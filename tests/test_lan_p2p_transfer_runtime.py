import unittest
from unittest.mock import Mock

from lan_p2p_network_paths import IPv4InterfacePath
from lan_p2p_transfer import TransferValidationError
from lan_p2p_transfer_runtime import TransferListenerRuntime, select_transfer_path


class TransferListenerRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.wifi = IPv4InterfacePath("wlan0", "192.168.50.2", 24, "192.168.50.255")
        self.usb = IPv4InterfacePath("usb0", "10.20.0.2", 24, "10.20.0.255")

    def test_selection_requires_explicit_choice_when_multiple_paths_exist(self):
        with self.assertRaises(TransferValidationError):
            select_transfer_path((self.wifi, self.usb))
        self.assertEqual(self.usb, select_transfer_path(
            (self.wifi, self.usb), interface_name="usb0"))
        self.assertEqual(self.wifi, select_transfer_path(
            (self.wifi, self.usb), address="192.168.50.2"))

    def test_selection_rejects_missing_and_ambiguous_paths(self):
        with self.assertRaises(TransferValidationError):
            select_transfer_path(())
        duplicate = IPv4InterfacePath("wlan0", "192.168.50.3", 24, "192.168.50.255")
        with self.assertRaises(TransferValidationError):
            select_transfer_path((self.wifi, duplicate), interface_name="wlan0")

    def test_start_passes_exact_path_to_path_bound_server(self):
        paths = [self.wifi]
        made = []

        class FakeServer:
            def __init__(self, **kwargs):
                self.kwargs = kwargs
                self.started = False
                self.stopped = False
                made.append(self)

            def start(self):
                self.started = True
                return self

            def shutdown(self):
                self.stopped = True

        runtime = TransferListenerRuntime(
            path_provider=lambda: tuple(paths), server_factory=FakeServer,
            local_peer_id="local", authenticate_peer=lambda *_: ("peer", b"x" * 32),
            now_ms=lambda: 1,
        )
        server = runtime.start()
        self.assertTrue(server.started)
        self.assertEqual("192.168.50.2", server.kwargs["host"])
        self.assertIs(self.wifi, server.kwargs["network_path"])
        self.assertIs(server, runtime.server)
        runtime.close()
        self.assertTrue(server.stopped)
        self.assertIsNone(runtime.server)

    def test_refresh_retains_same_path_and_rebinds_changed_path(self):
        paths = [self.wifi]
        made = []

        class FakeServer:
            def __init__(self, **kwargs):
                self.kwargs = kwargs
                self.stopped = False
                made.append(self)

            def start(self):
                return self

            def shutdown(self):
                self.stopped = True

        runtime = TransferListenerRuntime(
            path_provider=lambda: tuple(paths), server_factory=FakeServer,
            local_peer_id="local", authenticate_peer=lambda *_: ("peer", b"x" * 32),
            now_ms=lambda: 1,
        )
        first = runtime.start()
        self.assertIs(first, runtime.refresh())
        self.assertEqual(1, len(made))
        paths[:] = [self.usb]
        second = runtime.refresh()
        self.assertIsNot(first, second)
        self.assertTrue(first.stopped)
        self.assertEqual("10.20.0.2", second.kwargs["host"])
        self.assertIs(self.usb, runtime.path)
        runtime.close()

    def test_refresh_without_path_stops_listener_and_never_falls_back(self):
        paths = [self.wifi]
        made = []

        class FakeServer:
            def __init__(self, **kwargs):
                self.stopped = False
                made.append(self)

            def start(self):
                return self

            def shutdown(self):
                self.stopped = True

        runtime = TransferListenerRuntime(
            path_provider=lambda: tuple(paths), server_factory=FakeServer,
            local_peer_id="local", authenticate_peer=lambda *_: ("peer", b"x" * 32),
            now_ms=lambda: 1,
        )
        first = runtime.start()
        paths.clear()
        self.assertIsNone(runtime.refresh())
        self.assertTrue(first.stopped)
        self.assertIsNone(runtime.server)
        self.assertEqual(1, len(made))
        runtime.close()

    def test_failed_rebind_leaves_runtime_stopped(self):
        paths = [self.wifi]
        made = []

        class FakeServer:
            def __init__(self, **kwargs):
                self.kwargs = kwargs
                self.stopped = False
                made.append(self)

            def start(self):
                if self.kwargs["host"] == "10.20.0.2":
                    raise OSError("interface bind denied")
                return self

            def shutdown(self):
                self.stopped = True

        runtime = TransferListenerRuntime(
            path_provider=lambda: tuple(paths), server_factory=FakeServer,
            local_peer_id="local", authenticate_peer=lambda *_: ("peer", b"x" * 32),
            now_ms=lambda: 1,
        )
        first = runtime.start()
        paths[:] = [self.usb]
        with self.assertRaises(OSError):
            runtime.refresh()
        self.assertTrue(first.stopped)
        self.assertIsNone(runtime.server)
        self.assertIsNone(runtime.path)
        self.assertTrue(made[-1].stopped)
        runtime.close()

    def test_runtime_cannot_restart_after_close(self):
        runtime = TransferListenerRuntime(path_provider=lambda: (self.wifi,),
            server_factory=Mock(), local_peer_id="local",
            authenticate_peer=lambda *_: ("peer", b"x" * 32), now_ms=lambda: 1)
        runtime.close()
        with self.assertRaises(RuntimeError):
            runtime.start()


if __name__ == "__main__":
    unittest.main()
