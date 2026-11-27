import unittest
from unittest.mock import patch

import lan_p2p_transfer_host


class _StopAfterOneRefresh:
    def __init__(self):
        self.waits = 0

    def wait(self, timeout):
        self.waits += 1
        if self.waits == 1:
            return False
        return True

    def set(self):
        pass


class HostLifecycleTests(unittest.TestCase):
    def test_foreground_host_periodically_refreshes_service_and_closes(self):
        stop_event = _StopAfterOneRefresh()

        class FakeService:
            def __init__(self, **kwargs):
                self.kwargs = kwargs
                self.refresh_calls = 0
                self.closed = False

            def start(self):
                return ("192.168.50.2", 49152)

            def refresh(self):
                self.refresh_calls += 1
                return ("192.168.60.2", 49152)

            def close(self):
                self.closed = True

        made = []

        def service_factory(**kwargs):
            service = FakeService(**kwargs)
            made.append(service)
            return service

        with patch.object(lan_p2p_transfer_host, "LanTransferService", side_effect=service_factory), \
             patch.object(lan_p2p_transfer_host, "load_or_create_peer_id", return_value="python-node-1"), \
             patch.object(lan_p2p_transfer_host.signal, "signal"), \
             patch.object(lan_p2p_transfer_host.threading, "Event", return_value=stop_event):
            result = lan_p2p_transfer_host.main([
                "--credentials", "trusted.json",
                "--data-dir", "data",
                "--identity-file", "identity.json",
                "--refresh-interval", "0.25",
            ])

        self.assertEqual(0, result)
        self.assertEqual(1, len(made))
        self.assertEqual(1, made[0].refresh_calls)
        self.assertTrue(made[0].closed)
        self.assertEqual(0.25, stop_event.waits and 0.25)

    def test_refresh_interval_must_be_positive(self):
        with self.assertRaises(SystemExit):
            lan_p2p_transfer_host.main([
                "--credentials", "trusted.json",
                "--data-dir", "data",
                "--identity-file", "identity.json",
                "--refresh-interval", "0",
            ])


if __name__ == "__main__":
    unittest.main()
