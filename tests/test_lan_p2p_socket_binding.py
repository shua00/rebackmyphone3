import socket
import sys
import unittest
from types import SimpleNamespace

from lan_p2p_socket_binding import bind_socket_to_path, validate_endpoint_on_path


class FakeSocket:
    family = socket.AF_INET

    def __init__(self):
        self.options = []
        self.bound = None

    def setsockopt(self, level, option, value):
        self.options.append((level, option, value))

    def bind(self, address):
        self.bound = address


class FakeSocketModule:
    AF_INET = socket.AF_INET
    SOL_SOCKET = socket.SOL_SOCKET
    SO_BINDTODEVICE = getattr(socket, "SO_BINDTODEVICE", 25)
    IPPROTO_IP = socket.IPPROTO_IP
    IP_UNICAST_IF = 31

    @staticmethod
    def if_nametoindex(name):
        if name == "missing":
            raise OSError("no such interface")
        return 7

    @staticmethod
    def htonl(value):
        return socket.htonl(value)


class LanSocketBindingTests(unittest.TestCase):
    def setUp(self):
        self.path = SimpleNamespace(
            interface_name="wlan-test",
            address="192.168.40.2",
            prefix_length=24,
            broadcast_address="192.168.40.255",
        )

    def test_linux_binds_device_and_source_before_connect(self):
        sock = FakeSocket()
        result = bind_socket_to_path(
            sock, self.path, remote_address="192.168.40.9",
            platform="linux", socket_module=FakeSocketModule)
        self.assertEqual("wlan-test", result.interface_name)
        self.assertEqual(7, result.interface_index)
        self.assertEqual(("192.168.40.2", 0), sock.bound)
        self.assertEqual(FakeSocketModule.SO_BINDTODEVICE, sock.options[0][1])
        self.assertEqual(b"wlan-test" + bytes((0,)), sock.options[0][2])

    def test_windows_uses_ip_unicast_if_and_network_order_index(self):
        sock = FakeSocket()
        bind_socket_to_path(sock, self.path, platform="win32",
                            socket_module=FakeSocketModule)
        self.assertEqual(FakeSocketModule.IP_UNICAST_IF, sock.options[0][1])
        self.assertEqual(socket.htonl(7), __import__("struct").unpack("@I", sock.options[0][2])[0])
        self.assertEqual(("192.168.40.2", 0), sock.bound)

    def test_rejects_endpoint_outside_selected_subnet_before_binding(self):
        sock = FakeSocket()
        with self.assertRaises(ValueError):
            bind_socket_to_path(sock, self.path, remote_address="192.168.41.9",
                                platform="linux", socket_module=FakeSocketModule)
        self.assertEqual([], sock.options)
        self.assertIsNone(sock.bound)

    def test_rejects_invalid_prefix_and_non_ipv4_socket(self):
        invalid = SimpleNamespace(interface_name="wlan-test", address="192.168.40.2",
                                  prefix_length=0)
        with self.assertRaises(ValueError):
            bind_socket_to_path(FakeSocket(), invalid, platform="linux",
                                socket_module=FakeSocketModule)
        ipv6 = FakeSocket()
        ipv6.family = socket.AF_INET6
        with self.assertRaises(ValueError):
            bind_socket_to_path(ipv6, self.path, platform="linux",
                                socket_module=FakeSocketModule)

    def test_missing_interface_index_fails_closed(self):
        missing = SimpleNamespace(interface_name="missing", address="192.168.40.2",
                                  prefix_length=24)
        with self.assertRaises(OSError):
            bind_socket_to_path(FakeSocket(), missing, platform="linux",
                                socket_module=FakeSocketModule)

    def test_unsupported_platform_fails_closed(self):
        with self.assertRaises(OSError):
            bind_socket_to_path(FakeSocket(), self.path, platform="freebsd",
                                socket_module=FakeSocketModule)

    def test_linux_permission_failure_does_not_fall_back_to_source_address_only(self):
        class DeniedSocket(FakeSocket):
            def setsockopt(self, level, option, value):
                raise PermissionError("SO_BINDTODEVICE denied")

        sock = DeniedSocket()
        with self.assertRaises(PermissionError):
            bind_socket_to_path(sock, self.path, remote_address="192.168.40.9",
                                platform="linux", socket_module=FakeSocketModule)
        self.assertIsNone(sock.bound)

    def test_windows_interface_binding_failure_does_not_fall_back(self):
        class DeniedSocket(FakeSocket):
            def setsockopt(self, level, option, value):
                raise OSError("IP_UNICAST_IF unavailable")

        sock = DeniedSocket()
        with self.assertRaisesRegex(OSError, "IP_UNICAST_IF"):
            bind_socket_to_path(sock, self.path, platform="win32",
                                socket_module=FakeSocketModule)
        self.assertIsNone(sock.bound)

    @unittest.skipUnless(sys.platform == "win32", "Windows IP_UNICAST_IF smoke test")
    def test_windows_ip_unicast_if_binds_real_loopback_socket(self):
        loopback_name = next(
            (name for name, _index in socket.if_nameindex()
             if "loopback" in name.lower()),
            None,
        )
        if loopback_name is None:
            self.skipTest("Windows loopback interface name is unavailable")

        path = SimpleNamespace(
            interface_name=loopback_name,
            address="127.0.0.1",
            prefix_length=8,
            broadcast_address=None,
        )
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        accepted = None
        try:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(3.0)
            client.settimeout(3.0)
            binding = bind_socket_to_path(
                client, path, remote_address="127.0.0.1",
                platform="win32", socket_module=socket,
            )
            self.assertGreater(binding.interface_index, 0)
            self.assertEqual(loopback_name, binding.interface_name)
            client.connect(listener.getsockname())
            accepted, _address = listener.accept()
            self.assertEqual("127.0.0.1", accepted.getpeername()[0])
        finally:
            if accepted is not None:
                accepted.close()
            client.close()
            listener.close()

    def test_endpoint_validation(self):
        validate_endpoint_on_path(self.path, "192.168.40.99")
        with self.assertRaises(ValueError):
            validate_endpoint_on_path(self.path, "10.0.0.8")


if __name__ == "__main__":
    unittest.main()
