import base64
import json
import os

import pytest

from lan_p2p_network_paths import IPv4InterfacePath
from lan_p2p_transfer import TransferValidationError
from lan_p2p_transfer_service import LanTransferService, PeerCredentialStore


def _credentials(tmp_path, peers=None):
    path = tmp_path / "trusted-peers.json"
    peers = peers if peers is not None else {
        "android-peer-1": base64.b64encode(b"x" * 32).decode("ascii")
    }
    path.write_text(json.dumps({"version": 1, "peer_secrets": peers}), encoding="utf-8")
    os.chmod(path, 0o600)
    return str(path)


def test_credential_store_exposes_only_explicit_peer_secrets(tmp_path):
    path = _credentials(tmp_path)
    store = PeerCredentialStore(path)
    assert store.trusted_peer_ids == ("android-peer-1",)
    assert store.is_trusted("android-peer-1")
    assert not store.is_trusted("discovered-only-peer")
    assert store.secret_for("android-peer-1") == base64.b64encode(b"x" * 32).decode("ascii")
    assert store.secret_for("discovered-only-peer") is None


@pytest.mark.parametrize("document", [
    {"version": 1, "peer_secrets": {}},
    {"version": 2, "peer_secrets": {"peer": base64.b64encode(b"x" * 32).decode("ascii")}},
    {"version": 1, "peer_secrets": {"peer": "not-base64"}},
    {"version": 1, "peer_secrets": {"peer": base64.b64encode(b"short").decode("ascii")}},
    {"version": 1, "peer_secrets": {"bad peer id": base64.b64encode(b"x" * 32).decode("ascii")}},
])
def test_credential_store_rejects_invalid_or_untrusted_configuration(tmp_path, document):
    path = tmp_path / "trusted-peers.json"
    path.write_text(json.dumps(document), encoding="utf-8")
    os.chmod(path, 0o600)
    with pytest.raises(TransferValidationError):
        PeerCredentialStore(str(path))


@pytest.mark.skipif(os.name != "posix", reason="POSIX credential-file mode policy")
def test_credential_store_rejects_group_or_world_readable_file(tmp_path):
    path = _credentials(tmp_path)
    os.chmod(path, 0o644)
    with pytest.raises(TransferValidationError, match="0600"):
        PeerCredentialStore(path)


class FakeServer:
    def __init__(self, **kwargs):
        self.kwargs = kwargs
        self.started = False
        self.stopped = False
        self.address = (kwargs["host"], kwargs["port"] or 49152)

    def start(self):
        self.started = True
        return self

    def shutdown(self):
        self.stopped = True


def test_service_explicit_lifecycle_and_network_refresh(tmp_path):
    credentials = _credentials(tmp_path)
    paths = [IPv4InterfacePath("wlan-test", "192.168.50.2", 24, "192.168.50.255")]
    servers = []

    def factory(**kwargs):
        server = FakeServer(**kwargs)
        servers.append(server)
        return server

    service = LanTransferService(
        local_peer_id="python-node-1",
        data_dir=str(tmp_path / "service-data"),
        credentials_path=credentials,
        path_provider=lambda: tuple(paths),
        server_factory=factory,
    )
    assert not service.is_running
    assert service.start() == ("192.168.50.2", 49152)
    assert service.is_running
    assert service.refresh() == ("192.168.50.2", 49152)
    assert len(servers) == 1

    paths[:] = [IPv4InterfacePath("wlan-test", "192.168.60.2", 24, "192.168.60.255")]
    assert service.refresh() == ("192.168.60.2", 49152)
    assert servers[0].stopped
    assert len(servers) == 2

    paths.clear()
    assert service.refresh() is None
    assert servers[1].stopped
    assert not service.is_running
    service.close()
    service.close()
    with pytest.raises(RuntimeError, match="closed"):
        service.start()


def test_service_requires_explicit_unambiguous_path_and_valid_peer_id(tmp_path):
    credentials = _credentials(tmp_path)
    with pytest.raises(TransferValidationError, match="stable"):
        LanTransferService(
            local_peer_id="",
            data_dir=str(tmp_path / "data"),
            credentials_path=credentials,
        )

    service = LanTransferService(
        local_peer_id="python-node-1",
        data_dir=str(tmp_path / "service-data"),
        credentials_path=credentials,
        path_provider=lambda: (
            IPv4InterfacePath("wlan0", "192.168.1.2", 24, "192.168.1.255"),
            IPv4InterfacePath("eth0", "10.0.0.2", 24, "10.0.0.255"),
        ),
        server_factory=lambda **kwargs: FakeServer(**kwargs),
    )
    with pytest.raises(TransferValidationError, match="multiple"):
        service.start()
    assert not service.is_running
    service.close()
