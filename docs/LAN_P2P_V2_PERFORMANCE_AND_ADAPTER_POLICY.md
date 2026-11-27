# LAN P2P v2 — Performance and Adapter Compatibility Policy

Status: normative architecture constraints for the IPv4 P0 implementation and later P1/P2 adapters.

## Goals

LAN P2P v2 must support multiple independently usable IPv4 network paths (including Android STA + SoftAP topologies) without imposing permanent multi-path cost on ordinary single-path LAN devices.

Complex-topology support is an additive capability. It must not make the common single-router LAN case slower, noisier, less reliable, or materially more expensive in CPU, memory, sockets, wakeups, battery, or network traffic. The baseline is not limited to exactly two devices: it includes two or more peers on the same ordinary router, with any supported mix of Android and Python endpoints.

## Runtime operating modes

### Single-path fast path

When exactly one fresh, authenticated, usable path exists for a peer (including a multi-peer ordinary-router LAN where several Android and/or Python devices share the same routed subnet):

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
- The durable `lan_peers` row is peer-wide and has no local ingress-path identity. Its endpoint is a membership/discovery hint and may seed a directed challenge, but it must never authorize chat or fragment payload transmission by itself.
- Payload transmission requires a fresh path-scoped in-memory route binding that still matches the exact active listener socket and generation at dispatch time. A cached endpoint learned before process restart or on a removed path must be challenged again before it becomes a send route.
- Message-specific `last_attempt_endpoint` and `last_sent_endpoint` fields remain delivery/ACK evidence only; they do not grant a reusable peer route or cross-path trust.

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

The implementation must compare (A) an ordinary single-router LAN with two or more peers, including Android↔Android, Android↔Python and Python↔Android communication, against (B) the complex STA/AP topology. A is the baseline fast-path scenario, not a two-device-only special case. Include three-or-more-peer fan-in/fan-out and simultaneous conversations where supported. At minimum, record:

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

Current priority remains IPv4 ordinary-router LAN (two or more Android/Python peers), IPv4 STA/AP and complex IPv4 LAN topologies. The ordinary-router Android/Python multi-peer case is a required P0 regression baseline, not merely a performance comparison. P1/P2 are compatibility constraints, not implementation commitments for this phase. Existing chat, long-message, pairing, file-transfer isolation, Rescue2 command handling and log collection behavior must not regress.


## Unified network awareness and adaptive routing

The project-wide P0 architecture contract is defined in `docs/UNIFIED_NETWORK_AWARENESS_AND_ADAPTIVE_ROUTING.md`. This performance policy is a binding constraint on that architecture: awareness and route selection must be event-driven and path-scoped, and the single-path ordinary-router fast path must not incur multi-path scanning, probing, fan-out, or worker overhead. Rescue2/Server2 UDP-versus-HTTP command routing is a later P0 integration stage after LAN P2P v2 IPv4 real-device acceptance; it must reuse authenticated reachability facts and command idempotency rather than infer LAN reachability from subnet similarity.


## Network awareness activation budget (hard constraint)

The shared awareness capability may be present in every build, but its work is conditional. A stable single-path peer with a fresh authenticated route must not cause interface scans, route-list allocation, active probes, extra workers/timers, or periodic wakeups. Path changes, stale leases, explicit failures/timeouts, or user-requested diagnostics may activate targeted work for only the affected peer/path, with coalescing, cooldown, backoff, and bounded concurrency. Quality scores must come from real RTT/ACK/retry observations; insufficient samples use a deterministic conservative fallback rather than invented scores.
