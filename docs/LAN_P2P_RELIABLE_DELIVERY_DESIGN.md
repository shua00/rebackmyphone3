# LAN P2P Reliable Delivery Architecture (v2-only)

Status: approved implementation baseline
Branch: `feature/lan-p2p-v2-only`
Scope: reliable text chat between Android peers and the existing Python services. This document does not authorize changing the remote floating-message channel or removing any existing feature.

## 1. Goals and non-goals

### Goals
- Android devices communicate directly on the same LAN when `server2.py`, `rescue2.py`, and `chat.py` are all stopped.
- A sender can account for delivery independently for every known recipient.
- A recipient ACKs only after durable inbox storage succeeds.
- Retries reuse the original message ID; duplicate delivery is ACKed but never displayed twice.
- Member identity survives process restarts and IP/network changes.
- Pending messages and ACK state survive process restart and temporary network loss.
- Existing Python services remain independently usable and retain their current responsibilities.
- The remote floating-message path stays strictly separate from peer chat.

### Non-goals for this implementation
- No virtual network interface, VPN, NAT hole punching, or mandatory relay.
- No WebSocket cluster or new cloud messaging service.
- No binary/image/file/voice transfer over UDP. Those payloads are a later transport extension.
- No new Android third-party dependency, Room, or mandatory background daemon.
- No hidden tracking or bypass of user-controlled stop behavior.

## 2. Architectural model

Separate four concerns:
1. **Identity**: stable logical peer/service identity.
2. **Membership/control plane**: authenticated discovery, leases, capabilities, and current endpoints.
3. **Message/data plane**: outbox, per-recipient delivery state, inbox, ACK, retry.
4. **Application presentation**: existing chat history/UI; no transport-specific UI rewrite.

Transport selection:
- Same-LAN discovery: UDP broadcast to the existing service ports.
- Known same-LAN recipient: UDP unicast to the last verified endpoint.
- Delivery confirmation: authenticated UDP ACK.
- Remote/web messages: existing HTTP/server queue path, upgraded to durable message/recipient state without replacing existing command/report paths.
- Future large payloads: separately negotiated TCP/HTTP transfer; never place large payloads into UDP datagrams.

Current ports remain: Android 8888, server2 8887, rescue2 8889, chat.py 8890. Preserve existing listener behavior and service-specific filters unless a coordinated protocol migration explicitly changes them.

## 3. Stable identity

Every logical endpoint has a stable ID:
- Android: retain the existing persisted `saved_device_id`.
- server2: persist one service ID in its existing durable state/database.
- rescue2: persist a separate service ID.
- chat.py: persist a separate service ID.

A process/session UUID is not a peer identity. Runtime boot/session IDs may be included for diagnostics, but never used as a durable identity or delivery target.

A member record contains:
- `peer_id` (stable primary identity)
- `peer_type` (`android`, `server2`, `rescue2`, `chat`)
- `service_id` (stable logical service endpoint)
- `protocol_version`, `capabilities`
- `network_scope` (best-effort interface/subnet/gateway fingerprint; not an authentication factor)
- `endpoint_ip`, `endpoint_port` (temporary route only)
- `last_seen_at`, `last_verified_at`, `lease_expires_at`
- `status`: `active`, `suspect`, `offline`, `expired`

IP address is never identity. Network changes invalidate endpoint routes and trigger rediscovery, but do not delete the stable peer or its delivery history. Lease expiry marks a member offline; it does not delete it.

## 4. Versioned control and message envelope

Use a bounded UTF-8 JSON envelope for the coordinated v2 protocol. All receivers enforce a datagram size ceiling (initial target: 1200 bytes including envelope); oversized messages are rejected before parsing/processing. Text bodies are validated by UTF-8 byte length, not Java character count.

Common fields:
- `v`: integer protocol version (2)
- `type`: message type
- `sender_peer_id`: stable sender identity
- `sender_peer_type`: sender type
- `service_id`: sender logical service
- `nonce`: challenge nonce where applicable
- `network_scope`: advisory network fingerprint
- `capabilities`: supported feature names

Control messages:
- `peer.hello`: sender identity, service, nonce, version, capabilities.
- `peer.ack`: responder identity and exact `reply_nonce`; source endpoint is recorded only after authentication and nonce validation.
- `peer.goodbye` is optional and advisory; lease expiry remains authoritative.

Message:
- `chat.message`: `message_id` (UUID), `sender_peer_id`, `target_peer_id`, `created_at_ms`, `body`, optional `body_digest`.
- `chat.ack`: `message_id`, `sender_peer_id` (original sender), `receiver_peer_id` (actual recipient), `status=stored`, optional digest.
- `chat.read` is reserved for a future explicit read receipt; `stored` must never be presented as read.

Authentication:
- Retain the current shared-secret trust model for the initial migration, but authenticate protocol envelopes with HMAC-SHA256 using a derived key and constant-time comparison where platform APIs allow.
- The HMAC covers the canonical serialized fields and message body/digest. Reject missing/invalid authentication.
- Nonces prevent stale discovery responses from refreshing routes. Message IDs and recipient identity prevent ACK cross-talk.
- The APK-embedded shared secret is not equivalent to per-device cryptographic identity; future pairing/per-device keys can strengthen this without changing message semantics.

The existing colon-delimited `FIND_ME_*` protocol is replaced only through a coordinated four-endpoint rollout on this v2-only branch. Do not deploy a mixed protocol where one endpoint expects JSON and another still expects the old envelope. Floating-message protocol identifiers and payload handling are excluded from this migration.

## 5. Membership and route state

Discovery is event-driven and bounded:
- Discover at first send when no valid membership exists, after network change, after route failure, or when the lease refresh policy says it is due.
- Do not force a 500 ms discovery wait before every message when valid routes exist.
- Broadcast to existing ports and collect responses for a bounded discovery window (initial 500 ms).
- Validate version, HMAC, nonce, peer ID, peer type, service ID, and source endpoint before updating membership.
- Keep separate logical peers for separate services even if they share a host IP.
- Repeated valid hello/ack refreshes lease. A route is only a current endpoint, not durable identity.
- Use a conservative lease (initial proposal 120 seconds) with active/suspect/offline states. Exact refresh timing must avoid high-frequency background scans.

A network fingerprint is advisory and should combine available interface/subnet/gateway details. Do not infer LAN membership from matching private IP prefixes alone. The cryptographic handshake and response source endpoint establish the usable route.

## 6. Durable message delivery

### Sender outbox
Each outgoing message has:
- `message_id`, `sender_peer_id`, `message_type=chat`, `body`, `created_at_ms`, `protocol_version`
- overall state: `discovery_pending`, `pending`, `partial`, `delivered`, `expired`
- `delivery_deadline` and diagnostic timestamps.

Each recipient has a separate target row:
- `message_id`, `peer_id`
- `status`: `pending`, `sent`, `acked`, `expired`
- `attempt_count`, `last_attempt_at`, `last_ack_at`
- `last_endpoint`, `last_error`

Rules:
1. Persist the message before attempting network transmission.
2. For a known member set, snapshot all known members at message creation as fixed targets, including temporarily offline members.
3. If no members are known, set `discovery_pending`; the first bounded discovery cycle adds every peer found in that cycle as the fixed target set. If none is found, retain the message as `waiting_peers`.
4. Do not continually add newly joining peers to an old message. A later peer receives messages created after it became a known member, unless the user explicitly selects it as a target.
5. ACK from a target marks only that target as acked. An ACK from a non-target is ignored.
6. Acked targets are never resent. Pending targets alone are retried.
7. The message becomes delivered only when all fixed targets are acked. An empty target set is never considered delivered.
8. Retries reuse the exact message ID and body. ACK loss is safe because inbox deduplication is durable.
9. Queue capacity is bounded (initially preserve current 50-message Android cap). When full, reject the new message explicitly; never overwrite an older pending message.
10. Expiration is a visible state, not silent deletion. Retain diagnostic metadata and require an explicit retention/cleanup policy before removing bodies.

Initial retry schedule: short bounded retries (for example 300 ms, 1 s, 3 s, 10 s), then exponential backoff with a ceiling; network restoration triggers an immediate bounded retry. These are tunable parameters, not guaranteed delivery-time promises. Apply per-peer retry limits and avoid synchronized broadcast storms.

### Recipient inbox
- Unique key: `(receiver_scope, sender_peer_id, message_id)`.
- Store message ID, body, sender, creation time and receive time in one transaction.
- Send `chat.ack(status=stored)` only after transaction commit succeeds.
- If the unique key already exists, send ACK again but do not create a second history/UI event.
- If storage fails, do not ACK.
- Keep inbox history and dedup retention long enough to cover sender retry/retention windows. Never prune dedup IDs while the corresponding message could still be retried.

## 7. Persistence implementation

Android:
- Use platform SQLiteOpenHelper (no Room or third-party dependency) for transactional peers/outbox/targets/inbox.
- Keep existing chat UI/history integration intact; the database becomes the durable transport ledger, not a replacement UI.
- Migrate `PREF_LAN_CHAT_QUEUE` rows preserving message ID, body, and creation time. Legacy rows start in `discovery_pending`; do not discard them because target information is absent.
- Migrate existing received message IDs/history before retiring old preferences. Migration is versioned, idempotent, and transactional.
- Database failure blocks ACK and leaves sender outbox intact.

Python:
- Reuse existing SQLite infrastructure and transaction patterns; do not add a database framework.
- server2 and rescue2 may share a database file but must retain distinct `receiver_scope` values.
- chat.py retains its independent service scope and existing independent history behavior.
- Persist each service's stable peer ID in durable local state.
- Preserve existing tables and data; use additive schema migrations.

Concurrency:
- One serialized owner/worker per outbox transaction path.
- SQLite transactions protect message + target changes.
- Network receive threads must not block on UI work. Bound worker queues and define overload behavior.
- Never ACK before durable inbox commit.

## 8. Strict separation from floating messages

This work changes peer chat only:
- Peer chat: existing `FIND_ME_CHAT` semantic path / new `chat.message` envelope, `chat.ack`, `handleIncomingChat()`, chat history and chat UI.
- Remote floating message: `FIND_ME_FLOAT_MSG` / `custom_msg` and `showFloatingMessage(content)`.

The floating-message protocol, display, close behavior, history, read/clear behavior, server queue and routing must remain unchanged. Do not share chat dedup namespaces, inbox rows, queue cleanup, or UI notification paths with floating messages. Do not modify `ChatActivity.java` as part of transport implementation.

## 9. Existing Python services and web path

- server2, rescue2 and chat.py keep their existing service features, listener roles, filters, and management/control functionality.
- They each participate as distinct stable logical peers.
- The web/HTTP path remains available for off-LAN communication. It must report server acceptance separately from recipient device storage ACK.
- Web-originated chat messages should enter a durable server outbox with explicit recipient targets and use the same message ID semantics when delivered to a device.
- Existing remote floating-message delivery remains a separate pipeline and is not converted into chat messages.
- Do not introduce WebSocket, NAT traversal, relay, or file transfer in this phase.

## 10. Migration and rollout order

Because all four endpoints must understand the new envelope, protocol rollout is coordinated:
1. Add durable schema and stable IDs additively; verify legacy data is untouched.
2. Add a shared protocol codec and authentication implementation in Python and Android.
3. Add discovery/member lease handling.
4. Add sender outbox target ledger and recipient inbox transaction/ACK.
5. Integrate existing chat presentation callbacks without changing UI behavior.
6. Integrate web-originated chat after direct LAN path is stable.
7. Remove legacy peer-chat parsing only after all four v2 endpoints are updated together. Keep floating-message parser untouched.

Do not merge to main. Keep changes on `feature/lan-p2p-v2-only`.

## 11. Acceptance matrix

Required before calling the feature complete:
- Two Android devices chat bidirectionally with all three Python services stopped.
- Three or more Android devices discover and chat.
- Python services are individually discoverable and retain stable identity after restart.
- Same host running server2 and rescue2 appears as two distinct logical endpoints.
- Partial ACK: only missing recipients are retried.
- ACK loss: duplicate packet is ACKed again but displayed once.
- Receiver database failure: no ACK.
- Sender process restart: pending message and per-recipient ACK state recover.
- Receiver process restart: inbox dedup survives and prevents duplicate display.
- Wi-Fi loss/change/recovery: routes refresh and pending messages resume.
- Unknown members do not cause a broadcast ACK to be mistaken for all-recipient delivery.
- Queue full rejects new messages without overwriting pending messages.
- Web-originated chat reports actual device ACK separately from server acceptance.
- Floating-message display/history/read/clear behavior is unchanged.
- Existing rescue, location, management, and chat-window behavior remains intact.
- GitHub Actions build passes; this is necessary but not sufficient. Real multi-device tests are still required.

## 12. Implementation guardrails

- Preserve every existing feature; reliability improvements must not be achieved by deleting old functionality.
- Do not modify `ChatActivity.java`.
- Do not change the floating-message channel.
- No new third-party Android dependencies.
- No high-frequency permanent LAN scanning.
- Do not claim end-to-end delivery from UDP send success or server HTTP 200.
- Do not claim real-device acceptance based on compilation or static inspection.
- Any unsupported network type (for example, LANs without usable IPv4 broadcast) must be documented rather than silently claimed to work.


## 13. Implementation status (2026-11-22)

Completed foundations on `feature/lan-p2p-v2-only`:
- The design is committed as the approved implementation baseline.
- Python service IDs are persisted in SQLite and are stable across process restarts. server2 and rescue2 use separate service identities even though they share the dedup database; chat.py uses its own database.
- A shared `lan_p2p_core.py` persists Python-side peer observations with service-specific receiver scopes. A HELLO observation is recorded as `suspect`; a message that is durably accepted refreshes the peer as `active`.
- Android has a native SQLite `LanChatStore` with peer, outbox, per-recipient target, inbox, and metadata tables. It is wired to outgoing chat persistence, target snapshots, per-peer ACK persistence, and inbox-before-ACK storage.
- Android currently dual-writes the legacy SharedPreferences outbox and SQLite outbox. The old queue remains the compatibility/recovery mirror until migration and restart-recovery behavior are fully validated; it is not silently discarded.
- Android reuses verified LAN routes between bounded discovery refresh windows instead of waiting 500 ms before every message.
- CI now compiles the Python services and runs unit tests for stable identity and peer-scope persistence.

Not yet complete:
- The coordinated authenticated JSON v2 envelope and its Android/Python codec.
- Python-side per-recipient outbox/ACK tracking and durable web-originated chat delivery.
- Full migration of legacy Android receive IDs/history and retirement of the SharedPreferences compatibility mirror.
- End-to-end multi-device tests, including process restart, partial ACK, and floating-message isolation.

A successful APK build is only a compile check. The communication feature must not be declared complete until the remaining protocol/server work and real-device acceptance matrix pass.
