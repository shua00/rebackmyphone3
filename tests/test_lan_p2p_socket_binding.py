import socket
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
    IP_BOUND_IF = 25

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

    def test_macos_uses_ip_bound_if(self):
        sock = FakeSocket()
        bind_socket_to_path(sock, self.path, platform="darwin",
                            socket_module=FakeSocketModule)
        self.assertEqual((socket.IPPROTO_IP, FakeSocketModule.IP_BOUND_IF, 7), sock.options[0])
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

    def test_endpoint_validation(self):
        validate_endpoint_on_path(self.path, "192.168.40.99")
        with self.assertRaises(ValueError):
            validate_endpoint_on_path(self.path, "10.0.0.8")


if __name__ == "__main__":
    unittest.main()
