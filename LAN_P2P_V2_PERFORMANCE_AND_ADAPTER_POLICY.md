# LAN P2P v2 — Performance and Adapter Compatibility Policy

Status: normative architecture constraints for the IPv4 P0 implementation and later P1/P2 adapters.

## Goals

LAN P2P v2 must support multiple independently usable IPv4 network paths (including Android STA + SoftAP topologies) without imposing permanent multi-path cost on ordinary single-path LAN devices.

Complex-topology support is an additive capability. It must not make the common two-device/single-router case slower, noisier, less reliable, or materially more expensive in CPU, memory, sockets, wakeups, battery, or network traffic.

## Runtime operating modes

### Single-path fast path

When exactly one fresh, authenticated, usable path exists for a peer:

- Send directly over that path; do not fan out or duplicate packets across interfaces.
- Keep one listener for each actually active local path, not one listener per hypothetical adapter.
- Reuse fresh authenticated endpoint leases; do not perform a handshake for every message.
- Do not run periodic interface scans or per-peer high-frequency reachability probes.
- Keep ACK handling immediate and bounded; keep persistence/UI work off the receive hot path.
- Avoid allocating path lists, retry plans, or diagnostic payloads on every packet when a direct route is available.
- Preserve the existing wire protocol and message semantics wherever possible.

### Multi-path mode

When multiple fresh paths are genuinely available for a peer:

- Keep route records path-scoped: peer identity, endpoint, path fingerprint, socket/listener generation, verification time and expiry.
- Select one preferred eligible path for normal transmission.
- Try another eligible path only after a bounded failure/timeout or an explicit path invalidation; never broadcast every chat message on every path by default.
- Bind ACK and fragment-ACK acceptance to the message/peer/endpoint/path policy; do not let an ACK from an unrelated endpoint complete delivery.
- Isolate listener ownership and cleanup by path and generation so stale workers cannot close or invalidate replacement sockets.
- Expire stale routes and release resources promptly when a path disappears.
- Use bounded retries, bounded candidate counts and backoff. No unbounded endpoint fan-out.

## Path validity and trust

- Interface discovery, address enumeration and successful socket binding are not proof of end-to-end reachability.
- A route is usable only after the relevant authenticated peer exchange succeeds over that path and the route remains within its lease.
- Authentication on one path must not automatically authenticate an endpoint on another path.
- A path fingerprint is a routing/diagnostic discriminator, not a credential.
- Failed, ambiguous or incomplete path binding must fail closed for that candidate; it must not silently fall back to an unrelated network.

## Adapter boundary (P1/P2 compatibility)

Network-specific behavior belongs behind optional adapters. The common path/route/transport core must not contain product-specific branches for every future network type.

An adapter is responsible for:
1. discovering candidate interfaces and addresses;
2. binding sockets to the intended path where the platform supports it;
3. reporting path identity and lifecycle changes;
4. performing or supporting authenticated bidirectional reachability checks;
5. reporting structured failure reasons and recovery opportunities.

The shared core is responsible for:
- path identity and lifecycle;
- endpoint leases and authenticated route association;
- route selection and bounded failover;
- message/ACK reliability and duplicate suppression;
- diagnostics and resource ownership.

P1 candidates (USB, wired Ethernet, portable routers, repeaters, Mesh, VPN, multi-NIC, guest/client-isolated networks and multi-subnet LANs) are not part of the current implementation scope. Each must be added independently and pass discovery, binding, bidirectional reachability, recovery and failure-diagnostics tests.

P2 candidates (IPv6, Wi-Fi Direct and other special transports) remain optional adapters evaluated only after IPv4 P0/P1 stability. IPv6 scope/interface semantics and Wi-Fi Direct lifecycle must not become mandatory dependencies of the IPv4 core.

## Performance and regression acceptance

The implementation must compare a simple single-router baseline against the complex STA/AP topology. At minimum, record:

- number of active listener sockets and receive workers;
- idle CPU/wakeup behavior and memory attributable to path management;
- packet counts for discovery, handshake, message, ACK and retries;
- message send-to-ACK latency and delivery success under normal conditions;
- recovery time after the preferred path is removed and restored;
- resource cleanup after path churn.

Acceptance rules:

- In a stable single-path network, no periodic path-probing loop, redundant message fan-out, or extra always-on worker may be introduced solely for future adapters.
- A fresh authenticated route must be reused until expiry or a relevant network/path event invalidates it.
- Additional listeners, probes and route state must scale with active usable paths, not with the number of adapter types compiled into the application.
- Diagnostics must be bounded, redact secrets/content, and be disableable or removable according to the project's diagnostic-build policy.
- Performance thresholds must be measured on representative Android devices before being fixed as numeric release gates; do not invent device-independent latency or memory budgets.

## Current scope

Current priority remains IPv4 STA/AP and complex IPv4 LAN topologies. P1/P2 are compatibility constraints, not implementation commitments for this phase. Existing chat, long-message, pairing, file-transfer isolation, Rescue2 command handling and log collection behavior must not regress.
