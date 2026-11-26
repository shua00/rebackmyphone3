import unittest
from types import SimpleNamespace

from lan_p2p_network_paths import enumerate_ipv4_paths, directed_broadcast_addresses


class LanNetworkPathEnumerationTests(unittest.TestCase):
    def fake_psutil(self, entries, stats=None):
        return SimpleNamespace(
            net_if_stats=lambda: stats or {name: SimpleNamespace(isup=True) for name in entries},
            net_if_addrs=lambda: entries,
        )

    def test_enumerates_multiple_offline_private_subnets_without_external_probe(self):
        psutil = self.fake_psutil({
            "eth0": [SimpleNamespace(family=2, address="192.168.20.4",
                netmask="255.255.255.0", broadcast="192.168.20.255")],
            "wlan0": [SimpleNamespace(family=2, address="10.42.0.1",
                netmask="255.255.0.0", broadcast="10.42.255.255")],
        })
        paths = enumerate_ipv4_paths(psutil)
        self.assertEqual(("eth0", "wlan0"), tuple(p.interface_name for p in paths))
        self.assertEqual(("10.42.255.255", "192.168.20.255"),
            directed_broadcast_addresses(paths))

    def test_filters_loopback_link_local_and_invalid_addresses(self):
        psutil = self.fake_psutil({
            "lo": [SimpleNamespace(family=2, address="127.0.0.1",
                netmask="255.0.0.0", broadcast="127.255.255.255")],
            "bad": [SimpleNamespace(family=2, address="169.254.1.2",
                netmask="255.255.0.0", broadcast="169.254.255.255")],
            "invalid": [SimpleNamespace(family=2, address="not-an-ip",
                netmask="255.255.255.0", broadcast=None)],
        })
        self.assertEqual((), enumerate_ipv4_paths(psutil))

    def test_ignores_down_interfaces_and_missing_broadcast(self):
        psutil = self.fake_psutil({
            "down": [SimpleNamespace(family=2, address="192.168.1.2",
                netmask="255.255.255.0", broadcast="192.168.1.255")],
            "p2p": [SimpleNamespace(family=2, address="10.0.0.2",
                netmask="255.255.255.252", broadcast=None)],
        }, stats={"down": SimpleNamespace(isup=False), "p2p": SimpleNamespace(isup=True)})
        paths = enumerate_ipv4_paths(psutil)
        self.assertEqual(("p2p",), tuple(p.interface_name for p in paths))
        self.assertEqual((), directed_broadcast_addresses(paths))

    def test_missing_psutil_fails_closed(self):
        self.assertEqual((), enumerate_ipv4_paths(object()))


if __name__ == "__main__":
    unittest.main()
