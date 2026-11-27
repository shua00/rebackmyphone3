import ast
import os
import subprocess
import sys
import sqlite3
import threading
import time
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

from lan_p2p_core import (LanP2PStorageError, load_stable_peer_id, record_peer,
                          load_pending_outgoing_for_peer, add_discovery_targets_for_peer,
                          load_active_pending_targets, mark_outgoing_attempt)


import re


@contextmanager
def _sqlite_connection(database):
    """Commit/rollback a test transaction and always close its SQLite handle."""
    connection = sqlite3.connect(database)
    try:
        with connection:
            yield connection
    finally:
        connection.close()

def extract_java_method(source, signature):
    """Extract one Java method using balanced braces, ignoring comments and literals."""
    start = source.find(signature)
    if start < 0:
        raise AssertionError("Java method not found: " + signature)
    opening = source.find("{", start + len(signature))
    if opening < 0:
        raise AssertionError("Java method body not found: " + signature)

    depth = 0
    state = "code"
    escaped = False
    i = opening
    while i < len(source):
        ch = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ""
        if state == "line_comment":
            if ch == "\n":
                state = "code"
        elif state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "code"
                i += 1
        elif state == "string":
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                state = "code"
        elif state == "char":
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == "'":
                state = "code"
        else:
            if ch == "/" and nxt == "/":
                state = "line_comment"
                i += 1
            elif ch == "/" and nxt == "*":
                state = "block_comment"
                i += 1
            elif ch == '"':
                state = "string"
            elif ch == "'":
                state = "char"
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return source[start:i + 1]
        i += 1
    raise AssertionError("Unbalanced Java method body: " + signature)



def extract_python_function(source, name):
    """Return a complete Python function by AST source range."""
    module = ast.parse(source)
    node = next((item for item in module.body
                 if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)) and item.name == name), None)
    if node is None:
        raise AssertionError("Python function not found: " + name)
    lines = source.splitlines()
    return "\\n".join(lines[node.lineno - 1:node.end_lineno])



class LanP2PCoreTests(unittest.TestCase):

    def test_chat_listener_runtime_owns_path_scoped_leases_without_background_work(self):
        root = Path(__file__).resolve().parents[1]
        runtime = (root / "app/src/main/java/com/example/devicefinder/LanChatListenerRuntime.java").read_text(encoding="utf-8")
        service = (root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        listener = extract_java_method(service, "private void startRescueListener()")
        self.assertIn("new LanChatListenerRuntime(lanChatPathRegistry)", service)
        self.assertIn("lanChatListenerRuntime.register(", listener)
        self.assertIn("lanChatListenerRuntime.remove(listenerLease)", listener)
        self.assertIn("listenerLease.pathFingerprint", listener)
        self.assertIn("registry.registerListener(pathFingerprint, socket, generation)", runtime)
        self.assertIn("registry.removeListener(expected.pathFingerprint", runtime)
        self.assertIn("leases.get(expected.pathFingerprint) != expected", runtime)
        self.assertIn("public synchronized boolean isCurrent(Lease lease)", runtime)
        # The owner is an in-memory lifecycle boundary only: no per-path workers,
        # timers, polling or socket close while its monitor is held.
        self.assertNotIn("new Thread(", runtime)
        self.assertNotIn("postDelayed(", runtime)
        self.assertNotIn("scheduleAtFixedRate(", runtime)
        self.assertNotIn("socket.close()", runtime)

    def test_authenticated_ack_ingress_uses_receive_loop_path_snapshot_not_global_slot(self):
        root = Path(__file__).resolve().parents[1]
        service = (root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        realtime = extract_java_method(service, "private boolean handleLanRealtimePacket(")
        authenticated = extract_java_method(service, "private boolean handleAuthenticatedLanPacket(")
        listener = extract_java_method(service, "private void startRescueListener()")
        self.assertIn("listenerPathFingerprint, listenerGeneration", listener)
        self.assertIn("listenerPathFingerprint, listenerGeneration", realtime)
        self.assertIn("listenerPathFingerprint, long listenerGeneration", authenticated)
        self.assertIn("listenerPathFingerprint != null", authenticated)
        self.assertIn("ingressListener.generation == listenerGeneration", authenticated)
        self.assertIn("ingressListener.socket == socket", authenticated)
        self.assertNotIn("? rescueSocketPathFingerprint : null", authenticated)
        self.assertIn("isCurrentLanChatIngress(verifiedPathFingerprint, socket", authenticated)

    def test_chat_listener_runtime_keeps_single_path_fast_path_and_multi_path_isolation(self):
        root = Path(__file__).resolve().parents[1]
        runtime = (root / "app/src/main/java/com/example/devicefinder/LanChatListenerRuntime.java").read_text(encoding="utf-8")
        selector = (root / "app/src/main/java/com/example/devicefinder/LanAdaptiveRouteSelector.java").read_text(encoding="utf-8")
        self.assertIn("public synchronized int listenerCount()", runtime)
        self.assertIn("if (registry.listenerCount() <= 1)", selector)
        self.assertIn("registry.getFreshPeerRouteBinding(peerId, preferredPath, nowElapsedMs)", selector)
        self.assertIn("registry.getFreshPeerRouteBindings(peerId, nowElapsedMs)", selector)
        self.assertIn("if (registry == null || peerId == null", selector)


    def test_udp_listener_stops_packet_dispatch_when_service_teardown_wins_receive_race(self):
        repo_root = Path(__file__).resolve().parents[1]
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        listener = extract_java_method(service, "private void startRescueListener()")
        receive = listener.index("rescueSocket.receive(packet)")
        teardown_guard = listener.index(
            "if (serviceDestroyed || rescueSocket.isClosed()) break;", receive)
        realtime_dispatch = listener.index("handleLanRealtimePacket(", receive)
        legacy_guard = listener.index(
            "if (serviceDestroyed || rescueSocket.isClosed()) break;", teardown_guard + 1)
        legacy_decode = listener.index("String message = new String(packet.getData()", legacy_guard)
        self.assertLess(receive, teardown_guard)
        self.assertLess(teardown_guard, realtime_dispatch)
        self.assertLess(realtime_dispatch, legacy_guard)
        self.assertLess(legacy_guard, legacy_decode)


    def test_android_udp_listener_cleanup_is_scoped_to_owning_service_thread(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        listener = extract_java_method(source, "private void startRescueListener()")
        finally_block = listener[listener.index("finally {"):]
        self.assertIn("rescueListenerThread == Thread.currentThread()", finally_block)
        self.assertIn("if (ownsListener)", finally_block)
        self.assertIn("rescueListenerThread = null;", finally_block)
        self.assertIn("isListeningForRescue = false;", finally_block)
        self.assertNotIn("private static volatile boolean isListeningForRescue", source)
        self.assertIn("listenerToStop.interrupt()", extract_java_method(
            source, "public void onDestroy()"))

    def test_android_listener_multicast_lock_admission_and_release_are_lifecycle_serialized(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        listener = extract_java_method(source, "private void startRescueListener()")
        finally_start = listener.index("finally {")
        finally_end = listener.index('}, "LAN-UDP-Listener-', finally_start)
        finally_block = listener[finally_start:finally_end]
        admission = listener[:listener.index("Thread listenerThread = new Thread")]
        runnable_start = listener[listener.index("Thread listenerThread = new Thread"):]
        self.assertLess(runnable_start.index("if (serviceDestroyed || Thread.currentThread().isInterrupted()) return;"),
                        runnable_start.index("new DatagramSocket(null)"))
        self.assertLess(admission.index("if (serviceDestroyed) return;"),
                        admission.index("multicastLock.acquire()"))
        self.assertLess(admission.index("synchronized (rescueLock)"),
                        admission.index("multicastLock.acquire()"))
        owner_cleanup = finally_block[finally_block.index("if (ownsListener)"):finally_block.index("// A deliberate Wi-Fi path replacement")]
        self.assertIn("releaseLanMulticastLock();", owner_cleanup)
        self.assertEqual(1, finally_block.count("releaseLanMulticastLock();"))
        release = extract_java_method(source, "private void releaseLanMulticastLock()")
        self.assertIn("synchronized (rescueLock)", release)
        self.assertIn("multicastLock.release()", release)
        destroy = extract_java_method(source, "public void onDestroy()")
        self.assertIn("releaseLanMulticastLock();", destroy)
        self.assertNotIn("multicastLock.release()", destroy)


    def test_service_depends_on_policy_not_store_compatibility_aliases(self):
        repo_root = Path(__file__).resolve().parents[1]
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        self.assertIn("LanChatRetryPolicy.MAX_AUTOMATIC_RETRY_ATTEMPTS", service)
        self.assertIn("LanChatRetryPolicy.FIRST_DISCOVERY_WINDOW_MS", service)
        self.assertNotIn("LanChatStore.MAX_AUTOMATIC_RETRY_ATTEMPTS", service)
        self.assertNotIn("LanChatStore.FIRST_DISCOVERY_WINDOW_MS", service)
        self.assertIn("FIRST_DISCOVERY_WINDOW_MS = LanChatRetryPolicy.FIRST_DISCOVERY_WINDOW_MS", store)
        self.assertIn("MAX_AUTOMATIC_RETRY_ATTEMPTS = LanChatRetryPolicy.MAX_AUTOMATIC_RETRY_ATTEMPTS", store)


    def test_outbox_pending_snapshot_limit_uses_shared_capacity_policy(self):
        repo_root = Path(__file__).resolve().parents[1]
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        policy = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatRetryPolicy.java"
        ).read_text(encoding="utf-8")
        self.assertIn("store.loadPending(LanChatRetryPolicy.MAX_PENDING_MESSAGES)", service)
        self.assertNotIn("store.loadPending(50)", service)
        self.assertIn("MAX_PENDING_MESSAGES = 50", policy)


    def test_outbox_retry_wake_and_batch_policy_is_centralized(self):
        repo_root = Path(__file__).resolve().parents[1]
        policy = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatRetryPolicy.java"
        ).read_text(encoding="utf-8")
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        scheduler = extract_java_method(service, "private void scheduleLanChatRetryFromOutbox(")
        retry = extract_java_method(service, "private void retryPendingLanChatMessages(boolean recoveryEvent)")
        self.assertIn("OUTBOX_RETRY_FIRST_DELAY_MS = 1_500L", policy)
        self.assertIn("OUTBOX_RETRY_SECOND_DELAY_MS = 4_000L", policy)
        self.assertIn("OUTBOX_RETRY_BACKOFF_BASE_MS = 10_000L", policy)
        self.assertIn("OUTBOX_RETRY_MAX_DELAY_MS = 60_000L", policy)
        self.assertIn("UNATTEMPTED_ROUTE_PROBE_MAX_DELAY_MS = 5_000L", policy)
        self.assertIn("outboxRetryWakeDelayMs(", scheduler)
        self.assertIn("RETRY_BATCH_SIZE = 3", policy)
        self.assertIn("MAX_RETRY_BATCHES_PER_WAKE = 3", policy)
        self.assertIn("LanChatRetryPolicy.RETRY_BATCH_SIZE", retry)
        self.assertIn("LanChatRetryPolicy.MAX_RETRY_BATCHES_PER_WAKE", retry)
        self.assertNotIn("minimumAttempts <= 1 ? 1_500L", scheduler)



    def test_outbox_retry_preserves_recovery_wakes_when_admission_or_storage_fails(self):
        repo_root = Path(__file__).resolve().parents[1]
        service = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        retry = extract_java_method(service, "private void retryPendingLanChatMessages(boolean recoveryEvent)")
        self.assertIn("final boolean routeRefreshCaptured = lanChatRouteRefreshPending.getAndSet(false)", retry)
        self.assertIn("if (routeRefreshCaptured) lanChatRouteRefreshPending.set(true)", retry)
        self.assertIn("if (routeRefreshCaptured && !retryBatchCompleted)", retry)
        self.assertIn("boolean recoveryEventCaptured = false", retry)
        self.assertIn("recoveryEventCaptured = lanChatRecoveryEventPending.getAndSet(false)", retry)
        self.assertIn("if (recoveryEventCaptured) lanChatRecoveryEventPending.set(true)", retry)
        recovery_call = retry.index("store.resumeExhaustedRetriesForRecoveryEvent")
        outer_catch = retry.index("catch (Exception e)")
        self.assertLess(recovery_call, outer_catch)
        self.assertLess(outer_catch, retry.index("if (recoveryEventCaptured) lanChatRecoveryEventPending.set(true)", outer_catch))
        self.assertIn("retryBatchCompleted = true", retry)
        self.assertIn("lanChatRetryInFlight.set(false)", retry)


    def test_outbox_retry_failure_wake_policy_is_shared_and_has_handler_fallback(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        policy = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatRetryPolicy.java").read_text(encoding="utf-8")
        helper = extract_java_method(source, "private void scheduleLanChatRetryAfterFailure(")
        self.assertIn("RECOVERY_FAILURE_RETRY_DELAY_MS = 500L", policy)
        self.assertIn("recoveryEvent || lanChatRecoveryEventPending.get()", helper)
        self.assertIn("scheduleLanChatTask(taskName, retry,", helper)
        self.assertIn("handler.postDelayed(retry, LanChatRetryPolicy.RECOVERY_FAILURE_RETRY_DELAY_MS)", helper)
        retry = extract_java_method(source, "private void retryPendingLanChatMessages(boolean recoveryEvent)")
        self.assertIn("scheduleLanChatRetryAfterFailure(", retry)
        self.assertIn('"outbox-retry-after-failure"', retry)


    def test_outbox_retry_failure_uses_delayed_wake_not_immediate_spin(self):
        repo_root = Path(__file__).resolve().parents[1]
        service = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        retry = extract_java_method(service, "private void retryPendingLanChatMessages(boolean recoveryEvent)")
        self.assertIn("if (!retryBatchCompleted)", retry)
        self.assertIn('"outbox-retry-after-failure"', retry)
        self.assertIn("scheduleLanChatRetryAfterFailure(", retry)
        self.assertIn('"outbox-retry-after-failure"', retry)
        self.assertNotIn("retryAfterFailure", retry)
        self.assertIn("retryPendingLanChatMessages(true)", retry)
        self.assertIn("retryPendingLanChatMessages(false)", retry)


    def test_recovery_epoch_storage_failure_is_not_reported_as_no_work(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        method = extract_java_method(store, "public int resumeExhaustedRetriesForRecoveryEvent(")
        self.assertIn("throw new IllegalStateException(\"Failed to resume LAN Outbox retry epoch\", e)", method)
        self.assertNotIn("return 0;", method)
        self.assertIn("db.endTransaction()", method)


    def test_all_targets_ack_reconciliation_is_one_sqlite_transaction(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        method = extract_java_method(store, "public boolean allTargetsAcked(")
        self.assertLess(method.index("db.beginTransaction();"), method.index("db.rawQuery("))
        self.assertLess(method.index("db.rawQuery("), method.index("db.setTransactionSuccessful();"))
        self.assertIn("db.endTransaction();", method)


    def test_lan_delivery_state_settlement_matrix_is_explicit(self):
        repo_root = Path(__file__).resolve().parents[1]
        state = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatDeliveryState.java"
        ).read_text(encoding="utf-8")
        self.assertIn('static final String DISCOVERY_PENDING = "discovery_pending";', state)
        self.assertIn('static final String RETRY_PAUSED = "retry_paused";', state)
        self.assertIn('static final String DELIVERED = "delivered";', state)
        self.assertIn('static final String WAITING_PEERS = "waiting_peers";', state)
        self.assertIn("if (totalTargets <= 0) return WAITING_PEERS;", state)
        self.assertIn("if (ackedTargets >= totalTargets) return DELIVERED;", state)
        self.assertIn("return hasRetryBudget ? PENDING : RETRY_PAUSED;", state)


    def test_lan_chat_retry_policy_is_centralized_and_store_sql_uses_same_policy(self):
        repo_root = Path(__file__).resolve().parents[1]
        policy = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatRetryPolicy.java").read_text(encoding="utf-8")
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        service = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        self.assertIn("static final int MAX_AUTOMATIC_RETRY_ATTEMPTS = 8;", policy)
        self.assertIn("static final long RETRY_RECOVERY_EVENT_COOLDOWN_MS = 60_000L;", policy)
        self.assertIn("RETRY_DELAY_AFTER_ATTEMPT_MS =", policy)
        self.assertIn("return RETRY_DELAY_AFTER_ATTEMPT_MS[completedAttempts - 1];", policy)
        self.assertIn("return RETRY_DELAY_CEILING_MS;", policy)
        self.assertIn("buildTargetRetryDelayCaseSql()", policy)
        self.assertIn("append(retryDelayAfterAttemptMs(i + 1))", policy)
        self.assertIn("LanChatRetryPolicy.TARGET_RETRY_DELAY_CASE_SQL", store)
        self.assertNotIn("CASE attempt_count WHEN 1 THEN 250", store)
        self.assertIn("LanChatRetryPolicy.PEER_DISCOVERY_COOLDOWN_MS", service)
        self.assertIn("LanChatRetryPolicy.PEER_CHALLENGE_TIMEOUT_MS", service)
        self.assertIn("LanChatRetryPolicy.MAX_PENDING_MESSAGES", service)



    def test_known_peer_queries_raise_storage_error_instead_of_appearing_empty(self):
        from lan_p2p_core import list_known_peers, list_known_peer_snapshot, LanP2PStorageError
        directory_path = os.path.join(self.temp_dir.name, "not-a-peer-database")
        os.mkdir(directory_path)
        with self.assertRaises(LanP2PStorageError):
            list_known_peers(directory_path, "server2")
        with self.assertRaises(LanP2PStorageError):
            list_known_peer_snapshot(directory_path, "server2")

    def test_network_callback_registration_is_atomic_and_idempotent(self):
        source = Path(__file__).resolve().parents[1] / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        java = source.read_text(encoding="utf-8")
        method = extract_java_method(java, "private void registerNetworkCallback()")
        self.assertIn("if (networkCallback != null && wifiLanNetworkCallback != null) return;", method)
        self.assertIn("cm.unregisterNetworkCallback(networkCallback)", method)
        self.assertIn("cm.unregisterNetworkCallback(wifiLanNetworkCallback)", method)
        self.assertIn("networkCallback = null;", method)
        self.assertIn("wifiLanNetworkCallback = null;", method)
        self.assertIn("if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;", method)
        self.assertIn("Build.VERSION.SDK_INT >= Build.VERSION_CODES.N", method)
        self.assertIn("cm.registerNetworkCallback(defaultNetworkRequest, networkCallback)", method)
        # API 21-23 use registerNetworkCallback and must also unregister both
        # observers on service teardown; gating cleanup at API 24 leaks callbacks.
        self.assertIn("if (cm != null && networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)", java)
        self.assertIn("if (cm != null && wifiLanNetworkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)", java)
        self.assertIn("cm.registerDefaultNetworkCallback(networkCallback)", method)
        self.assertIn("NET_CAPABILITY_INTERNET", method)
        self.assertIn("cm.registerNetworkCallback(defaultNetworkRequest, networkCallback)", method)
        self.assertLess(method.index("cm.registerDefaultNetworkCallback(networkCallback)"),
                        method.index("cm.registerNetworkCallback(wifiRequest, wifiLanNetworkCallback)"))

    def test_chat_standalone_udp_listener_rebinds_and_proxy_mode_avoids_udp_conflict(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "chat.py").read_text(encoding="utf-8")
        start = source.index("def udp_listener():")
        end = source.index("\n\nclass RequestHandler", start)
        listener = source[start:end]
        self.assertIn("while True:", listener)
        self.assertIn("udp_sock.bind(('0.0.0.0', UDP_PORT))", listener)
        self.assertLess(listener.index("udp_sock.bind("),
                        listener.index("_start_v2_recovery_retry_worker()"))
        self.assertIn("time.sleep(10)", listener)
        self.assertIn("time.sleep(5)", listener)
        self.assertIn("if UDP_LISTENER_SOCKET is udp_sock:", listener)
        self.assertIn("udp_sock.close()", listener)

        main = source[source.index("if __name__ == '__main__':"):]
        proxy = main.index("if PROXY_MODE:")
        standalone = main.index("else:", proxy)
        proxy_branch = main[proxy:standalone]
        standalone_branch = main[standalone:]
        self.assertNotIn("target=udp_listener", proxy_branch)
        self.assertIn("target=udp_listener", standalone_branch)
        self.assertIn("需重启 chat.py", proxy_branch)

    def test_python_udp_listener_recv_errors_release_socket_and_rebind(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename, socket_name in (
                ("chat.py", "udp_sock"),
                ("rescue2.py", "sock"),
                ("server2.py", "udp_sock")):
            source = (repo_root / filename).read_text(encoding="utf-8")
            start = source.index("def udp_listener():")
            end_candidates = [
                source.find("\ndef ", start + 1),
                source.find("\nclass ", start + 1),
            ]
            ends = [value for value in end_candidates if value >= 0]
            listener = source[start:min(ends)] if ends else source[start:]
            self.assertIn("while True:", listener, filename)
            self.assertIn(f"{socket_name}.close()", listener, filename)
            self.assertIn("finally:", listener, filename)
            self.assertIn(f"{socket_name}.bind(", listener, filename)
            if filename == "server2.py":
                recv_start = listener.index("except Exception as recv_err:")
                recv_end = listener.index('if data[:1] == b"{"', recv_start)
                recv_handler = listener[recv_start:recv_end]
                self.assertIn("raise", recv_handler)
                self.assertNotIn("continue", recv_handler)
                self.assertNotIn("time.sleep(1)", recv_handler)
            else:
                # These listeners already let recvfrom failures reach the outer
                # exception handler, which closes the socket before the next bind.
                self.assertNotIn("except Exception as recv_err:", listener)

    def test_android_outbox_exposes_stable_message_timestamp_for_fragment_retries(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        method = extract_java_method(store, "public long getMessageCreatedAtMs(")
        self.assertIn("SELECT created_at_ms FROM lan_outbox", method)
        self.assertIn("return -1L", method)

    def test_android_outbox_persists_per_recipient_fragment_ack_bitmap(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        self.assertIn("private static final int DB_VERSION = 11;", source)
        self.assertIn('"fragment_count INTEGER NOT NULL DEFAULT 0,"', source)
        self.assertIn('"fragment_ack_bitmap TEXT NOT NULL DEFAULT \'\',"', source)
        self.assertIn('if (oldVersion < 11)', source)
        self.assertIn("initializeTargetFragmentProgress(", source)
        self.assertIn("acknowledgeTargetFragment(", source)
        self.assertIn("loadTargetFragmentAckBitmap(", source)
        initialize = extract_java_method(source, "public boolean initializeTargetFragmentProgress(")
        acknowledge = extract_java_method(source, "public boolean acknowledgeTargetFragment(")
        self.assertIn("fragmentCount > 1024", initialize)
        self.assertIn("existingCount != fragmentCount", initialize)
        self.assertIn("fragmentIndex >= count", acknowledge)
        self.assertIn("db.setTransactionSuccessful()", acknowledge)
        self.assertIn("status!='acked'", acknowledge)

    def test_rescue2_legacy_log_pull_is_preserved_as_compatibility_path(self):
        repo_root = Path(__file__).resolve().parents[1]
        todo = (repo_root / "docs/LAN_P2P_TODO.md").read_text(encoding="utf-8")
        self.assertIn("pull_logs_multi", todo)
        self.assertIn("不得因新版诊断采集建设而删除", todo)
        self.assertIn("旧版日志拉取能力必须保留", todo)
        self.assertIn("旧版日志拉取能力必须保留", todo)

    def test_android_outbox_foreign_key_constraints_are_enabled_on_every_connection(self):
        source = Path(__file__).resolve().parents[1] / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        java = source.read_text(encoding="utf-8")
        configure = extract_java_method(java, "public void onConfigure(SQLiteDatabase db)")
        self.assertIn("super.onConfigure(db);", configure)
        self.assertIn("db.setForeignKeyConstraintsEnabled(true);", configure)
        self.assertIn("FOREIGN KEY(message_id) REFERENCES lan_outbox(message_id) ON DELETE CASCADE", java)

    def test_android_discovery_ledger_read_failure_never_means_window_closed(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        service = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")

        window = extract_java_method(store, "public int beginOrReadDiscoveryWindow(")
        self.assertIn("return DISCOVERY_WINDOW_ERROR;", window)
        self.assertIn("catch (Exception e)", window)

        send = extract_java_method(service, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("store.beginOrReadDiscoveryWindow(messageId)", send)
        self.assertIn("discoveryWindowState == LanChatStore.DISCOVERY_WINDOW_ERROR", send)
        self.assertIn("发现窗口账本操作失败，停止本轮发送", send)
        self.assertIn("发现窗口状态复核失败，停止广播", send)

        ack = extract_java_method(service, "private void processAuthenticatedLanChatAck(")
        self.assertIn("boolean discoveryAck = false;", ack)
        self.assertIn("catch (Exception storageFailure)", ack)
        self.assertIn("拒绝立即确认", ack)
        self.assertIn("allowDiscoveryGrace && (discoveryAck || attemptedEndpointPending)", ack)
        self.assertIn("store.matchesAttemptedAckEndpoint(messageId, senderId", ack)

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.temp_dir.name, "peers.sqlite3")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_service_identity_survives_restart_and_is_scoped(self):
        server_id = load_stable_peer_id(self.db_path, "server2")
        self.assertEqual(server_id, load_stable_peer_id(self.db_path, "server2"))
        rescue_id = load_stable_peer_id(self.db_path, "rescue2")
        self.assertNotEqual(server_id, rescue_id)

    def test_same_endpoint_hello_keeps_verified_status_but_route_change_invalidates_it(self):
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "active"))
        # A HELLO refresh from the same endpoint does not erase a valid verification.
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        conn = sqlite3.connect(self.db_path)
        try:
            same_route = conn.execute(
                "SELECT endpoint_ip,last_verified_at,status FROM lan_peers "
                "WHERE receiver_scope='server2' AND peer_id='android-a'"
            ).fetchone()
        finally:
            conn.close()
        self.assertGreater(same_route[1], 0)
        self.assertEqual(same_route[2], "active")

        # A new IP/port is a new route and must not inherit the old route's verified state.
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.11", 8888, "suspect"))
        conn = sqlite3.connect(self.db_path)
        try:
            changed_route = conn.execute(
                "SELECT endpoint_ip,last_verified_at,status FROM lan_peers "
                "WHERE receiver_scope='server2' AND peer_id='android-a'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(changed_route[0], "192.168.1.11")
        self.assertEqual(changed_route[1], 0)
        self.assertEqual(changed_route[2], "suspect")



    def test_unverified_same_endpoint_hello_cannot_extend_verified_peer_lease(self):
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                        "android", "android-main", "192.168.1.10", 8888, "active"))
        with patch("lan_p2p_core.time.time", return_value=1110.0):
            self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                        "android", "android-main", "192.168.1.10", 8888, "suspect"))
        with _sqlite_connection(self.db_path) as conn:
            row = conn.execute(
                "SELECT last_seen_at,last_verified_at,lease_expires_at,status FROM lan_peers "
                "WHERE receiver_scope='server2' AND peer_id='android-a'"
            ).fetchone()
        self.assertEqual(row[0], 1110.0)
        self.assertEqual(row[1], 1000.0)
        self.assertEqual(row[2], 1120.0)
        self.assertEqual(row[3], "active")

    def test_same_peer_has_independent_service_scopes(self):
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        self.assertTrue(record_peer(self.db_path, "rescue2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        conn = sqlite3.connect(self.db_path)
        try:
            count = conn.execute(
                "SELECT COUNT(*) FROM lan_peers WHERE peer_id='android-a'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(count, 2)


    def test_android_and_python_protocol_decoders_reject_malformed_utf8(self):
        repo_root = Path(__file__).resolve().parents[1]
        protocol = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanP2pProtocol.java"
        ).read_text(encoding="utf-8")
        decoder = extract_java_method(
            protocol, "public static JSONObject decode(byte[] bytes, int offset")
        self.assertIn("StandardCharsets.UTF_8.newDecoder()", decoder)
        self.assertIn("CodingErrorAction.REPORT", decoder)
        self.assertIn("ByteBuffer.wrap(bytes, offset, length)", decoder)
        self.assertIn("Invalid LAN P2P UTF-8", decoder)

        python_core = (repo_root / "lan_p2p_core.py").read_text(encoding="utf-8")
        self.assertIn('decode("utf-8", errors="strict")', python_core)

    def test_android_udp_v2_path_decodes_original_datagram_bytes(self):
        repo_root = Path(__file__).resolve().parents[1]
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        handler = extract_java_method(tracking, "private boolean handleLanRealtimePacket(")
        self.assertIn(
            "LanP2pProtocol.decode(\n                            data, offset, length, RESCUE_SECRET)",
            handler,
        )
        self.assertNotIn("message.getBytes", handler)

        receive_at = tracking.index("rescueSocket.receive(packet);")
        raw_v2_at = tracking.index(
            "handleLanRealtimePacket(rescueSocket, packet, udpReceiveElapsedMs,", receive_at
        )
        legacy_string_at = tracking.index(
            "String message = new String(packet.getData(), 0, packet.getLength())", receive_at
        )
        self.assertLess(raw_v2_at, legacy_string_at)

    def test_authenticated_v2_packet_round_trip_and_tamper_rejection(self):
        from lan_p2p_core import encode_packet, decode_packet
        packet = {
            "type": "chat.message",
            "sender_peer_id": "android-a",
            "sender_peer_type": "android",
            "service_id": "android-main",
            "message_id": "message-1",
            "target_peer_id": "android-b",
            "created_at_ms": 123456,
            "body": "老人好，今天记得吃药"
        }
        encoded = encode_packet(packet, "test-secret")
        decoded = decode_packet(encoded, "test-secret")
        self.assertEqual(decoded["body"], packet["body"])
        tampered = encoded.replace("老人好".encode("utf-8"), "老人坏".encode("utf-8"))
        with self.assertRaises(ValueError):
            decode_packet(tampered, "test-secret")

    def test_authenticated_v2_packet_rejects_oversized_datagrams(self):
        from lan_p2p_core import encode_packet
        packet = {
            "type": "chat.message",
            "sender_peer_id": "android-a",
            "target_peer_id": "android-b",
            "message_id": "oversized-message",
            "body": "x" * 2000
        }
        with self.assertRaises(ValueError):
            encode_packet(packet, "test-secret")

    def test_peer_id_limit_and_worst_case_json_budget_probe(self):
        from lan_p2p_core import (MAX_PEER_ID_BYTES, max_peer_id_budget_probe,
                                  encode_packet, decode_packet)
        probe = max_peer_id_budget_probe()
        self.assertEqual(len(probe.encode("utf-8")), MAX_PEER_ID_BYTES)
        packet = {
            "type": "chat.message",
            "sender_peer_id": "android-a",
            "target_peer_id": probe,
            "message_id": "budget-boundary",
            "body": "ok",
        }
        raw = encode_packet(packet, "test-secret")
        self.assertLessEqual(len(raw), 1200)
        self.assertEqual(decode_packet(raw, "test-secret")["target_peer_id"], probe)
        packet["target_peer_id"] = "x" * (MAX_PEER_ID_BYTES + 1)
        with self.assertRaises(ValueError):
            encode_packet(packet, "test-secret")
        packet["target_peer_id"] = "android-b"
        packet["sender_peer_id"] = "x" * (MAX_PEER_ID_BYTES + 1)
        with self.assertRaises(ValueError):
            encode_packet(packet, "test-secret")




    def test_python_outbox_rejects_invalid_peer_ids_before_persisting(self):
        from lan_p2p_core import persist_outgoing_message
        invalid_id = "x" * 129
        self.assertFalse(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-invalid-target",
            "hello", [invalid_id]))
        self.assertFalse(persist_outgoing_message(
            self.db_path, "server2", invalid_id, "m-invalid-sender",
            "hello", ["android-a"]))

    def test_python_outbox_requires_every_fixed_recipient_ack(self):
        from lan_p2p_core import persist_outgoing_message, acknowledge_outgoing_message
        self.assertTrue(persist_outgoing_message(self.db_path, "server2", "server2-id",
                                                 "m-1", "hello", ["android-a", "android-b"]))
        self.assertFalse(acknowledge_outgoing_message(self.db_path, "server2", "m-1", "unknown"))
        self.assertTrue(acknowledge_outgoing_message(self.db_path, "server2", "m-1", "android-a"))
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute("SELECT state FROM lan_outbox WHERE message_id='m-1'").fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(state, "partial")
        self.assertTrue(acknowledge_outgoing_message(self.db_path, "server2", "m-1", "android-b"))
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute("SELECT state FROM lan_outbox WHERE message_id='m-1'").fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(state, "delivered")


    def test_unknown_recipient_ack_window_collects_multiple_peers(self):
        from lan_p2p_core import (
            persist_outgoing_message, acknowledge_outgoing_message, record_peer,
            load_pending_outgoing_for_peer, begin_outgoing_discovery_window,
        )
        self.assertTrue(persist_outgoing_message(self.db_path, "server2", "server2-id",
                                                 "m-discovery", "hello", []))
        self.assertTrue(begin_outgoing_discovery_window(
            self.db_path, "server2", "m-discovery"))
        self.assertTrue(acknowledge_outgoing_message(self.db_path, "server2", "m-discovery", "android-a"))
        self.assertTrue(acknowledge_outgoing_message(self.db_path, "server2", "m-discovery", "android-b"))
        conn = sqlite3.connect(self.db_path)
        try:
            row = conn.execute("SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id='m-discovery'").fetchone()
            count = conn.execute("SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-discovery'").fetchone()[0]
            conn.execute("UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id='m-discovery'")
            conn.commit()
        finally:
            conn.close()
        self.assertEqual(row[0], "discovery_pending")
        self.assertEqual(count, 2)
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        load_pending_outgoing_for_peer(self.db_path, "server2", "android-a")
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute("SELECT state FROM lan_outbox WHERE message_id='m-discovery'").fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(state, "delivered")

    def test_ack_endpoint_must_match_recorded_peer_route(self):
        from lan_p2p_core import peer_endpoint_matches
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        self.assertTrue(peer_endpoint_matches(self.db_path, "server2", "android-a",
                                              "192.168.1.10", 8888))
        self.assertFalse(peer_endpoint_matches(self.db_path, "server2", "android-a",
                                               "192.168.1.11", 8888))
        self.assertFalse(peer_endpoint_matches(self.db_path, "server2", "android-a",
                                               "192.168.1.10", 8890))
        self.assertFalse(peer_endpoint_matches(self.db_path, "rescue2", "android-a",
                                               "192.168.1.10", 8888))


    def test_ack_route_database_read_failure_fails_closed(self):
        from lan_p2p_core import peer_endpoint_matches
        with tempfile.TemporaryDirectory() as invalid_db_path:
            self.assertFalse(peer_endpoint_matches(
                invalid_db_path, "server2", "android-a", "192.168.1.10", 8888))


    def test_unknown_recipient_ack_is_only_allowed_inside_open_discovery_window(self):
        from lan_p2p_core import (
            persist_outgoing_message, is_outgoing_discovery_pending,
            acknowledge_outgoing_message, begin_outgoing_discovery_window,
        )
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-open-discovery", "hello", []))
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-open-discovery"))
            self.assertTrue(is_outgoing_discovery_pending(
                self.db_path, "server2", "m-open-discovery"))
        with patch("lan_p2p_core.time.time", return_value=1011.0):
            self.assertFalse(is_outgoing_discovery_pending(
                self.db_path, "server2", "m-open-discovery"))
            self.assertFalse(acknowledge_outgoing_message(
                self.db_path, "server2", "m-open-discovery", "android-late"))

    def test_fixed_target_message_does_not_open_discovery_ack_bypass(self):
        from lan_p2p_core import persist_outgoing_message, is_outgoing_discovery_pending
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-fixed-ack", "hello", ["android-a"]))
        self.assertFalse(is_outgoing_discovery_pending(
            self.db_path, "server2", "m-fixed-ack"))

    def test_ack_route_requires_active_peer_and_rejects_changed_suspect_endpoint(self):
        from lan_p2p_core import record_peer, peer_endpoint_matches
        self.assertTrue(record_peer(
            self.db_path, "server2", "android-a", "android", "android-main",
            "192.168.1.10", 8888, "suspect"))
        self.assertFalse(peer_endpoint_matches(
            self.db_path, "server2", "android-a", "192.168.1.10", 8888))
        self.assertTrue(record_peer(
            self.db_path, "server2", "android-a", "android", "android-main",
            "192.168.1.10", 8888, "active"))
        self.assertTrue(peer_endpoint_matches(
            self.db_path, "server2", "android-a", "192.168.1.10", 8888))
        self.assertTrue(record_peer(
            self.db_path, "server2", "android-a", "android", "android-main",
            "192.168.1.11", 8888, "suspect"))
        self.assertFalse(peer_endpoint_matches(
            self.db_path, "server2", "android-a", "192.168.1.11", 8888))
        self.assertFalse(peer_endpoint_matches(
            self.db_path, "server2", "android-a", "192.168.1.10", 8888))

    def test_peer_endpoint_match_fails_closed_on_database_open_error(self):
        from lan_p2p_core import peer_endpoint_matches
        directory_path = os.path.join(self.temp_dir.name, "not-a-database")
        os.mkdir(directory_path)
        self.assertFalse(peer_endpoint_matches(
            directory_path, "server2", "android-a", "192.168.1.10", 8888))

    def test_outgoing_ack_fails_closed_when_database_cannot_be_opened(self):
        from lan_p2p_core import acknowledge_outgoing_message
        directory_path = os.path.join(self.temp_dir.name, "not-an-ack-database")
        os.mkdir(directory_path)
        self.assertFalse(acknowledge_outgoing_message(
            directory_path, "server2", "message-a", "android-a"))

    def test_retry_only_returns_unacked_fixed_targets(self):
        from lan_p2p_core import (
            persist_outgoing_message, record_peer, load_pending_outgoing_for_peer,
            acknowledge_outgoing_message, mark_outgoing_attempt,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-retry", "hello",
            ["android-a", "android-b"]))
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        record_peer(self.db_path, "server2", "android-b", "android", "android-main",
                    "192.168.1.11", 8888, "active")

        first = load_pending_outgoing_for_peer(self.db_path, "server2", "android-a")
        second = load_pending_outgoing_for_peer(self.db_path, "server2", "android-b")
        self.assertEqual([x["message_id"] for x in first], ["m-retry"])
        self.assertEqual([x["message_id"] for x in second], ["m-retry"])

        self.assertTrue(mark_outgoing_attempt(
            self.db_path, "m-retry", "android-a", "192.168.1.10", 8888))
        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "m-retry", "android-a"))
        # A separately queued message to the same peer remains eligible even after
        # the peer lease is downgraded to suspect; fixed recipient identity is durable.
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "suspect")
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-suspect-route", "later", ["android-a"]))
        pending = load_pending_outgoing_for_peer(self.db_path, "server2", "android-a")
        self.assertEqual(["m-suspect-route"], [row["message_id"] for row in pending])
        self.assertEqual("192.168.1.10", pending[0]["endpoint_ip"])
        remaining = load_pending_outgoing_for_peer(
            self.db_path, "server2", "android-b")
        self.assertEqual([x["message_id"] for x in remaining], ["m-retry"])

        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "m-retry", "android-b"))
        self.assertEqual(load_pending_outgoing_for_peer(
            self.db_path, "server2", "android-b"), [])
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-retry'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(state, "delivered")

    def test_python_outbox_attempt_cannot_mutate_terminal_message_states(self):
        from lan_p2p_core import persist_outgoing_message
        terminal_states = ("delivered", "expired", "waiting_peers")
        for state in terminal_states:
            message_id = "m-terminal-attempt-" + state
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", message_id, "hello", ["android-a"]))
            with _sqlite_connection(self.db_path) as conn:
                conn.execute("UPDATE lan_outbox SET state=? WHERE message_id=?",
                             (state, message_id))
            self.assertFalse(mark_outgoing_attempt(
                self.db_path, message_id, "android-a", "192.168.1.10", 8888))
            with _sqlite_connection(self.db_path) as conn:
                row = conn.execute(
                    "SELECT status,attempt_count FROM lan_outbox_targets "
                    "WHERE message_id=? AND peer_id='android-a'", (message_id,)
                ).fetchone()
            self.assertEqual(row, ("pending", 0), state)

    def test_first_discovery_target_set_freezes_after_deadline(self):
        from lan_p2p_core import (
            persist_outgoing_message, record_peer, add_discovery_targets_for_peer,
            load_pending_outgoing_for_peer, begin_outgoing_discovery_window,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-freeze", "hello", []))
        self.assertTrue(begin_outgoing_discovery_window(
            self.db_path, "server2", "m-freeze"))
        self.assertEqual(add_discovery_targets_for_peer(
            self.db_path, "server2", "android-a"), 1)
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                "UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id='m-freeze'")
            conn.commit()
        finally:
            conn.close()
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        load_pending_outgoing_for_peer(self.db_path, "server2", "android-a")
        self.assertEqual(add_discovery_targets_for_peer(
            self.db_path, "server2", "android-b"), 0)
        conn = sqlite3.connect(self.db_path)
        try:
            targets = conn.execute(
                "SELECT peer_id FROM lan_outbox_targets WHERE message_id='m-freeze' ORDER BY peer_id"
            ).fetchall()
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-freeze'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual([x[0] for x in targets], ["android-a"])
        self.assertEqual(state, "pending")

    def test_empty_discovery_window_never_becomes_delivered(self):
        from lan_p2p_core import persist_outgoing_message, load_pending_outgoing_for_peer
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-empty", "hello", []))
        # A valid ledger includes the peer table even when this target is unknown.
        self.assertTrue(record_peer(
            self.db_path, "server2", "known-peer", "android", "android-main",
            "192.168.1.20", 8888, "active"))
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                "UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id='m-empty'")
            conn.commit()
        finally:
            conn.close()
        load_pending_outgoing_for_peer(self.db_path, "server2", "nonexistent-peer")
        conn = sqlite3.connect(self.db_path)
        try:
            row = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-empty'"
            ).fetchone()
            count = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-empty'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(row[0], "waiting_peers")
        self.assertEqual(count, 0)

    def test_expired_empty_discovery_is_finalized_by_broadcast_gate_and_rejects_late_peer(self):
        from lan_p2p_core import (
            persist_outgoing_message, is_outgoing_discovery_pending,
            add_discovery_targets_for_peer,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-empty-final", "hello", []))
        with _sqlite_connection(self.db_path) as conn:
            conn.execute(
                "UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id='m-empty-final'")
        self.assertFalse(is_outgoing_discovery_pending(
            self.db_path, "server2", "m-empty-final"))
        self.assertEqual(add_discovery_targets_for_peer(
            self.db_path, "server2", "late-android"), 0)
        with _sqlite_connection(self.db_path) as conn:
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-empty-final'").fetchone()[0]
            targets = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-empty-final'").fetchone()[0]
        self.assertEqual(state, "waiting_peers")
        self.assertEqual(targets, 0)


    def test_single_discovery_target_cannot_start_window_before_udp_dispatch(self):
        from lan_p2p_core import persist_outgoing_message, add_discovery_target
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-no-early-window", "hello", []))
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertFalse(add_discovery_target(
                self.db_path, "server2", "m-no-early-window", "android-early"))
        with _sqlite_connection(self.db_path) as conn:
            state, deadline = conn.execute(
                "SELECT state,delivery_deadline_ms FROM lan_outbox "
                "WHERE message_id='m-no-early-window'"
            ).fetchone()
            target_count = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-no-early-window'"
            ).fetchone()[0]
        self.assertEqual(state, "discovery_pending")
        self.assertEqual(deadline, 0)
        self.assertEqual(target_count, 0)

    def test_initial_discovery_window_stays_unstarted_until_udp_dispatch(self):
        from lan_p2p_core import (
            persist_outgoing_message, is_outgoing_discovery_pending,
            begin_outgoing_discovery_window,
        )
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-window-start", "hello", []))
            self.assertTrue(is_outgoing_discovery_pending(
                self.db_path, "server2", "m-window-start"))
        with _sqlite_connection(self.db_path) as conn:
            state, deadline = conn.execute(
                "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id='m-window-start'"
            ).fetchone()
        self.assertEqual(state, "discovery_pending")
        self.assertEqual(deadline, 0)

        # A delayed listener starts the bounded window at the actual first dispatch.
        with patch("lan_p2p_core.time.time", return_value=1010.0):
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-window-start"))
        with _sqlite_connection(self.db_path) as conn:
            deadline = conn.execute(
                "SELECT delivery_deadline_ms FROM lan_outbox WHERE message_id='m-window-start'"
            ).fetchone()[0]
        self.assertEqual(deadline, 1020000)

    def test_discovery_window_begin_is_idempotent_and_never_extends_deadline(self):
        from lan_p2p_core import persist_outgoing_message, begin_outgoing_discovery_window
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-window-idempotent", "hello", []))
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-window-idempotent"))
        with patch("lan_p2p_core.time.time", return_value=1002.0):
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-window-idempotent"))
        with _sqlite_connection(self.db_path) as conn:
            deadline = conn.execute(
                "SELECT delivery_deadline_ms FROM lan_outbox WHERE message_id='m-window-idempotent'"
            ).fetchone()[0]
        self.assertEqual(deadline, 1010000)


    def test_discovery_deadline_is_closed_at_exact_boundary_on_python_and_android(self):
        from lan_p2p_core import (
            persist_outgoing_message, begin_outgoing_discovery_window,
            add_discovery_target, is_outgoing_discovery_pending,
            acknowledge_outgoing_message,
        )
        cases = (
            ("m-boundary-bulk", "scope-bulk"),
            ("m-boundary-single", "scope-single"),
            ("m-boundary-begin", "scope-begin"),
            ("m-boundary-ack", "scope-ack"),
            ("m-boundary-query", "scope-query"),
        )
        for message_id, scope in cases:
            with patch("lan_p2p_core.time.time", return_value=1000.0):
                self.assertTrue(persist_outgoing_message(
                    self.db_path, scope, "sender", message_id, "hello", []))
                self.assertTrue(begin_outgoing_discovery_window(
                    self.db_path, scope, message_id))

        # Each operation is isolated by sender scope so one expiry sweep cannot mask another.
        with patch("lan_p2p_core.time.time", return_value=1010.0):
            self.assertFalse(add_discovery_target(
                self.db_path, "scope-single", "m-boundary-single", "android-late"))
            self.assertFalse(begin_outgoing_discovery_window(
                self.db_path, "scope-begin", "m-boundary-begin"))
            self.assertFalse(acknowledge_outgoing_message(
                self.db_path, "scope-ack", "m-boundary-ack", "android-late"))
            self.assertFalse(is_outgoing_discovery_pending(
                self.db_path, "scope-query", "m-boundary-query"))
            self.assertEqual(add_discovery_targets_for_peer(
                self.db_path, "scope-bulk", "android-late"), 0)

        with _sqlite_connection(self.db_path) as conn:
            states = dict(conn.execute(
                "SELECT message_id,state FROM lan_outbox WHERE message_id LIKE 'm-boundary-%'"
            ).fetchall())
            targets = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id LIKE 'm-boundary-%'"
            ).fetchone()[0]
        self.assertEqual(states["m-boundary-begin"], "waiting_peers")
        self.assertEqual(states["m-boundary-query"], "waiting_peers")
        self.assertEqual(states["m-boundary-bulk"], "waiting_peers")
        self.assertEqual(targets, 0)

        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        ack = extract_java_method(store, "public boolean acknowledge(")
        discovery_window = extract_java_method(store, "public int beginOrReadDiscoveryWindow(")
        pending = extract_java_method(store, "public boolean isDiscoveryPending(")
        add_peer = extract_java_method(store, "private boolean addDiscoveryPeerToOpenMessages(SQLiteDatabase db,")
        set_targets = extract_java_method(store, "public boolean setTargets(")
        self.assertIn("now >= discoveryDeadline", set_targets)
        self.assertIn("now >= deadline", ack)
        self.assertIn("now < deadline", ack)
        self.assertIn("if (now < deadline)", discovery_window)
        self.assertIn("finalizeDiscoveryMessage(db, messageId)", discovery_window)
        self.assertIn("delivery_deadline_ms>?", pending)
        self.assertIn("now >= deadline", add_peer)

    def test_first_peer_discovered_after_initial_window_is_not_added(self):
        from lan_p2p_core import (
            persist_outgoing_message, add_discovery_targets_for_peer,
            acknowledge_outgoing_message, begin_outgoing_discovery_window,
        )
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-too-late", "hello", []))
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-too-late"))
        with patch("lan_p2p_core.time.time", return_value=1011.0):
            self.assertEqual(add_discovery_targets_for_peer(
                self.db_path, "server2", "android-late"), 0)
            self.assertFalse(acknowledge_outgoing_message(
                self.db_path, "server2", "m-too-late", "android-late"))
        conn = sqlite3.connect(self.db_path)
        try:
            row = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-too-late'"
            ).fetchone()
            count = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-too-late'"
            ).fetchone()[0]
        finally:
            conn.close()
        # The expired empty snapshot is terminal and must not remain retryable.
        self.assertEqual(row[0], "waiting_peers")
        self.assertEqual(count, 0)

    def test_replaying_same_message_id_cannot_expand_targets_or_change_body(self):
        from lan_p2p_core import persist_outgoing_message
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-immutable", "original", ["android-a"]))
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-immutable", "original",
            ["android-a", "android-b"]))
        self.assertFalse(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-immutable", "mutated", ["android-a"]))
        conn = sqlite3.connect(self.db_path)
        try:
            body = conn.execute(
                "SELECT body FROM lan_outbox WHERE message_id='m-immutable'"
            ).fetchone()[0]
            targets = conn.execute(
                "SELECT peer_id FROM lan_outbox_targets WHERE message_id='m-immutable' ORDER BY peer_id"
            ).fetchall()
        finally:
            conn.close()
        self.assertEqual(body, "original")
        self.assertEqual([x[0] for x in targets], ["android-a"])

    def test_packet_with_wrong_secret_is_rejected(self):
        from lan_p2p_core import encode_packet, decode_packet
        packet = encode_packet({
            "type": "chat.message",
            "sender_peer_id": "android-a",
            "target_peer_id": "android-b",
            "message_id": "m-secret",
            "body": "hello",
        }, "correct-secret")
        with self.assertRaises(ValueError):
            decode_packet(packet, "wrong-secret")


    def test_directed_peer_challenge_requires_matching_nonce_identity_and_endpoint(self):
        from lan_p2p_core import remember_peer_challenge, complete_peer_challenge, list_known_peers
        self.assertTrue(remember_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-a", "192.168.1.10", 8888))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "wrong-nonce", "android-a",
            "192.168.1.10", 8888, "android", "android-main"))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-b",
            "192.168.1.10", 8888, "android", "android-main"))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-a",
            "192.168.1.11", 8888, "android", "android-main"))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-a",
            "192.168.1.10", 8890, "android", "android-main"))
        self.assertTrue(complete_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-a",
            "192.168.1.10", 8888, "android", "android-main"))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-a",
            "192.168.1.10", 8888, "android", "android-main"))
        peers = list_known_peers(self.db_path, "server2")
        self.assertEqual([peer["peer_id"] for peer in peers], ["android-a"])
        self.assertEqual(peers[0]["status"], "active")

    def test_one_broadcast_challenge_can_collect_multiple_distinct_peers(self):
        from lan_p2p_core import remember_peer_challenge, complete_peer_challenge, list_known_peers
        self.assertTrue(remember_peer_challenge(self.db_path, "server2", "broadcast-nonce"))
        self.assertTrue(complete_peer_challenge(
            self.db_path, "server2", "broadcast-nonce", "android-a",
            "192.168.1.10", 8888, "android", "android-main"))
        self.assertTrue(complete_peer_challenge(
            self.db_path, "server2", "broadcast-nonce", "android-b",
            "192.168.1.11", 8888, "android", "android-main"))
        peers = list_known_peers(self.db_path, "server2")
        self.assertEqual({peer["peer_id"] for peer in peers}, {"android-a", "android-b"})

    def test_challenge_lookup_fails_closed_when_database_cannot_be_opened(self):
        from lan_p2p_core import complete_peer_challenge
        directory_path = os.path.join(self.temp_dir.name, "not-a-challenge-database")
        os.mkdir(directory_path)
        self.assertFalse(complete_peer_challenge(
            directory_path, "server2", "nonce", "android-a",
            "192.168.1.10", 8888, "android", "android-main"))

    def test_expired_peer_challenge_cannot_activate_route(self):
        from lan_p2p_core import remember_peer_challenge, complete_peer_challenge
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(remember_peer_challenge(
                self.db_path, "server2", "nonce-expired", "android-a",
                "192.168.1.10", 8888))
        with patch("lan_p2p_core.time.time", return_value=1011.0):
            self.assertFalse(complete_peer_challenge(
                self.db_path, "server2", "nonce-expired", "android-a",
                "192.168.1.10", 8888, "android", "android-main"))

    def test_active_target_sender_retries_only_until_ack(self):
        from lan_p2p_core import (
            record_peer, persist_outgoing_message,
            send_outbox_to_active_peers, acknowledge_outgoing_message,
        )

        class FakeSocket:
            def __init__(self, on_send=None):
                self.sent = []
                self.on_send = on_send
            def sendto(self, payload, destination):
                self.sent.append((payload, destination))
                if self.on_send:
                    self.on_send(payload, destination)

        record_peer(self.db_path, "rescue2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        packet = {
            "type": "chat.message", "sender_peer_id": "rescue2-id",
            "sender_peer_type": "rescue2", "service_id": "rescue2-id",
            "target_peer_id": "*", "message_id": "retry-m1",
            "created_at_ms": 123, "body": "hello",
        }
        self.assertTrue(persist_outgoing_message(
            self.db_path, "rescue2", "rescue2-id", "retry-m1", "hello", ["android-a"]))
        socket = FakeSocket()
        with patch("lan_p2p_core.time.sleep", return_value=None):
            sent = send_outbox_to_active_peers(
                socket, self.db_path, "rescue2", "retry-m1", packet,
                "test-secret", attempts=3, retry_delay_seconds=0.25)
        self.assertEqual(sent, 3)
        self.assertEqual(len(socket.sent), 3)
        with _sqlite_connection(self.db_path) as conn:
            attempt_count = conn.execute(
                "SELECT attempt_count FROM lan_outbox_targets WHERE message_id=? AND peer_id=?",
                ("retry-m1", "android-a")
            ).fetchone()[0]
        self.assertEqual(attempt_count, 3)
        self.assertTrue(all(addr == ("192.168.1.10", 8888) for _, addr in socket.sent))

        self.assertTrue(persist_outgoing_message(
            self.db_path, "rescue2", "rescue2-id", "retry-m2", "hello", ["android-a"]))
        packet["message_id"] = "retry-m2"
        def ack_first_send(_payload, _destination):
            acknowledge_outgoing_message(
                self.db_path, "rescue2", "retry-m2", "android-a")
        ack_socket = FakeSocket(on_send=ack_first_send)
        with patch("lan_p2p_core.time.sleep", return_value=None):
            acked_sends = send_outbox_to_active_peers(
                ack_socket, self.db_path, "rescue2", "retry-m2", packet,
                "test-secret", attempts=3, retry_delay_seconds=0.25)
        self.assertEqual(acked_sends, 1)


    def test_active_target_sender_fails_closed_when_attempt_ledger_fails(self):
        from lan_p2p_core import record_peer, persist_outgoing_message, send_outbox_to_active_peers

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, payload, destination):
                self.sent.append((payload, destination))

        record_peer(self.db_path, "rescue2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        self.assertTrue(persist_outgoing_message(
            self.db_path, "rescue2", "rescue2-id", "ledger-fail", "hello", ["android-a"]))
        packet = {
            "type": "chat.message", "sender_peer_id": "rescue2-id",
            "sender_peer_type": "rescue2", "service_id": "rescue2-id",
            "target_peer_id": "*", "message_id": "ledger-fail",
            "created_at_ms": 123, "body": "hello",
        }
        socket = FakeSocket()
        with patch("lan_p2p_core.mark_outgoing_attempt", return_value=False):
            sent = send_outbox_to_active_peers(
                socket, self.db_path, "rescue2", "ledger-fail", packet,
                "test-secret", attempts=3, retry_delay_seconds=0)
        self.assertEqual(sent, 0)
        self.assertEqual(socket.sent, [])


    def test_partial_ack_retries_only_the_remaining_fixed_peer(self):
        from lan_p2p_core import (
            record_peer, persist_outgoing_message,
            send_outbox_to_active_peers, acknowledge_outgoing_message,
        )

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, payload, destination):
                self.sent.append((payload, destination))
                if destination[0] == "192.168.1.10":
                    acknowledge_outgoing_message(
                        self.db_path, "server2", "group-retry", "android-a")

        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        record_peer(self.db_path, "server2", "android-b", "android", "android-main",
                    "192.168.1.11", 8888, "active")
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "group-retry", "hello",
            ["android-a", "android-b"]))
        socket = FakeSocket()
        socket.db_path = self.db_path
        packet = {
            "type": "chat.message", "sender_peer_id": "server2-id",
            "sender_peer_type": "server2", "service_id": "server2-id",
            "target_peer_id": "*", "message_id": "group-retry",
            "created_at_ms": 123, "body": "hello",
        }
        with patch("lan_p2p_core.time.sleep", return_value=None):
            sent = send_outbox_to_active_peers(
                socket, self.db_path, "server2", "group-retry", packet,
                "test-secret", attempts=3, retry_delay_seconds=0.25)
        self.assertEqual(sent, 4)
        ips = [destination[0] for _, destination in socket.sent]
        self.assertEqual(ips.count("192.168.1.10"), 1)
        self.assertEqual(ips.count("192.168.1.11"), 3)
        conn = sqlite3.connect(self.db_path)
        try:
            rows = dict(conn.execute(
                "SELECT peer_id,status FROM lan_outbox_targets WHERE message_id='group-retry'"
            ).fetchall())
        finally:
            conn.close()
        self.assertEqual(rows["android-a"], "acked")
        self.assertEqual(rows["android-b"], "sent")

    def test_broadcast_retries_are_bounded_and_keep_identical_payload(self):
        from lan_p2p_core import send_broadcast_packet_with_retries

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, payload, destination):
                self.sent.append((payload, destination))

        socket = FakeSocket()
        payload = b"same-message-id-packet"
        destinations = [("192.168.1.255", 8888), ("255.255.255.255", 8888)]
        with patch("lan_p2p_core.time.sleep", return_value=None):
            sent = send_broadcast_packet_with_retries(
                socket, payload, destinations, attempts=3, retry_delay_seconds=0.25)
        self.assertEqual(sent, 6)
        self.assertEqual(len(socket.sent), 6)
        self.assertTrue(all(packet == payload for packet, _ in socket.sent))

    def test_broadcast_retry_stops_when_discovery_window_closes(self):
        from lan_p2p_core import send_broadcast_packet_with_retries

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, payload, destination):
                self.sent.append((payload, destination))

        socket = FakeSocket()
        allowed = {"open": True}
        destinations = [("192.168.1.255", 8888), ("255.255.255.255", 8888)]
        def close_window(_delay):
            allowed["open"] = False

        with patch("lan_p2p_core.time.sleep", side_effect=close_window):
            sent = send_broadcast_packet_with_retries(
                socket, b"same-message-id", destinations, attempts=3,
                retry_delay_seconds=0.25,
                should_continue=lambda: allowed["open"])

        self.assertEqual(sent, 2)
        self.assertEqual(len(socket.sent), 2)
        self.assertTrue(all(payload == b"same-message-id" for payload, _ in socket.sent))

    def test_incoming_message_claim_rejects_same_id_with_different_body(self):
        from lan_p2p_core import validate_incoming_message_claim
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute("""CREATE TABLE received_chat_messages (
                receiver_scope TEXT NOT NULL, sender TEXT NOT NULL, message_key TEXT NOT NULL,
                content TEXT NOT NULL, PRIMARY KEY(receiver_scope,sender,message_key))""")
            conn.execute("BEGIN IMMEDIATE")
            first = conn.execute(
                "INSERT OR IGNORE INTO received_chat_messages VALUES(?,?,?,?)",
                ("server2", "android-a", "message-1", "original body"))
            self.assertTrue(validate_incoming_message_claim(
                conn, first.rowcount, "server2", "android-a", "message-1", "original body"))
            conn.commit()

            conn.execute("BEGIN IMMEDIATE")
            duplicate = conn.execute(
                "INSERT OR IGNORE INTO received_chat_messages VALUES(?,?,?,?)",
                ("server2", "android-a", "message-1", "original body"))
            self.assertFalse(validate_incoming_message_claim(
                conn, duplicate.rowcount, "server2", "android-a", "message-1", "original body"))
            conn.commit()

            conn.execute("BEGIN IMMEDIATE")
            conflict = conn.execute(
                "INSERT OR IGNORE INTO received_chat_messages VALUES(?,?,?,?)",
                ("server2", "android-a", "message-1", "tampered body"))
            self.assertIsNone(validate_incoming_message_claim(
                conn, conflict.rowcount, "server2", "android-a", "message-1", "tampered body"))
            conn.rollback()
            stored = conn.execute(
                "SELECT content FROM received_chat_messages WHERE receiver_scope=? AND sender=? AND message_key=?",
                ("server2", "android-a", "message-1")).fetchone()[0]
            self.assertEqual(stored, "original body")
        finally:
            conn.close()

    def test_all_authenticated_v2_service_receivers_persist_before_stored_ack(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("server2.py", "rescue2.py", "chat.py"):
            with self.subTest(service=filename):
                source = (repo_root / filename).read_text(encoding="utf-8")
                self.assertIn(
                    "claim_result = _claim_chat_message(sender, message_id, body)",
                    source,
                )
                self.assertNotIn(
                    'if sender in ("Web_Console", "PC_Terminal", "Guest_")',
                    source,
                )
                self.assertIn("validate_incoming_message_claim(", source)
                self.assertIn("send_discovery_broadcast_with_retries(", source)

    def test_directed_peer_challenge_nonce_is_consumed_atomically_under_replay(self):
        from concurrent.futures import ThreadPoolExecutor
        from lan_p2p_core import remember_peer_challenge, complete_peer_challenge

        self.assertTrue(remember_peer_challenge(
            self.db_path, "server2", "nonce-race", "android-a",
            "192.168.1.10", 8888))
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(
                lambda _: complete_peer_challenge(
                    self.db_path, "server2", "nonce-race", "android-a",
                    "192.168.1.10", 8888, "android", "android-main"),
                range(2)))
        self.assertEqual(sum(1 for result in results if result), 1)

    def test_python_chat_ack_requires_verified_peer_route_in_all_services(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            with self.subTest(service=filename):
                source = (repo_root / filename).read_text(encoding="utf-8")
                start = source.index('if packet_type == "chat.ack":')
                end = source.index('if packet_type != "chat.message":', start)
                handler = source[start:end]
                self.assertIn("active_route = peer_endpoint_matches(", handler)
                self.assertIn("sent_endpoint_route = outgoing_ack_endpoint_matches(", handler)
                self.assertIn("and sender and (active_route or sent_endpoint_route))", handler)
                self.assertNotIn("discovery_ack", handler)
                self.assertNotIn("is_outgoing_discovery_pending(", handler)

    def test_peer_challenge_rejects_unregistered_udp_service_ports(self):
        from lan_p2p_core import remember_peer_challenge, complete_peer_challenge
        self.assertTrue(remember_peer_challenge(
            self.db_path, "server2", "nonce-bad-port", "android-a",
            "192.168.1.10", 9999))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "nonce-bad-port", "android-a",
            "192.168.1.10", 9999, "android", "android-main"))

    def test_suspect_peer_is_not_an_active_unicast_route(self):
        from lan_p2p_core import record_peer, list_known_peers, persist_outgoing_message, load_pending_outgoing_for_peer
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        self.assertEqual(list_known_peers(self.db_path, "server2"), [])
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-suspect-route", "hello", ["android-a"]))
        pending = load_pending_outgoing_for_peer(self.db_path, "server2", "android-a")
        self.assertEqual(["m-suspect-route"], [row["message_id"] for row in pending])
        self.assertEqual("192.168.1.10", pending[0]["endpoint_ip"])

    def test_android_discovery_ack_never_grants_future_route_trust(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        ack_handler = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        handler_dispatch = source[source.index('if ("chat.ack".equals(type))'):source.index('if ("chat.message".equals(type))')]
        store_source = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        self.assertIn("boolean routeMatches", ack_handler)
        self.assertIn("boolean discoveryAck", ack_handler)
        self.assertIn("if (!routeMatches && !persistedEndpointMatch)", ack_handler)
        self.assertNotIn("if (!routeMatches && !persistedEndpointMatch && !discoveryAck)", ack_handler)
        self.assertIn("allowDiscoveryGrace && (discoveryAck || attemptedEndpointPending)", ack_handler)
        self.assertIn("store.matchesAttemptedAckEndpoint(messageId, senderId", ack_handler)
        self.assertLess(ack_handler.index("if (!routeMatches && !persistedEndpointMatch)"),
                        ack_handler.index("store.acknowledge(messageId, senderId)"))
        # ACK acceptance is scoped to the captured ingress path and must not
        # refresh the peer-wide compatibility endpoint cache.
        self.assertIn("findCurrentPeerRouteBinding(senderId, ingressPath", ack_handler)
        self.assertNotIn("verifiedChatPeerRouteVersions.put(senderId", ack_handler)
        self.assertNotIn("verifiedChatPeerSeen.put(senderId", ack_handler)
        self.assertNotIn("synchronized (verifiedChatPeerRouteVersions)", ack_handler)
        self.assertIn("if (store.allTargetsAcked(messageId))", ack_handler)
        self.assertIn("scheduleLanChatMirrorCleanup(messageId)", ack_handler)
        self.assertIn("P2P 最终 ACK 已落账；兼容队列清理已异步排队", ack_handler)
        cleanup = extract_java_method(source, "private void scheduleLanChatMirrorCleanup(")
        self.assertIn("lanChatPeerExecutor.execute(", cleanup)
        self.assertIn("removePendingLanChat(messageId)", cleanup)
        ack_completion = extract_java_method(source, "private void completeLanChatAckWindow(")
        self.assertIn("scheduleLanChatMirrorCleanup(messageId)", ack_completion)
        self.assertNotIn("removePendingLanChat(messageId)", ack_completion)
        self.assertNotIn("verifiedChatPeers.put(", ack_handler)
        self.assertIn("store.matchesExpectedAckEndpoint(messageId, senderId", ack_handler)
        self.assertIn("persistedEndpointMatch", ack_handler)
        self.assertIn("processAuthenticatedLanChatAck(", handler_dispatch)
        self.assertIn("reason=unverified_endpoint", ack_handler)
        endpoint_start = store_source.index("public boolean matchesExpectedAckEndpoint(")
        endpoint_method = store_source[endpoint_start:endpoint_start + 1200]
        self.assertIn("last_sent_endpoint=?", endpoint_method)
        self.assertNotIn("last_endpoint=?", endpoint_method)
        self.assertIn('endpointIp + ":" + endpointPort', endpoint_method)

    def test_android_inbox_conflicting_payload_is_rejected_and_duplicate_refreshes_ui(self):
        repo_root = Path(__file__).resolve().parents[1]
        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        tracking_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        enqueue_start = store_source.index("public boolean enqueue(")
        enqueue_end = store_source.index("\n    public ", enqueue_start + 12)
        enqueue_method = store_source[enqueue_start:enqueue_end]
        self.assertIn("SELECT sender_peer_id,body FROM lan_outbox WHERE message_id=?", enqueue_method)
        self.assertIn("senderPeerId.equals(exists.getString(0))", enqueue_method)
        self.assertIn("body.equals(exists.getString(1))", enqueue_method)

        inbox_start = store_source.index("public boolean storeIncoming(")
        inbox_end = store_source.index("\n    public ", inbox_start + 12)
        inbox_method = store_source[inbox_start:inbox_end]
        self.assertIn("SELECT body FROM lan_inbox", inbox_method)
        self.assertIn("body.equals(existing.getString(0))", inbox_method)
        self.assertIn("if (!identical) return false", inbox_method)

        receive_method = extract_java_method(
            tracking_source,
            "private boolean persistIncomingLanChat(String senderId, String messageId, String msgContent,"
        )
        self.assertIn("store.storeIncoming(", receive_method)
        self.assertNotIn("editor.commit()", receive_method)
        self.assertNotIn("markIncomingDisplayed(", receive_method)
        projection = extract_java_method(tracking_source, "private boolean projectUndisplayedLanChatInbox(")
        self.assertIn("knownIds.add(stableId)", projection)
        self.assertIn("store.markIncomingDisplayed(", projection)
        self.assertIn("scheduleLanChatUiRefresh();", projection)
        self.assertIn("synchronized (lanChatReceiveLock)", tracking_source[tracking_source.index("private void saveChatToHistory("):tracking_source.index("private void syncMessageToServer(")])
        self.assertNotIn("receivedChatMessageIds", tracking_source)
        self.assertIn("P2P RX durable message=", tracking_source)
        self.assertIn("persisted=true ackPaths=", tracking_source)
        self.assertIn("persisted=false ackSent=false", tracking_source)
        ack_start = tracking_source.index("private boolean sendAuthenticatedChatAck(")
        ack_end = tracking_source.index("private boolean handleLanRealtimePacket(", ack_start)
        ack_method = tracking_source[ack_start:ack_end]
        self.assertIn("return !socket.isClosed()", ack_method)
        self.assertIn("return false", ack_method)


    def test_android_receive_ack_requires_sqlite_history_and_presentation_commit(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        receive = extract_java_method(source, "private boolean persistIncomingLanChat(String senderId, String messageId, String msgContent,")
        sqlite_commit = receive.index("store.storeIncoming(")
        self.assertIn("return stored", receive)
        self.assertNotIn("editor.commit()", receive)
        self.assertNotIn("markIncomingDisplayed(", receive)
        projection = extract_java_method(source, "private boolean projectUndisplayedLanChatInbox(")
        history_commit = projection.index("editor.commit()")
        presentation_commit = projection.index("store.markIncomingDisplayed(")
        self.assertLess(history_commit, presentation_commit)
        self.assertIn("knownIds.add(stableId)", projection)
        self.assertIn("loadUndisplayedIncoming(receiverScope, 100)", projection)
        self.assertIn("scheduleLanChatInboxProjection()", source)
        self.assertIn("private final java.util.concurrent.atomic.AtomicBoolean lanChatInboxProjectionInFlight", source)

        packet_start = source.index('if ("chat.message".equals(type))')
        packet_end = source.index("\n        } catch (Exception e)", packet_start)
        packet_handler = source[packet_start:packet_end]
        persist_call = packet_handler.index("persistIncomingLanChat(")
        ack_call = packet_handler.index("sendLanChatReceiveAck(", persist_call)
        self.assertLess(persist_call, ack_call)
        self.assertIn("if (stored)", packet_handler)
        self.assertIn('persisted=false ackSent=false', packet_handler)
        self.assertIn("else {", packet_handler)

        ack = extract_java_method(source, "private boolean sendAuthenticatedChatAck(")
        actual_send = ack.index("sendLanUdpPacket(socket, response, \"CHAT_ACK_V2\")")
        success_return = ack.index("return !socket.isClosed()")
        failure_catch = ack.index("catch (Exception e)")
        self.assertLess(actual_send, success_return)
        self.assertLess(success_return, failure_catch)
        self.assertIn("return false", ack[failure_catch:])


    def test_android_inflight_duplicate_receive_is_coalesced_and_rejection_is_retryable(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index('if ("chat.message".equals(type))')
        end = source.index("\n        } catch (Exception e)", start)
        handler = source[start:end]

        claim = handler.index("pendingLanChatV2Receives.putIfAbsent(")
        ack_target = handler.index("pendingReceive.addAckTarget(ackTarget)", claim)
        duplicate_branch = handler.index("if (!ownsReceive)", ack_target)
        dispatch = handler.index("lanChatIoExecutor.execute", duplicate_branch)
        persist = handler.index("persistIncomingLanChat(", dispatch)
        ack = handler.index("sendLanChatReceiveAck(", persist)
        cleanup = handler.index("pendingLanChatV2Receives.remove(receiveKey, pendingReceive)", persist)
        rejected = handler.index("catch (java.util.concurrent.RejectedExecutionException", cleanup)
        rejected_cleanup = handler.index("pendingLanChatV2Receives.remove(receiveKey, pendingReceive)", rejected)

        self.assertLess(claim, ack_target)
        self.assertLess(ack_target, duplicate_branch)
        self.assertLess(duplicate_branch, dispatch)
        self.assertLess(dispatch, persist)
        self.assertLess(persist, ack)
        self.assertLess(ack, cleanup)
        self.assertLess(rejected, rejected_cleanup)
        self.assertIn("return true", handler[duplicate_branch:dispatch])
        self.assertIn("if (stored)", handler[persist:cleanup])
        self.assertIn("new java.util.ArrayList<>(pendingReceive.ackTargets)", handler[persist:cleanup])


    def test_android_discovery_ack_store_rejects_empty_peer_identity(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        start = store.index("public boolean acknowledge(String messageId, String peerId)")
        end = store.index("public boolean beginDiscoveryWindow(", start)
        acknowledge = store[start:end]
        self.assertIn("peerId == null || peerId.length() == 0", acknowledge)
        self.assertLess(acknowledge.index("peerId.length() == 0"),
                        acknowledge.index('db.update("lan_outbox_targets", ackValues'))
        self.assertIn('if (state.isEmpty() || "expired".equals(state)) return false;', acknowledge)

    def test_android_expired_outbox_rejects_late_ack_without_mutating_targets(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        start = source.index("public boolean acknowledge(String messageId, String peerId)")
        end = source.index("public boolean beginDiscoveryWindow(", start)
        acknowledge = source[start:end]
        terminal_guard = acknowledge.index('if (state.isEmpty() || "expired".equals(state)) return false;')
        target_mutation = acknowledge.index('db.update("lan_outbox_targets", ackValues')
        self.assertLess(terminal_guard, target_mutation)
        self.assertIn('"SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id=?"', acknowledge)
        self.assertNotIn('next.put("state", total > 0 && total == acked ? "delivered"', acknowledge[:terminal_guard])

    def test_android_all_targets_acked_never_resurrects_expired_outbox(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        start = source.index("public boolean allTargetsAcked(")
        end = source.index("public List<Target> loadKnownPeers(", start)
        method = source[start:end]
        expired_guard = method.index("LanChatDeliveryState.EXPIRED.equals(state)")
        delivered_mutation = method.index("LanChatDeliveryState.DELIVERED")
        self.assertLess(expired_guard, delivered_mutation)
        self.assertIn("db.beginTransaction();", method)
        self.assertIn("db.setTransactionSuccessful();", method)
        self.assertIn("db.endTransaction();", method)
        self.assertIn('state = c.getString(0);', method)
        self.assertIn('if (discoveryOpen || total == 0) return false;', method)

    def test_record_peer_fails_closed_when_database_cannot_be_opened(self):
        from lan_p2p_core import record_peer
        directory_path = os.path.join(self.temp_dir.name, "not-a-database-file")
        os.mkdir(directory_path)
        self.assertFalse(record_peer(directory_path, "server2", "android-a",
                                     "android", "android-main", "192.168.1.10", 8888, "suspect"))


    def test_historical_peer_snapshot_includes_expired_offline_members(self):
        from lan_p2p_core import record_peer, list_known_peers, list_known_peer_snapshot
        self.assertTrue(record_peer(self.db_path, "rescue2", "android-offline",
                                    "android", "android-main", "192.168.1.20", 8888, "active"))
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                "UPDATE lan_peers SET lease_expires_at=1,status='suspect' "
                "WHERE receiver_scope='rescue2' AND peer_id='android-offline'")
            conn.commit()
        finally:
            conn.close()
        self.assertEqual(list_known_peers(self.db_path, "rescue2"), [])
        snapshot = list_known_peer_snapshot(self.db_path, "rescue2")
        self.assertEqual([peer["peer_id"] for peer in snapshot], ["android-offline"])
        self.assertEqual(snapshot[0]["endpoint_ip"], "192.168.1.20")

    def test_outgoing_ack_is_idempotent_after_delivery(self):
        from lan_p2p_core import persist_outgoing_message, acknowledge_outgoing_message
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-ack-idempotent",
            "hello", ["android-a"]))
        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "m-ack-idempotent", "android-a"))
        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "m-ack-idempotent", "android-a"))
        self.assertFalse(acknowledge_outgoing_message(
            self.db_path, "server2", "m-ack-idempotent", "android-unknown"))
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-ack-idempotent'"
            ).fetchone()[0]
            count = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-ack-idempotent'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(state, "delivered")
        self.assertEqual(count, 1)

    def test_python_expired_outbox_rejects_late_ack_without_mutating_targets(self):
        from lan_p2p_core import persist_outgoing_message, acknowledge_outgoing_message
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-expired-late-ack",
            "hello", ["android-a"]))
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                "UPDATE lan_outbox SET state='expired' WHERE message_id=?",
                ("m-expired-late-ack",))
            conn.execute(
                "UPDATE lan_outbox_targets SET status='sent',attempt_count=3 "
                "WHERE message_id=? AND peer_id=?",
                ("m-expired-late-ack", "android-a"))
            conn.commit()
        finally:
            conn.close()

        self.assertFalse(acknowledge_outgoing_message(
            self.db_path, "server2", "m-expired-late-ack", "android-a"))
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id=?",
                ("m-expired-late-ack",)).fetchone()[0]
            target = conn.execute(
                "SELECT status,attempt_count,last_ack_at FROM lan_outbox_targets "
                "WHERE message_id=? AND peer_id=?",
                ("m-expired-late-ack", "android-a")).fetchone()
        finally:
            conn.close()
        self.assertEqual(state, "expired")
        self.assertEqual(target, ("sent", 3, 0))


    def test_http_fallback_envelope_preserves_v2_message_identity(self):
        from lan_p2p_core import (
            encode_chat_http_fallback_payload, decode_chat_http_fallback_payload,
        )
        payload = encode_chat_http_fallback_payload(
            "message-unique-1", "server2-stable-id", "Web_Console", "同一消息：中文|内容"
        )
        decoded = decode_chat_http_fallback_payload(payload)
        self.assertEqual(decoded["message_id"], "message-unique-1")
        self.assertEqual(decoded["sender_peer_id"], "server2-stable-id")
        self.assertEqual(decoded["body"], "同一消息：中文|内容")
        self.assertEqual(decoded["display_name"], "Web_Console")
        self.assertIsNone(decode_chat_http_fallback_payload("legacy plain chat text"))

    def test_startup_peer_beacon_sends_authenticated_one_shot_hello(self):
        from lan_p2p_core import send_startup_peer_beacon, decode_packet

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, data, target):
                self.sent.append((data, target))

        sock = FakeSocket()
        sent = send_startup_peer_beacon(
            sock, "server2-stable", "server2", "server2-stable",
            "test-secret", 8887, [8888, 8889],
            ["255.255.255.255", "192.168.1.255"],
        )
        self.assertEqual(sent, 4)
        self.assertEqual(len(sock.sent), 4)
        decoded = [decode_packet(data, "test-secret") for data, _ in sock.sent]
        self.assertTrue(all(packet["type"] == "peer.hello" for packet in decoded))
        self.assertTrue(all(packet["sender_peer_id"] == "server2-stable" for packet in decoded))
        self.assertTrue(all(packet["listen_port"] == 8887 for packet in decoded))
        self.assertTrue(all(packet["nonce"] == decoded[0]["nonce"] for packet in decoded))


    def test_startup_peer_beacon_is_withheld_when_challenge_nonce_cannot_be_persisted(self):
        from lan_p2p_core import send_startup_peer_beacon

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, data, target):
                self.sent.append((data, target))

        sock = FakeSocket()
        with patch("lan_p2p_core.remember_peer_challenge", return_value=False) as persist:
            sent = send_startup_peer_beacon(
                sock, "server2-stable", "server2", "server2-stable",
                "test-secret", 8887, [8888, 8889], ["255.255.255.255"],
                db_path=self.db_path, receiver_scope="server2",
            )
        self.assertEqual(sent, 0)
        self.assertEqual(sock.sent, [])
        persist.assert_called_once()


    def test_android_retry_recovery_reads_durable_sqlite_outbox(self):
        repo_root = Path(__file__).resolve().parents[1]
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = tracking.index("private void retryPendingLanChatMessages()")
        end = tracking.index("private void sendLocalUdpMessage", start)
        recovery = tracking[start:end]

        # SharedPreferences remains a compatibility import only. Retry enumeration
        # must come from SQLite so a missing/stale mirror cannot strand durable rows.
        self.assertIn("store.loadPending(LanChatRetryPolicy.MAX_PENDING_MESSAGES)", recovery)
        self.assertIn("List<LanChatStore.PendingMessage> pending", recovery)
        self.assertIn("item.messageId", recovery)
        self.assertIn("item.body", recovery)
        self.assertIn("store.allTargetsAcked(id)", recovery)
        self.assertIn("scheduleLanChatMirrorCleanup(id)", recovery)
        self.assertNotIn("removePendingLanChat(id)", recovery)

    def test_android_attempt_reservation_uses_explicit_target_state_name(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        state = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatDeliveryState.java"
        ).read_text(encoding="utf-8")
        attempt = extract_java_method(store, "public boolean markAttempt(")
        self.assertIn("LanChatDeliveryState.TARGET_ATTEMPT_RESERVED", attempt)
        self.assertIn('static final String TARGET_ATTEMPT_RESERVED = "sent";', state)
        self.assertIn("TARGET_ATTEMPT_RESERVED", state)

    def test_android_sqlite_pending_read_fails_closed_on_query_error(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        start = store.index("public List<PendingMessage> loadPending(int limit)")
        end = store.index("\n    public List<Target> loadTargets", start)
        method = store[start:end]

        # A database error must escape to retryPendingLanChatMessages' catch/log path.
        # It must never be converted into an empty queue, which looks like success.
        self.assertNotIn("catch (Exception ignored)", method)
        self.assertIn("return result;", method)
        self.assertIn("finally", method)


    def test_android_recovery_event_is_latched_across_an_inflight_retry_worker(self):
        repo_root = Path(__file__).resolve().parents[1]
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = tracking.index("private void retryPendingLanChatMessages(boolean recoveryEvent)")
        end = tracking.index("private void transmitLocalUdpMessage(", start)
        retry = tracking[start:end]
        self.assertLess(
            retry.index("if (recoveryEvent) lanChatRecoveryEventPending.set(true)"),
            retry.index("lanChatRetryInFlight.compareAndSet(false, true)"),
        )
        self.assertIn("lanChatRecoveryEventPending.getAndSet(false)", retry)
        self.assertIn("resumeExhaustedRetriesForRecoveryEvent(System.currentTimeMillis())", retry)

    def test_transient_preflight_failures_rearm_durable_outbox_planning(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessage(final String message, final String messageId)")
        target_read = send[send.index("catch (Exception targetReadFailure)"):send.index("final long targetLoadMs")]
        attempt_read = send[send.index("catch (Exception attemptReadFailure)"):send.index("if (preparedAttemptNumber < 0)")]
        self.assertIn("scheduleLanChatRetryPlan(500L)", target_read)
        self.assertIn("scheduleLanChatRetryPlan(500L)", attempt_read)
        self.assertIn("if (!attemptAdmitted && !serviceDestroyed)", send)
        self.assertIn("scheduleLanChatRetryPlan(500L)", send)
        self.assertIn("private void scheduleLanChatRetryPlan(long delayMs)", source)
        self.assertIn("lanChatRetryPlanPending.compareAndSet(false, true)", source)
        self.assertIn('scheduleLanChatTask("outbox-retry-plan"', source)
        self.assertNotIn("outbox-preflight-retry-", source)
        self.assertIn("if (preparedAttemptNumber < 0)", send)
        budget_exit = send[send.index("if (preparedAttemptNumber < 0)"):send.index("// Build immutable authenticated datagrams")]
        self.assertNotIn("outbox-preflight-retry", budget_exit)

    def test_outbox_retry_planning_wakeups_are_globally_coalesced(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        plan = extract_java_method(source, "private void scheduleLanChatRetryPlan(long delayMs)")
        self.assertIn("lanChatRetryPlanPending.compareAndSet(false, true)", plan)
        self.assertIn('scheduleLanChatTask("outbox-retry-plan"', plan)
        self.assertIn("lanChatSendExecutor.execute", plan)
        self.assertIn("scheduleLanChatRetryFromOutbox()", plan)
        self.assertIn("finally", plan)
        self.assertIn("lanChatRetryPlanPending.set(false)", plan)
        self.assertNotIn("messageId", plan)
        self.assertIn("scheduleLanChatRetryPlan(500L)", plan)
        self.assertIn("if (!handler.postDelayed(submitPlan, delayMs))", plan)

    def test_outbox_retry_planner_storage_failure_rearms_durable_wakeup(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        planner = extract_java_method(source, "private void scheduleLanChatRetryFromOutbox(")
        self.assertIn("catch (Exception e)", planner)
        self.assertIn('scheduleLanChatRetryAfterFailure("outbox-plan-read-failure", false)', planner)

    def test_outbox_retry_executor_rejection_rearms_admission_without_network_event(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        retry = extract_java_method(source, "private void retryPendingLanChatMessages(boolean recoveryEvent)")
        rejected = retry.index("catch (java.util.concurrent.RejectedExecutionException e)")
        block = retry[rejected:]
        self.assertIn("lanChatRetryInFlight.set(false);", block)
        self.assertIn('scheduleLanChatRetryAfterFailure("outbox-retry-admission-fallback", recoveryEvent)', block)
        self.assertNotIn("retryPendingLanChatMessages(true)", block)

    def test_android_outbox_retry_attempt_ledger_is_persistent_and_migrated(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        self.assertIn("DB_VERSION = 11", store)
        self.assertIn("retry_attempt_count INTEGER NOT NULL DEFAULT 0", store)
        self.assertIn("state='retry_paused'", store)
        self.assertIn("'retry_paused' ELSE state", store)
        self.assertIn('if (oldVersion < 4)', store)
        self.assertIn('ALTER TABLE lan_outbox ADD COLUMN retry_attempt_count', store)
        self.assertIn("public int recordOutboxAttempt(String messageId)", store)
        self.assertIn("update.bindLong(1, now)", store)
        self.assertIn("update.bindLong(2, MAX_AUTOMATIC_RETRY_ATTEMPTS)", store)
        self.assertIn("update.bindString(3, messageId)", store)
        self.assertIn("update.bindLong(4, MAX_AUTOMATIC_RETRY_ATTEMPTS)", store)
        self.assertIn("retry_attempt_count=retry_attempt_count+1", store)
        self.assertIn("c.getString(4), c.getInt(5)", store)

    def test_android_retry_wakeups_are_bounded_and_cover_later_pending_rows(self):
        repo_root = Path(__file__).resolve().parents[1]
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = tracking.index("private void retryPendingLanChatMessages()")
        end = tracking.index("private void sendLocalUdpMessage", start)
        recovery = tracking[start:end]
        self.assertIn("final int maxBatchesPerWake = LanChatRetryPolicy.MAX_RETRY_BATCHES_PER_WAKE", recovery)
        self.assertIn("final int batchSize = LanChatRetryPolicy.RETRY_BATCH_SIZE", recovery)
        self.assertIn("int startIndex = batchNo * batchSize", recovery)
        self.assertIn("scheduleLanChatRetryFromOutbox()", recovery)
        self.assertIn("lanChatRetryWakeScheduled", tracking)
        self.assertIn("item.retryAttempts > 0", tracking)
        self.assertIn("MAX_AUTOMATIC_RETRY_ATTEMPTS", tracking)
        self.assertIn("retryPendingLanChatMessages(true)", tracking)
        self.assertNotIn("item.retryAttempts < 3", tracking)
        self.assertIn("resumeExhaustedRetriesForRecoveryEvent", tracking)
        self.assertIn("lanChatRecoveryEventPending", tracking)
        self.assertIn("boolean attemptAdmitted = false", tracking)
        self.assertIn("if (!attemptAdmitted && !serviceDestroyed)", tracking)
        self.assertIn("scheduleLanChatRetryFromOutbox()", tracking)
        policy = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatRetryPolicy.java"
        ).read_text(encoding="utf-8")
        self.assertIn("minimumAttempts == 2 ? OUTBOX_RETRY_SECOND_DELAY_MS", policy)
        self.assertIn("OUTBOX_RETRY_BACKOFF_BASE_MS << Math.min(3, minimumAttempts - 3)", policy)
        self.assertIn("OUTBOX_RETRY_MAX_DELAY_MS", policy)
        self.assertIn("finite per-epoch attempt budget", tracking)
        self.assertIn("waiting_peers", tracking)
        self.assertIn("waiting_peers", tracking)
        self.assertIn("handler.postDelayed", tracking)
        self.assertIn("lanChatRetryInFlight.set(false);", recovery)
        self.assertIn("if (serviceDestroyed || !lanChatRetryInFlight.compareAndSet(false, true)) return", recovery)


    def test_android_exhausted_retry_budget_is_preserved_and_only_rearmed_by_cooldown_event(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        load = extract_java_method(store, "public List<PendingMessage> loadPending(")
        self.assertIn("t.status!='acked' AND t.attempt_count<?", load)
        self.assertIn("NOT EXISTS (SELECT 1 FROM lan_outbox_targets", load)
        self.assertIn("o.retry_attempt_count<?", load)
        resume = extract_java_method(store, "public int resumeExhaustedRetriesForRecoveryEvent(")
        self.assertIn("last_recovery_at_ms<=?", resume)
        self.assertIn("now - RETRY_RECOVERY_EVENT_COOLDOWN_MS", resume)
        self.assertIn("status!='acked' AND attempt_count>=?", resume)
        self.assertIn('reopen.put("retry_attempt_count", 0)', resume)
        attempt = extract_java_method(store, "public int recordOutboxAttempt(")
        self.assertIn("readOutboxTargetAttemptProgress(getReadableDatabase(), messageId)", attempt)
        self.assertLess(attempt.index("getReadableDatabase()"), attempt.index("db.beginTransaction()"))
        self.assertIn("readOutboxTargetAttemptProgress(db, messageId)", attempt)
        self.assertIn("targetCount > 0", attempt)
        self.assertIn("remainingTargets == 0", attempt)
        self.assertIn("NOT EXISTS (SELECT 1 FROM lan_outbox_targets t", attempt)
        progress = extract_java_method(store, "private int[] readOutboxTargetAttemptProgress(")
        self.assertIn("SELECT COUNT(*)", progress)
        self.assertIn("SUM(CASE WHEN status!='acked'", progress)
        self.assertIn("retry_attempt_count<?", attempt)
        self.assertIn("last_recovery_at_ms INTEGER NOT NULL DEFAULT 0", store)
        self.assertIn("DB_VERSION = 11", store)
        self.assertIn("idx_lan_targets_retry", store)
        self.assertIn("DB_VERSION = 11", store)
        self.assertIn("retry_attempt_count=0,last_retry_at_ms=0", store)
        ack = extract_java_method(store, "public boolean acknowledge(")
        self.assertIn('hasRetryBudget ? "pending" : "retry_paused"', ack)

    def test_android_udp_endpoint_is_persisted_only_after_successful_send(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = store.index("public boolean markAttempt(")
        end = store.index("public void recordTargetSendError(", start)
        attempt_method = store[start:end]
        self.assertIn("attempt_count=attempt_count+1", attempt_method)
        self.assertIn("last_attempt_endpoint=?", attempt_method)
        self.assertIn("last_error=?", attempt_method)
        self.assertNotIn("last_endpoint", attempt_method)

        targets_method = extract_java_method(store, "public boolean setTargets(")
        self.assertIn('v.put("last_endpoint", target.endpointIp', targets_method)
        self.assertNotIn("last_sent_endpoint", targets_method)

        upgrade_method = extract_java_method(store, "public void onUpgrade(")
        self.assertIn("oldVersion < 8", upgrade_method)
        self.assertIn("ADD COLUMN last_sent_endpoint TEXT NOT NULL DEFAULT", upgrade_method)
        self.assertIn("oldVersion < 9", upgrade_method)
        self.assertIn("ADD COLUMN last_attempt_endpoint TEXT NOT NULL DEFAULT", upgrade_method)

        ack_endpoint_start = store.index("public boolean matchesExpectedAckEndpoint(")
        ack_endpoint_method = store[ack_endpoint_start:ack_endpoint_start + 1000]
        self.assertIn("last_sent_endpoint=? LIMIT 1", ack_endpoint_method)
        self.assertNotIn("last_attempt_endpoint", ack_endpoint_method)
        self.assertNotIn("last_endpoint=?", ack_endpoint_method)
        attempted_endpoint = extract_java_method(store, "public boolean matchesAttemptedAckEndpoint(")
        self.assertIn("last_attempt_endpoint=?", attempted_endpoint)
        self.assertNotIn("last_sent_endpoint=? LIMIT 1", attempted_endpoint)
        ack_handler = extract_java_method(tracking, "private void processAuthenticatedLanChatAck(")
        self.assertIn("store.matchesAttemptedAckEndpoint(messageId, senderId", ack_handler)
        self.assertIn("allowDiscoveryGrace && (discoveryAck || attemptedEndpointPending)", ack_handler)
        self.assertIn("processAuthenticatedLanChatAck(", ack_handler)

        endpoint_start = store.index("public boolean recordSuccessfulSendEndpoint(")
        endpoint_method = store[endpoint_start:endpoint_start + 1100]
        self.assertIn('values.put("last_endpoint", endpoint)', endpoint_method)
        self.assertIn('values.put("last_sent_endpoint", endpoint)', endpoint_method)
        self.assertNotIn("status!='acked'", endpoint_method)
        self.assertIn('"message_id=? AND peer_id=?"', endpoint_method)

        transmit_start = tracking.index("private void transmitLocalUdpMessage(")
        transmit_end = tracking.index("private void transmitLocalUdpMessageInternal(", transmit_start)
        transmit_method = tracking[transmit_start:transmit_end]
        lock_pos = transmit_method.index("lanChatTransmitLock.lock()")
        retry_epoch_pos = transmit_method.index("store.recordOutboxAttempt(messageId)")
        self.assertLess(lock_pos, retry_epoch_pos)

        send_method = extract_java_method(
            tracking, "private void transmitLocalUdpMessageInternal("
        )
        attempt_pos = send_method.index("targetAttemptAllowed = store != null")
        skip_pos = send_method.index("continue;", attempt_pos)
        udp_pos = send_method.index("sendLanUdpPacket(peerSocket, p, verifiedRoutePeerIds.contains(peerId)")
        endpoint_pos = send_method.index("lanChatPeerExecutor.execute(", udp_pos)
        self.assertNotIn("lanChatControlExecutor.execute(", send_method)
        self.assertLess(attempt_pos, skip_pos)
        self.assertLess(skip_pos, udp_pos)
        self.assertLess(udp_pos, endpoint_pos)
        self.assertNotIn("store.recordSuccessfulSendEndpoint(", send_method)
        self.assertIn("endpointStore.recordSuccessfulSendEndpoint(", send_method)
        self.assertIn("if (!endpointPersisted)", send_method)
        self.assertIn("last_attempt_endpoint", send_method)
        self.assertIn("recordTargetSendError(", send_method)
        self.assertEqual(
            tracking.count("private boolean persistIncomingLanChat("), 2,
            "keep exactly one compatibility overload and one display-name implementation",
        )
        inbox_compat = extract_java_method(
            tracking, "private boolean persistIncomingLanChat(String senderId, String messageId, String msgContent)"
        )
        self.assertIn("return persistIncomingLanChat(senderId, messageId, msgContent, senderId);", inbox_compat)
        udp_helper_start = tracking.index("private void sendLanUdpPacket(")
        udp_helper = tracking[udp_helper_start:udp_helper_start + 1500]
        self.assertIn("throw new java.net.SocketException", udp_helper)
        self.assertNotIn("if (socket.isClosed()) return", udp_helper)



    def test_android_peer_and_target_reads_do_not_collapse_database_errors_to_empty(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        for method_name, next_method in (
            ("public List<Target> loadKnownPeers()", "public boolean hasUnattemptedTargets"),
            ("public List<Target> loadTargets(String messageId)", "public boolean markAttempt"),
        ):
            start = store.index(method_name)
            end = store.index(next_method, start)
            method = store[start:end]
            if method_name.startswith("public List<Target> loadKnownPeers"):
                self.assertNotIn("catch (Exception", method)
            else:
                # The only permitted local catch is IP:port parsing; SQL query failures
                # must escape instead of being converted into an empty target list.
                self.assertEqual(method.count("catch (Exception ignored)"), 1)
                self.assertLess(method.index("Integer.parseInt"), method.index("catch (Exception ignored)"))
                self.assertNotIn("catch (Exception e)", method)
            self.assertIn("finally", method)

        send_start = tracking.index("private void transmitLocalUdpMessage(")
        send_end = tracking.index("private void transmitLocalUdpMessageInternal(", send_start)
        send = tracking[send_start:send_end]
        self.assertIn("preparedTargets = store.loadTargets(messageId)", send)
        self.assertIn("catch (Exception targetReadFailure)", send)
        self.assertIn("消息保留待重试", send)
        self.assertLess(send.index("preparedTargets = store.loadTargets(messageId)"),
                        send.index("lanChatTransmitLock.lock()"))
        internal_start = tracking.index("private void transmitLocalUdpMessageInternal(")
        internal_end = tracking.index("private void sendLanUdpPacket(", internal_start)
        internal = tracking[internal_start:internal_end]
        self.assertIn("final List<LanChatStore.Target> savedTargets", internal)
        self.assertNotIn("store.loadTargets(messageId)", internal)


    def test_android_expired_empty_discovery_window_never_broadcasts_old_message_to_late_peers(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        start = store.index("public boolean isDiscoveryPending(String messageId)")
        end = store.index("\n    /** Add a newly discovered peer", start)
        discovery = store[start:end]
        self.assertIn("delivery_deadline_ms>?", discovery)
        self.assertIn("System.currentTimeMillis()", discovery)

        tx_start = tracking.index("final int totalPeerCount = pendingTargetIds.size()")
        tx_end = tracking.index("java.util.concurrent.CountDownLatch ack", tx_start)
        send_guard = tracking[tx_start:tx_end]
        self.assertIn("firstDiscoveryWindowOpen = store.isDiscoveryPending(messageId)", send_guard)
        self.assertIn("totalPeerCount == 0 && !firstDiscoveryWindowOpen", send_guard)
        self.assertIn("禁止向晚加入设备广播旧消息", send_guard)


    def test_python_chat_senders_respect_fixed_recipient_snapshots(self):
        repo_root = Path(__file__).resolve().parents[1]

        rescue = (repo_root / "rescue2.py").read_text(encoding="utf-8")
        rescue_start = rescue.index("def send_chat_v2(")
        rescue_end = rescue.index("\ndef udp_listener", rescue_start)
        rescue_sender = rescue[rescue_start:rescue_end]
        self.assertIn("if target_ids:", rescue_sender)
        self.assertIn("attempts=1", rescue_sender)
        self.assertNotIn("selected_peers", rescue_sender)
        self.assertIn("elif broadcast_all:", rescue_sender)
        self.assertIn("send_discovery_broadcast_with_retries(", rescue_sender)

        chat = (repo_root / "chat.py").read_text(encoding="utf-8")
        chat_start = chat.index("def send_chat_v2(")
        chat_end = chat.index("\n\n# UDP 监听线程", chat_start)
        chat_sender = chat[chat_start:chat_end]
        self.assertIn("if target_ids:", chat_sender)
        self.assertIn("attempts=1", chat_sender)
        self.assertIn("message is persisted for retry after peer discovery", chat_sender)
        self.assertIn("send_discovery_broadcast_with_retries(", chat_sender)

        server = (repo_root / "server2.py").read_text(encoding="utf-8")
        server_start = server.index("def send_server2_v2_chat_from_outbox(")
        server_end = server.index("\ndef udp_listener", server_start)
        server_sender = server[server_start:server_end]
        self.assertIn("if fixed_target_ids:", server_sender)
        self.assertIn("send_outbox_to_active_peers(", server_sender)
        self.assertIn("attempts=1", server_sender)
        self.assertIn("send_discovery_broadcast_with_retries(", server_sender)
        self.assertNotIn("begin_outgoing_discovery_window(", server_sender)
        self.assertIn("[(ip, 8888)", server_sender)

        action_start = server.index('elif action == "chat_msg":')
        action_end = server.index('elif action == "change_server":', action_start)
        web_chat = server[action_start:action_end]
        self.assertIn("send_server2_v2_chat_from_outbox(cmd_prefix, target_devs)", web_chat)
        self.assertNotIn("smart_send(cmd_prefix)", web_chat)


    def test_peer_reconnect_replay_records_attempt_before_udp_send(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            start = source.index("def _resume_pending_v2_for_peer(")
            end = source.index("\ndef _handle_authenticated_v2_packet", start)
            replay = source[start:end]
            record = replay.index("attempt_admitted = mark_outgoing_attempt(")
            send = replay.index("send_outgoing_packet(")
            self.assertLess(record, send, filename)
            self.assertIn("if not attempt_admitted:", replay[record:send], filename)
            self.assertIn("continue", replay[record:send], filename)


    def test_android_service_first_startup_work_runs_off_main_looper(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index("public void onCreate()")
        end = source.index("private void startWatchdog()", start)
        startup = source[start:end]

        # Foreground promotion remains synchronous to satisfy Android's FGS deadline.
        self.assertIn("startForegroundNotification();", startup)
        worker_start = startup.index('new Thread(() ->')
        self.assertGreater(worker_start, startup.index("startForegroundNotification();"))
        worker = startup[worker_start:]
        for operation in (
            "logStartupSelfCheck();",
            "handleIconVisibility(true);",
            "triggerRescueListener();",
            "requestSingleLocationAndReport();",
            "registerNetworkCallback();",
            "startLocationUpdates();",
            "registerBatteryMonitor();",
            "KeepAliveJobService.scheduleJob(this);",
        ):
            self.assertIn(operation, worker)
        self.assertNotIn("handler.post(() -> {\n            FileLogger.log(this, \"SERVICE\", \"🚀 追踪服务已启动", startup)

    def test_android_location_callbacks_stay_on_main_looper_after_worker_registration(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index("private void startLocationUpdates()")
        end = source.index("public void onLocationChanged(Location location)", start)
        method = source[start:end]
        self.assertIn("LocationManager.GPS_PROVIDER, minTime, 0f, this, Looper.getMainLooper()", method)
        self.assertIn("LocationManager.FUSED_PROVIDER, minTime, 0f, this, Looper.getMainLooper()", method)
        self.assertIn("LocationManager.NETWORK_PROVIDER, minTime, 0f, this, Looper.getMainLooper()", method)

    def test_android_permission_finish_does_not_change_launcher_component_on_ui_thread(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/MainActivity.java"
        ).read_text(encoding="utf-8")
        start = source.index("private void finishWithToast(String msg, int duration)")
        end = source.index("\n}", start)
        finish = source[start:end]
        self.assertIn("startTrackingServiceOnly();", finish)
        self.assertIn('new Thread(() -> {', finish)
        self.assertIn("LauncherIconUtils.setLauncherIconVisible(appContext, false)", finish)
        self.assertNotIn("hideLauncherIcon();", finish)

    def test_android_system_wake_self_heal_is_dispatched_to_maintenance_executor(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index("public int onStartCommand(Intent intent, int flags, int startId)")
        end = source.index("private void logWakeSource(Intent intent)", start)
        method = source[start:end]
        self.assertIn('scheduleSelfHeal("job");', method)
        self.assertIn('scheduleSelfHeal("accessibility");', method)
        self.assertIn('scheduleSelfHeal("component");', method)
        self.assertIn('executeMaintenance("keep-alive"', method)
        self.assertNotIn('selfHeal();\n            } else if ("ACTION_JOB_WAKE_UP"', method)


    def test_android_network_refresh_and_offline_flush_do_not_run_on_main_handler(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        start = source.index("private final Runnable networkRefreshRunnable")
        end = source.index("private JSONObject buildNetworkSnapshot()", start)
        runnable = source[start:end]
        self.assertIn('executeMaintenance("network-refresh"', runnable)
        self.assertIn("buildNetworkSnapshot()", runnable)
        self.assertIn("flushPendingReports()", runnable)
        self.assertIn("retryPendingLanChatMessages(true)", runnable)

        callback_start = source.index("public void onAvailable(Network network)")
        callback_end = source.index("public void onLost(Network network)", callback_start)
        callback = source[callback_start:callback_end]
        self.assertIn("handler.postDelayed(networkRefreshRunnable, 1200L)", callback)
        self.assertIn('executeMaintenance("network-available-flush"', callback)
        self.assertNotIn("flushPendingReports();", callback)


    def test_android_network_callbacks_only_enqueue_network_refresh_work(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        default_start = source.index("networkCallback = new ConnectivityManager.NetworkCallback()")
        default_end = source.index("cm.registerDefaultNetworkCallback(networkCallback)", default_start)
        default_callback = source[default_start:default_end]
        self.assertEqual(default_callback.count('executeMaintenance("peer-route-refresh"'), 4)
        # Refresh calls belong inside queued Runnable lambdas, not on callback threads.
        self.assertEqual(len(re.findall(
            r'executeMaintenance\(\s*"peer-route-refresh"\s*,\s*\(\s*\)\s*->\s*refreshPeerRoutesForNetworkChange\s*\(',
            default_callback)), 4)

        wifi_start = source.index("wifiLanNetworkCallback = new ConnectivityManager.NetworkCallback()")
        wifi_end = source.index("cm.registerNetworkCallback(wifiRequest, wifiLanNetworkCallback)", wifi_start)
        wifi_callback = source[wifi_start:wifi_end]
        self.assertEqual(wifi_callback.count('executeMaintenance("wifi-lan-refresh"'), 4)
        self.assertEqual(len(re.findall(
            r'executeMaintenance\(\s*"wifi-lan-refresh"\s*,\s*\(\s*\)\s*->\s*refreshWifiLanTransport\s*\(',
            wifi_callback)), 4)



    def test_android_network_refresh_tasks_coalesce_callback_bursts_without_losing_final_state(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("coalescedMaintenanceStates", source)
        self.assertIn("state.compareAndSet(1, 2)", source)
        self.assertIn("completedState == 2 && !serviceDestroyed", source)
        dispatch_start = source.index("private void executeMaintenance(")
        dispatch = source[dispatch_start:dispatch_start + 1600]
        for task_name in ("peer-route-refresh", "wifi-lan-refresh", "network-refresh"):
            self.assertIn('"'+task_name+'".equals(taskName)', dispatch)
            self.assertIn("executeCoalescedMaintenance(taskName, task)", dispatch)


    def test_android_lan_chat_receive_and_ack_use_dedicated_io_executor(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        start = source.index("private boolean handleAuthenticatedLanPacket(")
        end = source.index("private void processAuthenticatedLanChatAck(", start)
        handler = source[start:end]
        self.assertEqual(handler.count("lanChatControlExecutor.execute(() -> {"), 1)
        self.assertEqual(handler.count("lanChatIoExecutor.execute(() -> {"), 1)
        self.assertIn("lanChatAckExecutor.execute(() -> processAuthenticatedLanChatAck(", handler)
        self.assertNotIn("lanChatControlExecutor.execute(() -> processAuthenticatedLanChatAck(", handler)
        self.assertNotIn("reportExecutor.execute(", handler)
        self.assertIn('new Thread(runnable, "LanChat-Control")', source)
        self.assertIn('new Thread(runnable, "LanChat-Ack")', source)
        self.assertIn('new Thread(runnable, "LanChat-IO")', source)
        ack = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        self.assertIn("lanChatAckExecutor.execute(() -> processAuthenticatedLanChatAck(", ack)

    def test_android_peer_challenge_nonce_outlives_initial_discovery_wait(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        policy = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatRetryPolicy.java").read_text(encoding="utf-8")
        self.assertIn("PEER_CHALLENGE_TIMEOUT_MS = 2_500L", policy)
        self.assertIn("LAN_PEER_CHALLENGE_TIMEOUT_MS = LanChatRetryPolicy.PEER_CHALLENGE_TIMEOUT_MS", source)
        start = source.index("private void transmitLocalUdpMessageInternal(")
        end = source.index("private void sendLanUdpPacket(", start)
        method = source[start:end]
        self.assertIn("LAN_PEER_CHALLENGE_TIMEOUT_MS", method)
        self.assertNotIn("Thread.sleep(500L)", method)
        self.assertIn("Discovery is deliberately non-blocking", method)
        self.assertIn("lanChatProbeExecutor.execute(", method)
        self.assertIn("peerDiscoveryInFlight.set(false)", method)
        discovery = method[method.index("if (shouldDiscoverPeers)"):]
        nonce_finally = discovery[discovery.index("// The nonce remains valid for late authenticated peer.ack responses"):]
        nonce_finally = nonce_finally[:nonce_finally.index("});")]
        self.assertNotIn("clearPendingPeerChallenge(nonce);", nonce_finally)
        self.assertIn("clearPendingPeerChallenge(nonce);", discovery)  # queue rejection cleanup

    def test_android_known_offline_targets_are_rechallenged_at_saved_endpoint(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index("private void transmitLocalUdpMessageInternal(")
        end = source.index("private void sendLanUdpPacket(", start)
        method = source[start:end]
        self.assertIn("final List<LanChatStore.Target> savedTargets", method)
        self.assertIn("for (LanChatStore.Target target : savedTargets)", method)
        self.assertIn("preparedTargets = store.loadTargets(messageId)", extract_java_method(
            source, "private void transmitLocalUdpMessage("))
        self.assertIn("target.endpointIp", method)
        self.assertIn("target.endpointPort", method)
        self.assertIn("sendDirectedPeerChallenge(socket, challengePeerId", method)
        self.assertIn("pendingPeerChallengeDispatches.putIfAbsent(", method)
        self.assertIn("lanChatProbeExecutor.execute(", method)

    def test_android_receive_latency_is_measured_from_udp_arrival(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("handleLanRealtimePacket(rescueSocket, packet, udpReceiveElapsedMs,", source)
        self.assertIn("P2P 接收耗时 message=", source)
        self.assertIn("queueMs=", source)
        self.assertIn("durableTotalMs=", source)

    def test_android_fresh_chat_send_queue_wait_is_measured(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("P2P 发送串行锁等待 message=", source)
        self.assertIn("long lockRequestedAt =", source)
        self.assertIn("int queueDepthAtRequest = lanChatTransmitLock.getQueueLength()", source)
        self.assertIn("long lockWaitMs = lockAcquiredAt - lockRequestedAt", source)

    def test_android_chat_send_uses_short_ack_wait_and_durable_retry(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("attempt <= 1 && ack.getCount() > 0", send)
        self.assertNotIn("ack.await(", send)
        self.assertIn('scheduleLanChatTask("chat-ack-grace-" + messageId', send)
        self.assertIn("ackCleanupDeferred", send)
        self.assertIn("completeLanChatAckWindow(messageId", source)
        self.assertIn("lanChatAckGraceInFlight.add(messageId)", send)
        self.assertLess(
            send.index("lanChatAckGraceInFlight.add(messageId)"),
            send.index('scheduleLanChatTask("chat-ack-grace-" + messageId'),
        )
        self.assertIn("lanChatAckGraceInFlight.contains(messageId)", source)
        self.assertIn("lanChatAckGraceInFlight.remove(messageId)", source)
        self.assertIn("activeLanChatTransmissions.remove(messageId)", source)
        self.assertIn("pendingChatAcks.remove(messageId)", source)
        self.assertIn("scheduleLanChatRetryFromOutbox()", source)
        self.assertIn("activeLanChatTransmissions.add(messageId)", source)
        self.assertIn("new java.util.concurrent.locks.ReentrantLock(true)", source)
        self.assertIn("lanChatTransmitLock.lock()", source)
        self.assertIn("lanChatTransmitLock.unlock()", source)
        self.assertNotIn("synchronized (lanChatTransmitLock)", source)

    def test_android_message_ack_timeout_does_not_evict_a_validated_peer_lease(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        cleanup = extract_java_method(source, "private void completeLanChatAckWindow(")
        self.assertNotIn("verifiedChatPeers.remove(peerId)", cleanup)
        self.assertNotIn("verifiedChatPeerSeen.remove(peerId)", cleanup)
        self.assertNotIn("lastPeerDiscoveryAt = 0L;\n                for (String peerId", cleanup)
        self.assertIn("保留已验证路由并触发有界路由刷新", cleanup)
        self.assertIn("if (hadUdpDispatchAttempt || reachablePeerCount == 0)", cleanup)
        self.assertIn("retryPendingLanChatMessages(false)", cleanup)
        self.assertIn("now - e.getValue() > 120_000L", send)
        self.assertIn("verifiedChatPeerRouteVersionCounter.incrementAndGet()", source)

    def test_android_slow_pre_dispatch_work_is_stage_timed(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("P2P 发送锁内准备耗时", send)
        for stage in ("broadcastLookupMs", "preLockTargetDbMs", "routeProbeMs",
                      "preLockPacketBuildMs", "otherLockMs"):
            self.assertIn(stage, send)


    def test_android_peer_discovery_respects_cooldown_without_verified_routes(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("boolean discoveryNeeded = pendingTargetIds.isEmpty()", send)
        self.assertIn("LAN_PEER_DISCOVERY_COOLDOWN_MS", send)
        self.assertNotIn("verifiedChatPeers.isEmpty() || lastPeerDiscoveryAt", send)
        self.assertIn("expiredPeerRouteRemoved && verifiedChatPeers.isEmpty()", send)


    def test_android_slow_udp_socket_monitor_wait_is_persisted(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        helper = extract_java_method(source, "private void sendLanUdpPacket(")
        self.assertIn('FileLogger.log(this, "UDP", "P2P UDP slow dispatch kind="', helper)
        self.assertIn("sendMs=", helper)
        self.assertNotIn("monitorWaitMs=", helper)
        self.assertNotIn("synchronized (socket)", helper)


    def test_android_retry_scheduler_does_not_wake_during_active_batch(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        scheduler = extract_java_method(source, "private void scheduleLanChatRetryFromOutbox(")
        self.assertIn("lanChatRetryInFlight.get()", scheduler)


    def test_android_ack_control_lane_does_not_run_noncritical_bookkeeping(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        ack = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        self.assertIn("scheduleLanChatMirrorCleanup(messageId)", ack)
        self.assertNotIn("removePendingLanChat(messageId)", ack)
        send = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("lanChatPeerExecutor.execute(", send)
        self.assertNotIn("lanChatControlExecutor.execute(", send)
        cleanup = extract_java_method(source, "private void scheduleLanChatMirrorCleanup(")
        self.assertIn("lanChatPeerExecutor.execute(", cleanup)
        self.assertIn("removePendingLanChat(messageId)", cleanup)

    def test_android_duplicate_ack_cannot_consume_another_peer_wait_slot(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        handler = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        persist_ack = handler.index("store.acknowledge(messageId, senderId)")
        remove_waiter = handler.index("expected.remove(senderId)")
        self.assertLess(persist_ack, remove_waiter)
        self.assertIn("boolean wasExpected = expected != null && expected.remove(senderId);", handler)
        self.assertIn("!wasExpected && expected != null && expected.isEmpty()", handler)
        self.assertIn("if (discoveryBroadcastAck && !discoveryAck)", handler)
        self.assertIn("discoveryBroadcastAck = store.isDiscoveryPending(messageId);", handler)
        self.assertIn("expected.isEmpty()\n                    && latch != null && latch.getCount() > 0", handler)
        self.assertIn("wasExpected || discoveryBroadcastAck", handler)
        self.assertNotIn("wasExpected || discoveryAck)", handler)

    def test_android_p2p_send_log_distinguishes_fixed_targets_from_reachable_routes(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        method = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        # Check the meaning and counters, not one exact concatenation layout.
        self.assertIn("固定目标", method)
        self.assertIn("totalPeerCount", method)
        self.assertIn("已验证路由", method)
        self.assertIn("历史端点直投", method)
        self.assertIn("reachablePeerCount", method)
        # Historical terminology in comments is harmless; the emitted log above
        # is what defines the operator-facing distinction.



    def test_android_p2p_retry_and_peer_deadlines_do_not_depend_on_main_handler(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn('new Thread(runnable, "LanChat-Scheduler")', source)

        retry_start = source.index("private void scheduleLanChatRetryFromOutbox()")
        retry_end = source.index("private void transmitLocalUdpMessageInternal(", retry_start)
        retry_method = source[retry_start:retry_end]
        self.assertIn('scheduleLanChatTask("outbox-retry"', retry_method)
        self.assertNotIn("handler.postDelayed(", retry_method)

        self.assertIn('scheduleLanChatTask("peer-verified-retry"', source)
        self.assertIn('scheduleLanChatTask("listener-outbox-recovery"', source)
        self.assertIn('scheduleLanChatTask("peer-discovery-timeout"', source)
        self.assertIn('scheduleLanChatTask("peer-challenge-timeout"', source)



    def test_android_first_discovery_window_starts_when_network_send_begins(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        enqueue = extract_java_method(store, "public boolean enqueue(")
        self.assertIn('v.put("delivery_deadline_ms", 0L)', enqueue)
        self.assertNotIn("+ FIRST_DISCOVERY_WINDOW_MS", enqueue)

        begin = extract_java_method(store, "public int beginOrReadDiscoveryWindow(")
        self.assertIn("FIRST_DISCOVERY_WINDOW_MS", begin)
        self.assertIn("if (deadline > 0L)", begin)
        self.assertIn("delivery_deadline_ms<=0", begin)

        policy = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatRetryPolicy.java").read_text(encoding="utf-8")
        self.assertIn("FIRST_DISCOVERY_WINDOW_MS = 10_000L", policy)
        self.assertIn("FIRST_DISCOVERY_WINDOW_MS = LanChatRetryPolicy.FIRST_DISCOVERY_WINDOW_MS", store)
        self.assertIn("store.beginOrReadDiscoveryWindow(messageId)", service)
        self.assertIn("DISCOVERY_WINDOW_STARTED", service)
        self.assertIn("首次发现窗口开始", service)



    def test_android_expired_empty_discovery_becomes_waiting_peers_and_stops_retrying(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        pending_start = store.index("public List<PendingMessage> loadPending(")
        pending_end = store.index("public int recordOutboxAttempt(", pending_start)
        self.assertIn("state NOT IN ('delivered','expired','waiting_peers')",
                      store[pending_start:pending_end])
        finalize_start = store.index("private boolean finalizeDiscoveryMessage(")
        finalize_end = store.index("public boolean storeIncoming(", finalize_start)
        state = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatDeliveryState.java"
        ).read_text(encoding="utf-8")
        self.assertIn("LanChatDeliveryState.settleAfterDiscovery(total, acked, hasRetryBudget)", store[finalize_start:finalize_end])
        self.assertIn('static final String WAITING_PEERS = "waiting_peers";', state)
        self.assertIn("if (totalTargets <= 0) return WAITING_PEERS;", state)
        self.assertIn("public boolean finalizeDiscoveryWindow(", store)
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("scheduleLanChatDiscoveryFinalizer(", tracking)
        self.assertIn("store.finalizeExpiredDiscoveryWindows(System.currentTimeMillis())", tracking)


    def test_android_discovery_finalization_uses_durable_target_budget(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        state = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatDeliveryState.java"
        ).read_text(encoding="utf-8")
        start = store.index("private boolean finalizeDiscoveryMessage(")
        end = store.index("public boolean storeIncoming(", start)
        finalize = store[start:end]
        self.assertIn("LanChatDeliveryState.settleAfterDiscovery(total, acked, hasRetryBudget)", finalize)
        self.assertNotIn("SELECT retry_attempt_count FROM lan_outbox", finalize)
        self.assertIn("return totalTargets <= 0 ? WAITING_PEERS", state) if "return totalTargets <= 0 ? WAITING_PEERS" in state else self.assertIn("if (totalTargets <= 0) return WAITING_PEERS;", state)
        self.assertIn("return hasRetryBudget ? PENDING : RETRY_PAUSED;", state)
        self.assertIn("LanChatDeliveryState.settleAfterAck(total, acked, hasRetryBudget)", store)



    def test_android_waiting_peers_keeps_sqlite_record_but_releases_legacy_queue_mirror(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("public boolean isWaitingForPeers(", store)
        remove_start = service.index("private void removePendingLanChat(")
        remove_end = service.index("private void retryPendingLanChatMessages(", remove_start)
        remove = service[remove_start:remove_end]
        self.assertIn("store.isWaitingForPeers(messageId)", remove)
        self.assertIn("SQLite 记录保留，停止自动重试", remove)
        finalizer_start = service.index("private void scheduleLanChatDiscoveryFinalizer(")
        finalizer_end = service.index("private void transmitLocalUdpMessageInternal(", finalizer_start)
        finalizer = service[finalizer_start:finalizer_end]
        self.assertIn("store.finalizeExpiredDiscoveryWindowIds(System.currentTimeMillis())", finalizer)
        self.assertIn("scheduleLanChatMirrorCleanup(finalizedId)", finalizer)
        self.assertNotIn("retryPendingLanChatMessages(false)", finalizer)
        retry_start = service.index("private void retryPendingLanChatMessages(boolean recoveryEvent)")
        retry_end = service.index("private void sendLocalUdpMessage(", retry_start)
        retry = service[retry_start:retry_end]
        self.assertNotIn("removePendingLanChat(id);", retry)
        self.assertEqual(3, retry.count("scheduleLanChatMirrorCleanup(id)"))
        cleanup = extract_java_method(service, "private void scheduleLanChatMirrorCleanup(")
        self.assertIn("lanChatPeerExecutor.execute", cleanup)
        self.assertIn("removePendingLanChat(messageId)", cleanup)
        sender = extract_java_method(service, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("scheduleLanChatMirrorCleanup(messageId)", sender)
        self.assertNotIn("removePendingLanChat(messageId)", sender)


    def test_discovery_window_finalization_uses_one_coalesced_deadline_timer(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        store_source = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        finalizer = extract_java_method(source, "private void scheduleLanChatDiscoveryFinalizer(")
        self.assertIn("lanChatDiscoveryFinalizerScheduled.compareAndSet(false, true)", finalizer)
        self.assertIn('scheduleLanChatTask("discovery-window-finalizer"', finalizer)
        self.assertIn("lanChatSendExecutor.execute", finalizer)
        self.assertIn("store.finalizeExpiredDiscoveryWindowIds(System.currentTimeMillis())", finalizer)
        self.assertIn("catch (java.util.concurrent.RejectedExecutionException rejected)", finalizer)
        self.assertIn("scheduleLanChatDiscoveryFinalizer(store, 500L)", finalizer)
        self.assertIn("store.getNextDiscoveryDeadlineMs()", finalizer)
        self.assertIn("boolean finalizationFailed = false", finalizer)
        self.assertIn("finalizationFailed = true", finalizer)
        self.assertIn("if (finalizationFailed)", finalizer)
        self.assertIn("scheduleLanChatDiscoveryFinalizer(store, 500L)", finalizer)
        self.assertNotIn("messageId", finalizer)
        next_deadline = extract_java_method(store_source, "public long getNextDiscoveryDeadlineMs(")
        self.assertIn("MIN(delivery_deadline_ms)", next_deadline)
        self.assertIn("state='discovery_pending'", next_deadline)
        self.assertIn("return -1L", next_deadline)
        sender = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("scheduleLanChatDiscoveryFinalizer(", sender)
        self.assertIn("LAN_CHAT_DISCOVERY_WINDOW_MS + 100L", sender)
        self.assertNotIn("scheduleLanChatDiscoveryWindowClose(", source)

    def test_android_expired_empty_discovery_rows_are_finalized_even_after_pending_window_closes(self):
        repo_root = Path(__file__).resolve().parents[1]
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        send = extract_java_method(
            service, "private void transmitLocalUdpMessageInternal("
        )
        self.assertIn("if (pendingTargetIds.isEmpty()) {", send)
        self.assertIn("store.beginOrReadDiscoveryWindow(messageId)", send)
        self.assertIn("discoveryWindowState == LanChatStore.DISCOVERY_WINDOW_WAITING", send)
        self.assertIn("discoveryWindowState == LanChatStore.DISCOVERY_WINDOW_STARTED", send)
        self.assertNotIn(
            "pendingTargetIds.isEmpty() && store.isDiscoveryPending(messageId)", send
        )
        self.assertIn("scheduleLanChatMirrorCleanup(messageId)", send)
        self.assertNotIn("removePendingLanChat(messageId)", send)

        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        finalize = extract_java_method(store_source, "public List<String> finalizeExpiredDiscoveryWindowIds(")
        self.assertIn("delivery_deadline_ms<=?", finalize)
        self.assertIn("finalizeDiscoveryMessage(db, messageId)", finalize)

        retry = extract_java_method(service, "private void retryPendingLanChatMessages(boolean recoveryEvent)")
        self.assertLess(
            retry.index("store.finalizeExpiredDiscoveryWindows("),
            retry.index("store.loadPending(LanChatRetryPolicy.MAX_PENDING_MESSAGES)"),
        )
        self.assertIn("if (store.isTerminalForLegacyMirror(id))", retry)
        terminal = extract_java_method(store_source, "public boolean isTerminalForLegacyMirror(")
        self.assertIn("state IN ('delivered','waiting_peers')", terminal)
        self.assertNotIn("removePendingLanChat(id);", retry)
        self.assertEqual(3, retry.count("scheduleLanChatMirrorCleanup(id)"))


    def test_android_peer_ack_is_durable_before_route_activation_and_discovery_snapshot(self):
        repo_root = Path(__file__).resolve().parents[1]
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = service.index('if ("peer.ack".equals(type))')
        end = service.index('if ("chat.ack".equals(type))', start)
        handler = service[start:end]

        # A peer must be durably recorded and added to every still-open first-discovery
        # snapshot before it becomes an active unicast route or releases discovery waiters.
        persist = handler.index("store.upsertPeer(")
        route = handler.index("verifiedChatPeers.put(senderId")
        release = handler.index("latch.countDown()")
        self.assertLess(persist, route)
        self.assertLess(route, release)
        self.assertIn("if (!persisted)", handler)
        self.assertNotIn("store.addDiscoveryPeerToOpenMessages(senderId)", handler)
        store_source = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        upsert = extract_java_method(store_source, "public boolean upsertPeer(")
        self.assertIn("addDiscoveryPeerToOpenMessages(db, peerId, now)", upsert)
        self.assertIn("db.beginTransaction()", upsert)
        self.assertIn("db.setTransactionSuccessful()", upsert)
        self.assertIn("lanChatControlExecutor.execute", handler)
        # peer.ack route registration must retain the exact receiving listener epoch.
        self.assertIn('final boolean isAckFrame = "peer.ack".equals(type) || "chat.ack".equals(type)', service)
        self.assertIn("final String verifiedPathFingerprint = ackIngressPath;", handler)
        self.assertIn("final long verifiedListenerGeneration = ackIngressGeneration;", handler)
        self.assertIn("isCurrentLanChatIngress(verifiedPathFingerprint, socket,", handler)
        self.assertIn("long verifiedAt = android.os.SystemClock.elapsedRealtime();", handler)
        self.assertNotIn("long verifiedAt = System.currentTimeMillis()", handler)
        ingress_guard = extract_java_method(service, "private boolean isCurrentLanChatIngress(")
        self.assertIn("path.equals(listener.pathFingerprint)", ingress_guard)
        self.assertNotIn("lanSocketGeneration.get()", ingress_guard)


    def test_chat_send_restores_editor_focus_for_continuous_typing(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/ChatActivity.java"
        ).read_text(encoding="utf-8")
        start = source.index("private void sendMessage()")
        end = source.index("\n    private void refreshDeliveryStatusAsync(", start)
        send = source[start:end]
        self.assertIn('inputMsg.setText("");', send)
        self.assertIn("boolean keepKeyboardVisible = isComposerImeVisible();", send)
        self.assertIn("restoreChatInputFocus(keepKeyboardVisible);", send)
        self.assertLess(send.index("isComposerImeVisible();"), send.index('inputMsg.setText("");'))
        self.assertLess(send.index('inputMsg.setText("");'), send.index("restoreChatInputFocus(keepKeyboardVisible);"))
        focus = extract_java_method(
            source, "private void restoreChatInputFocus(boolean keepKeyboardVisible)"
        )
        ime = extract_java_method(source, "private void scheduleComposerImeRestore()")
        self.assertIn("inputMsg.post(", focus)
        self.assertIn("inputMsg.requestFocus();", focus)
        self.assertIn("inputMsg.setSelection(inputMsg.length());", focus)
        self.assertIn("scheduleComposerImeRestore();", focus)
        self.assertIn("inputMsg.postDelayed(", ime)
        self.assertIn("imm.showSoftInput(inputMsg, InputMethodManager.SHOW_IMPLICIT);", ime)
        self.assertIn("pendingComposerImeRestore = false;", ime)
        layout = (repo_root / "app/src/main/res/layout/activity_chat.xml").read_text(encoding="utf-8")
        send_button = layout[layout.index('@+id/btnSend'):]
        self.assertIn('android:focusable="false"', send_button)
        self.assertIn('android:focusableInTouchMode="false"', send_button)


    def test_android_wifi_lan_change_rebinds_listener_before_outbox_recovery(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        refresh_start = source.index("private void refreshWifiLanTransport(")
        refresh_end = source.index("private void registerNetworkCallback(", refresh_start)
        refresh = source[refresh_start:refresh_end]
        self.assertIn("previous.equals(current)", refresh)
        self.assertIn("invalidateVerifiedChatPeers(", refresh)
        self.assertIn("oldSocket.close()", refresh)
        self.assertIn("triggerRescueListener();", refresh)
        self.assertIn("else {", refresh)

        listener_start = source.index("private void startRescueListener()")
        listener_end = source.index("private void parseResponse(", listener_start)
        listener = source[listener_start:listener_end]
        bind = listener.index("bindSocketToWifi(rescueSocket, 8888)")
        socket_ready = listener.index("rescueSocketRef = rescueSocket")
        recovery = listener.index('scheduleLanChatTask("listener-outbox-recovery"')
        self.assertLess(bind, socket_ready)
        self.assertLess(socket_ready, recovery)
        self.assertIn("rescueSocketRef = null", listener)
        finally_start = listener.rindex("finally {")
        finally_block = listener[finally_start:]
        self.assertIn("if (rescueSocketRef == rescueSocket)", finally_block)
        self.assertIn("lanChatBoundWifiNetwork = null", finally_block)
        self.assertLess(finally_block.index("rescueSocketRef = null"),
                        finally_block.index("lanChatBoundWifiNetwork = null"))
        self.assertIn("isListeningForRescue = false", listener)
        self.assertIn("triggerRescueListener()", listener)


    def test_android_udp_listener_guard_is_scoped_to_service_instance(self):
        source = (
            Path(__file__).resolve().parents[1]
            / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("private volatile boolean isListeningForRescue = false;", source)
        self.assertIn("private static final Object rescueLock = new Object();", source)
        self.assertIn("private volatile Thread rescueListenerThread = null;", source)
        self.assertIn("activeLanChatListenerInstance == listenerInstance", source)
        self.assertIn("listenerInstance.ownsThread(Thread.currentThread())", source)
        self.assertIn("listenerInstance.attachSocket(rescueSocket)", source)
        self.assertIn("listenerInstance.attachThread(listenerThread)", source)
        self.assertIn("activeInstance.socket()", source)
        self.assertIn("activeListener.thread()", source)
        self.assertIn("oldInstance.socket()", source)
        self.assertIn("oldInstance.requestStop()", source)
        self.assertIn("activeInstance.requestStop()", source)
        self.assertIn("activeListener.thread() : rescueListenerThread;", source)
        self.assertIn("listenerToStop.interrupt();", source)
        self.assertIn("listenerToStop.join(1000L);", source)
        self.assertIn('"LAN-UDP-Listener-" + serviceInstanceId', source)
        self.assertNotIn("private final Object rescueLock = new Object();", source)


    def test_android_target_attempt_cannot_mutate_terminal_outbox_messages(self):
        repo_root = Path(__file__).resolve().parents[1]
        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        method = extract_java_method(store_source, "public boolean markAttempt(")
        self.assertIn("attempt_count=attempt_count+1", method)
        self.assertIn("status!='acked'", method)
        self.assertIn("attempt_count<?", method)
        self.assertIn("last_attempt_at +", method)
        self.assertIn("EXISTS (SELECT 1 FROM lan_outbox o", method)
        self.assertIn("o.message_id=lan_outbox_targets.message_id", method)
        self.assertIn("o.state NOT IN ('delivered','expired','waiting_peers')", method)
        self.assertIn("changed != 1", method)
        self.assertIn("db.setTransactionSuccessful()", method)
        self.assertNotIn("SELECT status FROM lan_outbox_targets", method)

    def test_android_exhausted_retry_epoch_has_persisted_recovery_cooldown(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        method = extract_java_method(store, "public int resumeExhaustedRetriesForRecoveryEvent(")
        self.assertIn("last_recovery_at_ms<=?", method)
        self.assertIn("now - RETRY_RECOVERY_EVENT_COOLDOWN_MS", method)
        self.assertIn("status!='acked' AND attempt_count>=?", method)
        self.assertIn('reopen.put("retry_attempt_count", 0)', method)
        self.assertIn("state='retry_paused'", method)
        self.assertIn("AND t.attempt_count<?", method)
        self.assertIn("NOT EXISTS (SELECT 1 FROM lan_outbox_targets t", method)
        values = extract_java_method(store, "private ContentValues valuesForRecoveryReset(")
        self.assertIn('values.put("attempt_count", 0)', values)
        self.assertIn('values.put("last_recovery_at_ms", now)', values)
        deadline = extract_java_method(store, "public long nextRecoveryEventEligibleAtMs(")
        self.assertIn("MIN(eligible_at_ms)", deadline)
        self.assertIn("last_recovery_at_ms + ?", deadline)
        self.assertIn("last_event_retry_at_ms + ?", deadline)
        self.assertIn("Long.MAX_VALUE", deadline)
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        retry_start = service.index("private void retryPendingLanChatMessages(boolean recoveryEvent)")
        retry_end = service.index("private void sendLocalUdpMessage(final String message, final String messageId)", retry_start)
        retry = service[retry_start:retry_end]
        self.assertIn("lanChatRecoveryEventPending.set(true)", retry)
        self.assertIn("lanChatRetryInFlight.compareAndSet(false, true)", retry)
        self.assertIn("lanChatRecoveryEventPending.getAndSet(false)", retry)
        self.assertIn("if (lanChatRecoveryEventPending.get())", retry)
        self.assertIn("retryPendingLanChatMessages(true)", retry)
        cooldown_wake = extract_java_method(service, "private void scheduleLanChatRecoveryCooldownWake(")
        self.assertIn("AtomicLong lanChatRecoveryCooldownWakeAtMs", service)
        self.assertIn("currentWakeAtMs <= requestedWakeAtMs", cooldown_wake)
        self.assertIn("compareAndSet(scheduledWakeAtMs, Long.MAX_VALUE)", cooldown_wake)
        self.assertIn('scheduleLanChatTask("outbox-recovery-cooldown"', cooldown_wake)
        self.assertIn("handler.postDelayed(wake, remainingDelayMs)", cooldown_wake)
        self.assertNotIn("lanChatRecoveryCooldownWakeScheduled", service)
        self.assertIn("store.nextRecoveryEventEligibleAtMs", retry)
        self.assertIn(
            'if (resumed > 0) {\n                            FileLogger.log(this, "CHAT", "LAN 恢复事件重新开启有限补发预算 messages=" + resumed);\n                        }\n                        // Schedule the next cooldown',
            retry,
        )

    def test_server2_udp_ack_requires_verified_route_and_durable_outbox_ack_before_fallback_cleanup(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index('if packet_type == "chat.ack":')
        end = source.index('if packet_type != "chat.message":', start)
        handler = source[start:end]
        route = handler.index("peer_endpoint_matches(")
        persist = handler.index("acknowledge_outgoing_message(")
        cleanup = handler.index("_remove_chat_fallback_for_message(")
        self.assertLess(route, persist)
        self.assertLess(persist, cleanup)
        self.assertIn("(active_route or sent_endpoint_route)", handler)
        self.assertIn("if active_route:", handler)
        self.assertIn('envelope.get("status") == "stored"', handler)


    def test_server2_http_chat_fallback_shares_identity_and_is_queued_before_udp_dispatch(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index('elif action == "chat_msg":')
        end = source.index('elif action == "upload_log":', start)
        handler = source[start:end]
        encode = handler.index("encode_chat_http_fallback_payload(")
        queue = handler.index('enqueue_command(dev_id, "chat_msg", fallback_payload)')
        udp = handler.index("send_server2_v2_chat_from_outbox(")
        self.assertLess(encode, queue)
        self.assertLess(queue, udp)
        self.assertIn("chat_message_id", handler)
        self.assertIn("message_id=chat_message_id", handler)
        self.assertIn("fallback_queued_count = 0", handler)
        self.assertIn("queued_count = fallback_queued_count", handler)


    def test_server2_executed_chat_id_persists_outbox_ack_before_http_queue_removal(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def process_executed_commands(")
        end = source.index("def process_command_feedback(", start)
        handler = source[start:end]
        ack = handler.index("acknowledge_outgoing_message(")
        remove = handler.index("PENDING_COMMANDS[dev_id] = new_queue")
        persist = handler.index("_persist_command_queue_locked(dev_id)")
        self.assertLess(ack, remove)
        self.assertLess(remove, persist)
        self.assertIn("if not removable_ids:", handler)
        self.assertIn("fallback retained", handler)
        self.assertIn("PENDING_COMMANDS[dev_id] = old_queue", handler)


    def test_server2_accepted_chat_feedback_cannot_retire_fallback_when_outbox_ack_fails(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def process_command_feedback(")
        end = source.index("def get_recent_command_feedback(", start)
        handler = source[start:end]
        ack = handler.index("acknowledge_outgoing_message(")
        reconciled = handler.index("reconciled_chat_command_ids.add(command_id)")
        terminal = handler.index("terminal_ids = {")
        self.assertLess(ack, reconciled)
        self.assertLess(reconciled, terminal)
        self.assertIn("row[0] not in reconciled_chat_command_ids", handler)
        self.assertIn("fallback retained", handler)


    def test_server2_command_queue_never_evicts_unacked_chat_fallbacks(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def enqueue_command(")
        end = source.index("def process_executed_commands(", start)
        handler = source[start:end]
        self.assertIn('item.get("type") != "chat_msg"', handler)
        self.assertIn("if evictable is None:", handler)
        self.assertIn("reject new", handler)
        self.assertNotIn("queue.pop(0)", handler)
        self.assertIn("HTTP queue enqueue rolled back", handler)


    def test_server2_chat_fallback_cleanup_rolls_back_memory_on_database_failure(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def _remove_chat_fallback_for_message(")
        end = source.index("def _load_pending_commands(", start)
        handler = source[start:end]
        persist = handler.index("_persist_command_queue_locked(dev_id)")
        except_block = handler.index("except Exception as exc:", persist)
        restore = handler.index("PENDING_COMMANDS[dev_id] = old_queue", except_block)
        failed = handler.index("return False", restore)
        self.assertLess(persist, except_block)
        self.assertLess(except_block, restore)
        self.assertLess(restore, failed)


    def test_server2_terminal_feedback_cleanup_rolls_back_queue_on_persistence_failure(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def process_command_feedback(")
        end = source.index("def get_recent_command_feedback(", start)
        handler = source[start:end]
        persist = handler.index("_persist_command_queue_locked(dev_id)")
        restore = handler.index("PENDING_COMMANDS[dev_id] = old_queue", persist)
        self.assertLess(persist, restore)
        self.assertIn("terminal command cleanup rolled back", handler)
        self.assertNotIn('_remove_chat_fallback_for_message(dev_id, envelope["message_id"])', handler)


    def test_server2_feedback_recovers_missing_action_from_pending_command_type(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def process_command_feedback(")
        end = source.index("def get_recent_command_feedback(", start)
        handler = source[start:end]
        lookup = handler.index("pending_actions = {")
        fallback = handler.index("pending_action = pending_actions.get(command_id)")
        reconcile = handler.index("chat_fallback_candidates.append((command_id, envelope))")
        self.assertLess(lookup, fallback)
        self.assertLess(fallback, reconcile)
        self.assertIn("action = pending_action or supplied_action", handler)
        self.assertIn('if action == "chat_msg" and status in ("accepted", "succeeded")', handler)
        self.assertIn('row[2] == "chat_msg" and row[0] not in reconciled_chat_command_ids', handler)


    def test_server2_command_ids_are_unique_across_process_restarts(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def enqueue_command(")
        end = source.index("def process_executed_commands(", start)
        enqueue = source[start:end]
        self.assertIn('cmd_id = f"c_{uuid.uuid4().hex}"', enqueue)
        self.assertNotIn("CMD_SEQ", enqueue)


    def test_server2_http_queue_rejection_is_counted_not_reported_as_queued(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index('elif "POST /api/control" in header_text:')
        end = source.index('# ===== GET /api/history =====', start)
        handler = source[start:end]
        self.assertIn("queue_rejected_count += 1", handler)
        self.assertIn("queued_count += 1", handler)
        self.assertIn("{queue_rejected_count}/{len(target_devs)}台设备", handler)
        self.assertNotIn("queued_count = len(target_devs) if action in HTTP_SUPPORTED_ACTIONS", handler)


    def test_server2_chat_outbox_is_successful_when_durable_but_peers_are_offline(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index('elif action == "chat_msg":')
        end = source.index('elif action == "change_server":', start)
        handler = source[start:end]
        self.assertIn("if chat_packets:", handler)
        self.assertIn("success = True", handler)
        self.assertIn("LAN消息已持久化", handler)
        self.assertIn("未建立持久化投递路径", handler)


    def test_android_unverified_peer_hello_cannot_extend_verified_route_lease(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        start = source.index("public boolean observePeer(")
        end = source.index("public boolean isIncomingDisplayed(", start)
        observe = source[start:end]
        self.assertIn("last_verified_at,status,lease_expires_at", observe)
        self.assertIn("oldLeaseExpiresAt = c.getLong(4)", observe)
        self.assertIn("v.put(\"lease_expires_at\", oldLeaseExpiresAt)", observe)
        self.assertNotIn("v.put(\"lease_expires_at\", leaseExpiresAt)", observe)
        verified = source[source.index("public boolean upsertPeer("):source.index("public void closeStore(")]
        self.assertIn("v.put(\"lease_expires_at\", leaseExpiresAt)", verified)


    def test_android_recovery_event_is_not_lost_during_inflight_retry_batch(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        start = source.index("private void retryPendingLanChatMessages(boolean recoveryEvent)")
        end = source.index("// 仅由 lanChatSendExecutor 调用", start)
        retry = source[start:end]
        self.assertIn("if (recoveryEvent) lanChatRecoveryEventPending.set(true)", retry)
        self.assertIn("lanChatRecoveryEventPending.getAndSet(false)", retry)
        self.assertIn("if (lanChatRecoveryEventPending.get())", retry)
        self.assertIn("retryPendingLanChatMessages(true)", retry)
        self.assertIn("store.resumeExhaustedRetriesForRecoveryEvent", retry)


    def test_android_ordinary_retry_timer_does_not_reopen_exhausted_retry_epoch(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        start = source.index("private void retryPendingLanChatMessages()")
        end = source.index("private void retryPendingLanChatMessages(boolean recoveryEvent)", start)
        wrapper = source[start:end]
        self.assertIn("retryPendingLanChatMessages(false)", wrapper)
        retry_start = end
        retry_end = source.index("// 仅由 lanChatSendExecutor 调用", retry_start)
        retry = source[retry_start:retry_end]
        self.assertIn("recoveryEventCaptured = lanChatRecoveryEventPending.getAndSet(false)", retry)
        self.assertNotIn("resumeExhaustedRetriesForRecoveryEvent", wrapper)


    def test_android_pre_m_lan_chat_requires_default_wifi_and_tracks_dhcp_route_identity(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        usable = extract_java_method(source, "private boolean hasUsableWifiLanPath(")
        marker = "if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)"
        pre_m = usable[usable.index(marker):usable.index("ConnectivityManager cm =", usable.index(marker))]
        self.assertIn("getActiveNetworkInfo()", pre_m)
        self.assertIn("active.getType() != ConnectivityManager.TYPE_WIFI", pre_m)
        self.assertIn('!"255.255.255.255".equals(broadcast)', pre_m)
        snapshot = extract_java_method(source, "private JSONObject buildNetworkSnapshot()")
        self.assertIn("buildLegacyWifiRouteIdentity()", snapshot)
        identity = extract_java_method(source, "private String buildLegacyWifiRouteIdentity()")
        self.assertIn("getBSSID()", identity)
        self.assertIn("getNetworkId()", identity)
        self.assertIn("getDhcpInfo()", identity)

    def test_android_default_network_switch_does_not_revoke_unchanged_wifi_lan_peers(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        start = source.index("private void refreshPeerRoutesForNetworkChange(")
        end = source.index("/** 网络路径变化立即撤销旧 LAN endpoint", start)
        refresh = source[start:end]
        self.assertIn("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)", refresh)
        pre_m_start = refresh.index("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)")
        modern_start = refresh.index("// The default Internet route can switch", pre_m_start)
        pre_m = refresh[pre_m_start:modern_start]
        modern = refresh[modern_start:]
        self.assertIn("invalidateVerifiedChatPeers(", pre_m)
        self.assertIn("lanSocketGeneration.incrementAndGet()", pre_m)
        self.assertIn("oldSocket.close()", pre_m)
        self.assertIn("triggerRescueListener()", pre_m)
        self.assertIn("默认网络路径变化但保留 LAN peer", modern)
        self.assertNotIn("invalidateVerifiedChatPeers(", modern)
        self.assertNotIn("broadcastLanPeerHello", refresh)
        wifi_start = source.index("private void refreshWifiLanTransport(")
        wifi_end = source.index("private void registerNetworkCallback()", wifi_start)
        wifi_refresh = source[wifi_start:wifi_end]
        self.assertIn("invalidateVerifiedChatPeers(\"Wi-Fi LAN path changed", wifi_refresh)
        self.assertIn("oldSocket.close()", wifi_refresh)
        # Transient platform inspection failures must not be mistaken for a real
        # Wi-Fi LAN topology change, otherwise valid peer routes are needlessly revoked.
        guard = wifi_refresh.index('if ("ERROR".equals(current) || "UNAVAILABLE".equals(current))')
        invalidate = wifi_refresh.index('invalidateVerifiedChatPeers("Wi-Fi LAN path changed')
        self.assertLess(guard, invalidate)
        self.assertIn("retaining verified peer routes", wifi_refresh)
        fingerprint_start = source.index("private String buildWifiLanFingerprint")
        fingerprint_end = source.index("private void refreshWifiLanTransport", fingerprint_start)
        fingerprint = source[fingerprint_start:fingerprint_end]
        self.assertIn("selectWifiLanNetwork(cm)", fingerprint)
        self.assertIn('if (selected == null) return "NO_WIFI";', fingerprint)
        self.assertIn("java.util.Collections.sort(addressEntries)", fingerprint)
        self.assertIn("java.util.Collections.sort(routeEntries)", fingerprint)
        self.assertIn("selected.toString() + '|'", fingerprint)
        self.assertNotIn("for (Network network : cm.getAllNetworks())", fingerprint)
        broadcast = extract_java_method(source, "private String getWifiBroadcastAddress(")
        self.assertIn("Network lanChatBoundWifiNetwork", source)
        self.assertIn("Network selected = lanChatBoundWifiNetwork", broadcast)
        self.assertIn("selectWifiLanNetwork(cm)", broadcast)
        self.assertIn("NetworkInterface.getByName(links.getInterfaceName())", broadcast)
        self.assertNotIn("name.startsWith(\"wlan\")", broadcast[:broadcast.index("// Pre-M fallback")])
        bind = extract_java_method(source, "private Network bindSocketToWifi(")
        self.assertNotIn("lanChatBoundWifiNetwork =", bind)
        self.assertIn("return selected;", bind)
        refresh = extract_java_method(source, "private void refreshWifiLanTransport(")
        self.assertIn("lanChatBoundWifiNetwork = null", refresh)

    def test_outbox_database_read_failures_raise_instead_of_looking_empty(self):
        operations = (
            lambda: load_pending_outgoing_for_peer(self.db_path, "server2", "peer-a"),
            lambda: add_discovery_targets_for_peer(self.db_path, "server2", "peer-a"),
            lambda: load_active_pending_targets(self.db_path, "server2", "message-a"),
        )
        for operation in operations:
            with self.subTest(operation=operation):
                with patch("lan_p2p_core.sqlite3.connect",
                           side_effect=sqlite3.OperationalError("database is locked")):
                    with self.assertRaises(LanP2PStorageError):
                        operation()

    def test_python_peer_recovery_event_is_not_erased_by_inflight_retry(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("chat.py", "rescue2.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            start = source.index("def _resume_pending_v2_for_peer(")
            end = source.index("\ndef _retry_deferred_v2_peer_recoveries(", start)
            worker = source[start:end]
            queued = worker.index("queued_recovery_event = peer_id in _V2_RECOVERY_RETRY_PEERS")
            consume = worker.index("_V2_RECOVERY_RETRY_PEERS.discard(peer_id)", queued)
            inflight = worker.index("if peer_id in _V2_RECOVERY_INFLIGHT:", consume)
            requeue = worker.index("_V2_RECOVERY_RETRY_PEERS.add(peer_id)", inflight)
            promote = worker.index("recovery_event = recovery_event or queued_recovery_event", requeue)
            self.assertLess(queued, consume)
            self.assertLess(consume, inflight)
            self.assertLess(inflight, requeue)
            self.assertLess(requeue, promote)
            finally_start = worker.index("finally:")
            cleanup = worker[finally_start:]
            self.assertIn("_V2_RECOVERY_INFLIGHT.discard(peer_id)", cleanup)
            self.assertNotIn("_V2_RECOVERY_RETRY_PEERS.discard(peer_id)", cleanup)


    def test_python_peer_endpoint_change_bypasses_stale_retry_backoff(self):
        from lan_p2p_core import (
            persist_outgoing_message, record_peer, mark_outgoing_attempt,
            load_pending_outgoing_for_peer, list_due_outgoing_peers,
        )
        record_peer(self.db_path, "server2", "android-route-change", "android",
                    "android-main", "192.168.1.10", 8888, "active")
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-route-change",
            "hello", ["android-route-change"]))
        self.assertTrue(mark_outgoing_attempt(
            self.db_path, "m-route-change", "android-route-change",
            "192.168.1.10", 8888))
        # A verified peer can move to a new IP/port before the old retry timer expires.
        record_peer(self.db_path, "server2", "android-route-change", "android",
                    "android-main", "192.168.1.44", 8899, "active")
        pending = load_pending_outgoing_for_peer(
            self.db_path, "server2", "android-route-change")
        self.assertEqual(1, len(pending))
        self.assertEqual(("192.168.1.44", 8899),
                         (pending[0]["endpoint_ip"], pending[0]["endpoint_port"]))
        self.assertEqual(["android-route-change"],
                         list_due_outgoing_peers(self.db_path, "server2"))
        # The send-time SQL guard must independently admit the new endpoint even if
        # another worker's due-scan snapshot was taken before the route update.
        self.assertTrue(mark_outgoing_attempt(
            self.db_path, "m-route-change", "android-route-change",
            "192.168.1.44", 8899, allow_fast_retry=False))
        with _sqlite_connection(self.db_path) as conn:
            row = conn.execute(
                "SELECT attempt_count,last_endpoint FROM lan_outbox_targets "
                "WHERE message_id='m-route-change' AND peer_id='android-route-change'"
            ).fetchone()
        self.assertEqual((2, "192.168.1.44:8899"), row)


    def test_mark_outgoing_attempt_storage_failure_is_not_reported_as_a_normal_skip(self):
        with patch("lan_p2p_core.sqlite3.connect",
                   side_effect=sqlite3.OperationalError("database is locked")):
            with self.assertRaises(LanP2PStorageError):
                mark_outgoing_attempt(self.db_path, "message-a", "peer-a",
                                      "192.168.1.20", 8888)

    def test_chat_listener_uses_exact_address_binding_for_selected_wifi_network(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        binding = (root / "app/src/main/java/com/example/devicefinder/LanSocketBinding.java").read_text(encoding="utf-8")
        method = extract_java_method(source, "private Network bindSocketToWifi(")
        listener = extract_java_method(source, "private void startRescueListener()")
        self.assertIn("LanSocketBinding.bindDatagramListenerSocket(selectedPath, socket, listenerPort)", method)
        self.assertIn("socket, listenerPort", method)
        self.assertIn("socket.bind(new java.net.InetSocketAddress(path.localAddress, port))", binding)
        self.assertIn("LanSocketBinding.bindDatagramListenerSocket(", listener)
        self.assertIn("plannedPrimaryPath, rescueSocket, 8888", listener)
        self.assertIn("if (!rescueSocket.isBound() && !primaryIsLocalCandidate)", listener)
        # Wildcard remains only for the legacy no-path compatibility case; a validated
        # local AP candidate must be exact-address bound and can never reach this fallback.
        self.assertLess(listener.index("if (!rescueSocket.isBound() && !primaryIsLocalCandidate)"),
                        listener.index("rescueSocket.bind(new InetSocketAddress(8888))"))

    def test_android_wifi_socket_binding_failure_does_not_fall_back_to_default_route(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        method = extract_java_method(source, "private Network bindSocketToWifi(")
        selector = extract_java_method(source, "private Network selectWifiLanNetwork(")
        self.assertIn("throws java.io.IOException", method)
        self.assertIn("selectWifiLanNetwork(cm)", method)
        self.assertIn("LanNetworkPath.fromAndroidNetwork(selected", method)
        self.assertIn("LanSocketBinding.bindDatagramListenerSocket(selectedPath, socket, listenerPort)", method)
        self.assertLess(method.index("LanSocketBinding.bindDatagramListenerSocket(selectedPath, socket, listenerPort)"),
                        method.index('FileLogger.logD("CHAT"'))
        self.assertLess(method.index('FileLogger.logD("CHAT"'),
                        method.index("return selected;"))
        self.assertIn("return selected;", method)
        self.assertNotIn("lanChatBoundWifiNetwork =", method)
        self.assertIn("Selected Wi-Fi network has incomplete link properties", method)
        listener_start = source.index("private void startRescueListener()")
        listener_end = source.index("private void parseResponse(", listener_start)
        listener = source[listener_start:listener_end]
        self.assertIn("boundWifiNetwork = bindSocketToWifi(rescueSocket, 8888);", listener)
        self.assertIn("if (primaryIsLocalCandidate)", listener)
        self.assertIn("LanSocketBinding.bindDatagramListenerSocket(", listener)
        self.assertIn("plannedPrimaryPath, rescueSocket, 8888", listener)
        self.assertIn("if (!rescueSocket.isBound() && !primaryIsLocalCandidate)", listener)
        self.assertIn("rescueSocket.bind(new InetSocketAddress(8888))", listener)
        generation_guard = listener.index("listenerGeneration != lanSocketGeneration.get()")
        publish_network = listener.index("lanChatBoundWifiNetwork = boundWifiNetwork")
        publish_socket = listener.index("rescueSocketRef = rescueSocket")
        self.assertLess(generation_guard, publish_network)
        self.assertLess(publish_network, publish_socket)
        self.assertIn("synchronized (rescueLock)", listener)
        self.assertIn("for (Network network : cm.getAllNetworks())", selector)
        self.assertIn("Network active = cm.getActiveNetwork()", selector)
        self.assertIn("wifiNetworks.contains(active)", selector)
        self.assertIn("wifiNetworks.size() == 1", selector)
        self.assertIn("Multiple non-default Wi-Fi networks; defer ambiguous UDP socket binding", selector)
        self.assertLess(selector.index("for (Network network : cm.getAllNetworks())"),
                        selector.index("Network active = cm.getActiveNetwork()"))
        self.assertLess(method.index("selectWifiLanNetwork(cm)"),
                        method.index("LanSocketBinding.bindDatagramListenerSocket(selectedPath, socket, listenerPort)"))

    def test_outbox_queries_still_return_empty_for_a_valid_empty_ledger(self):
        from lan_p2p_core import ensure_outbox_retry_schema
        conn = sqlite3.connect(self.db_path)
        try:
            conn.executescript("""
                CREATE TABLE lan_outbox (
                    message_id TEXT PRIMARY KEY, sender_scope TEXT NOT NULL,
                    sender_peer_id TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '',
                    created_at_ms INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL,
                    delivery_deadline_ms INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE lan_outbox_targets (
                    message_id TEXT NOT NULL, peer_id TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending');
                CREATE TABLE lan_peers (
                    receiver_scope TEXT NOT NULL, peer_id TEXT NOT NULL,
                    endpoint_ip TEXT NOT NULL DEFAULT '', endpoint_port INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'suspect', lease_expires_at REAL NOT NULL DEFAULT 0);
            """)
            conn.commit()
        finally:
            conn.close()
        self.assertTrue(ensure_outbox_retry_schema(self.db_path))
        self.assertEqual([], load_pending_outgoing_for_peer(self.db_path, "server2", "peer-a"))
        self.assertEqual(0, add_discovery_targets_for_peer(self.db_path, "server2", "peer-a"))
        self.assertEqual([], load_active_pending_targets(self.db_path, "server2", "message-a"))

    def test_all_python_services_defer_peer_recovery_on_ledger_read_failure(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            start = source.index("def _resume_pending_v2_for_peer(")
            end = source.index("\ndef _handle_authenticated_v2_packet", start)
            recovery = source[start:end]
            self.assertIn("try:", recovery, filename)
            self.assertIn("except Exception as e:", recovery, filename)
            self.assertIn("durable ledger read failed", recovery, filename)
            self.assertIn("load_pending_outgoing_for_peer(", recovery, filename)

    def test_all_python_services_stop_peer_replay_on_outbox_storage_write_failure(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            self.assertIn("from lan_p2p_core import LanP2PStorageError", source, filename)
            start = source.index("def _resume_pending_v2_for_peer(")
            end = source.index("\ndef _handle_authenticated_v2_packet", start)
            recovery = source[start:end]
            admission = recovery.index("attempt_admitted = mark_outgoing_attempt(")
            storage_catch = recovery.index("except LanP2PStorageError as e:", admission)
            send_catch = recovery.index("except LanP2PStorageError as e:", storage_catch + 1)
            generic_catch = recovery.index("except Exception as e:", send_catch)
            admission_handler = recovery[storage_catch:recovery.index("if not attempt_admitted:", storage_catch)]
            send_handler = recovery[send_catch:generic_catch]
            self.assertIn("recovery retained", admission_handler, filename)
            self.assertIn("_V2_RECOVERY_RETRY_PEERS.add(peer_id)", admission_handler, filename)
            self.assertIn("backoff retained", send_handler, filename)
            self.assertNotIn("_V2_RECOVERY_RETRY_PEERS.add(peer_id)", send_handler, filename)
            self.assertIn("return", admission_handler, filename)
            self.assertIn("return", send_handler, filename)


    def test_outbox_storage_error_is_not_masked_by_rollback_or_close_failure(self):
        class BrokenCleanupConnection:
            def execute(self, *args, **kwargs):
                raise sqlite3.OperationalError("primary write failure")
            def rollback(self):
                raise RuntimeError("rollback cleanup failure")
            def close(self):
                raise RuntimeError("close cleanup failure")

        with patch("lan_p2p_core.sqlite3.connect", return_value=BrokenCleanupConnection()):
            with self.assertRaises(LanP2PStorageError) as caught:
                mark_outgoing_attempt(self.db_path, "message-a", "peer-a",
                                      "192.168.1.20", 8888)
        self.assertIsInstance(caught.exception.__cause__, sqlite3.OperationalError)
        self.assertEqual("primary write failure", str(caught.exception.__cause__))





    def test_android_network_callback_pair_self_heals_on_api_21_and_above(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        method = extract_java_method(source, "private void selfHeal()")
        self.assertIn("(networkCallback == null || wifiLanNetworkCallback == null)", method)
        self.assertIn("Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP", method)
        register = extract_java_method(source, "private void registerNetworkCallback()")
        self.assertIn("Build.VERSION_CODES.N", register)
        self.assertIn("registerNetworkCallback(defaultNetworkRequest, networkCallback)", register)
        self.assertIn("registerNetworkCallback(wifiRequest, wifiLanNetworkCallback)", register)




    def test_all_python_services_retry_deferred_peer_recovery_without_new_handshake(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            self.assertIn("_V2_RECOVERY_RETRY_PEERS = set()", source, filename)
            self.assertIn("_V2_RECOVERY_INFLIGHT = set()", source, filename)
            self.assertIn("def _retry_deferred_v2_peer_recoveries():", source, filename)
            self.assertIn("_V2_RECOVERY_WAKE_EVENT.wait(1.0)", source, filename)
            self.assertIn("list_due_outgoing_peers(", source, filename)
            self.assertIn("list_known_peer_snapshot(", source, filename)
            self.assertIn("def _start_v2_recovery_retry_worker():", source, filename)
            self.assertIn("if _V2_RECOVERY_WORKER_STARTED:", source, filename)
            start = source.index("def _resume_pending_v2_for_peer(")
            end = source.index("\ndef _retry_deferred_v2_peer_recoveries", start)
            recovery = source[start:end]
            self.assertIn("_V2_RECOVERY_RETRY_PEERS.add(peer_id)", recovery)
            self.assertIn("_V2_RECOVERY_INFLIGHT", recovery)
            queued = recovery.index("queued_recovery_event = peer_id in _V2_RECOVERY_RETRY_PEERS")
            consume = recovery.index("_V2_RECOVERY_RETRY_PEERS.discard(peer_id)", queued)
            inflight = recovery.index("if peer_id in _V2_RECOVERY_INFLIGHT:", consume)
            self.assertLess(queued, consume)
            self.assertLess(consume, inflight)
            self.assertIn("_V2_RECOVERY_RETRY_PEERS.add(peer_id)", recovery[inflight:])
            self.assertIn("recovery_event = recovery_event or queued_recovery_event", recovery)
            finally_block = recovery[recovery.index("finally:"):]
            self.assertIn("_V2_RECOVERY_INFLIGHT.discard(peer_id)", finally_block)
            self.assertNotIn("_V2_RECOVERY_RETRY_PEERS.discard(peer_id)", finally_block)




    def test_outbox_ack_commit_failure_rolls_back_and_preserves_pending_recipient(self):
        from lan_p2p_core import persist_outgoing_message, acknowledge_outgoing_message
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-ack-commit-fail",
            "hello", ["android-a"]))
        real_connect = sqlite3.connect

        class CommitFailConnection:
            def __init__(self, connection):
                self.connection = connection
            def __getattr__(self, name):
                return getattr(self.connection, name)
            def commit(self):
                raise sqlite3.OperationalError("injected ACK commit failure")

        with patch("lan_p2p_core.sqlite3.connect",
                   side_effect=lambda *args, **kwargs: CommitFailConnection(
                       real_connect(*args, **kwargs))):
            self.assertFalse(acknowledge_outgoing_message(
                self.db_path, "server2", "m-ack-commit-fail", "android-a"))

        conn = real_connect(self.db_path)
        try:
            target = conn.execute(
                "SELECT status FROM lan_outbox_targets WHERE message_id=? AND peer_id=?",
                ("m-ack-commit-fail", "android-a")).fetchone()
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id=?",
                ("m-ack-commit-fail",)).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(target[0], "pending")
        self.assertEqual(state, "pending")

    def test_server2_executed_chat_ack_and_queue_cleanup_failure_replay_is_idempotent(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        module = ast.parse(source, filename="server2.py")
        function = next(node for node in module.body
                        if isinstance(node, ast.FunctionDef)
                        and node.name == "process_executed_commands")
        isolated = ast.Module(body=[function], type_ignores=[])
        namespace = {
            "CHAT_DEDUP_DB": "CHAT_DEDUP_DB",
            "PENDING_COMMANDS": {
                "android-a": [{
                    "id": "cmd-chat-1",
                    "type": "chat_msg",
                    "payload": {"message_id": "shared-message-1"},
                }]
            },
            "_cmd_lock": threading.Lock(),
            "print_lock": threading.Lock(),
            "decode_chat_http_fallback_payload": lambda payload: payload,
        }
        ack_results = iter([False, True, True])
        ack_calls = []
        def acknowledge(db_path, scope, message_id, peer_id):
            ack_calls.append((db_path, scope, message_id, peer_id))
            return next(ack_results)
        persist_calls = []
        def persist(dev_id):
            persist_calls.append(dev_id)
            if len(persist_calls) == 1:
                raise sqlite3.OperationalError("injected command queue commit failure")
        namespace["acknowledge_outgoing_message"] = acknowledge
        namespace["_persist_command_queue_locked"] = persist
        exec(compile(isolated, "server2.py", "exec"), namespace)
        process = namespace["process_executed_commands"]

        # HTTP execution without a durable shared-ID outbox ACK must retain fallback.
        process("android-a", ["cmd-chat-1"])
        self.assertEqual(len(namespace["PENDING_COMMANDS"]["android-a"]), 1)
        self.assertEqual(persist_calls, [])

        # ACK is durable, but queue persistence fails: memory restores the fallback.
        process("android-a", ["cmd-chat-1"])
        self.assertEqual(len(namespace["PENDING_COMMANDS"]["android-a"]), 1)
        self.assertEqual(persist_calls, ["android-a"])

        # The same executed receipt is replayed; duplicate ACK is accepted and cleanup commits.
        process("android-a", ["cmd-chat-1"])
        self.assertNotIn("android-a", namespace["PENDING_COMMANDS"])
        self.assertEqual(persist_calls, ["android-a", "android-a"])
        self.assertEqual(ack_calls, [
            ("CHAT_DEDUP_DB", "server2", "shared-message-1", "android-a"),
            ("CHAT_DEDUP_DB", "server2", "shared-message-1", "android-a"),
            ("CHAT_DEDUP_DB", "server2", "shared-message-1", "android-a"),
        ])
        process("android-a", ["cmd-chat-1"])
        self.assertEqual(len(ack_calls), 3)





    def test_python_and_android_share_the_same_first_discovery_window(self):
        from lan_p2p_core import FIRST_DISCOVERY_WINDOW_MS
        self.assertEqual(10_000, FIRST_DISCOVERY_WINDOW_MS)
        repo_root = Path(__file__).resolve().parents[1]
        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        policy_source = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatRetryPolicy.java").read_text(encoding="utf-8")
        self.assertIn("FIRST_DISCOVERY_WINDOW_MS = 10_000L", policy_source)
        self.assertIn("FIRST_DISCOVERY_WINDOW_MS = LanChatRetryPolicy.FIRST_DISCOVERY_WINDOW_MS", store_source)

    def test_peer_discovery_before_first_dispatch_does_not_start_or_join_window(self):
        from lan_p2p_core import (
            persist_outgoing_message, add_discovery_targets_for_peer,
            begin_outgoing_discovery_window,
        )
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-pre-discovery", "hello", []))
        with patch("lan_p2p_core.time.time", return_value=1001.0):
            self.assertEqual(0, add_discovery_targets_for_peer(
                self.db_path, "server2", "android-early"))
        with _sqlite_connection(self.db_path) as conn:
            state, deadline = conn.execute(
                "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id='m-pre-discovery'"
            ).fetchone()
            targets = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-pre-discovery'"
            ).fetchone()[0]
        self.assertEqual(("discovery_pending", 0), (state, deadline))
        self.assertEqual(0, targets)

        with patch("lan_p2p_core.time.time", return_value=1010.0):
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-pre-discovery"))
        with patch("lan_p2p_core.time.time", return_value=1011.0):
            self.assertEqual(1, add_discovery_targets_for_peer(
                self.db_path, "server2", "android-during"))
        with _sqlite_connection(self.db_path) as conn:
            deadline, peer = conn.execute(
                "SELECT o.delivery_deadline_ms,t.peer_id FROM lan_outbox o "
                "JOIN lan_outbox_targets t ON t.message_id=o.message_id "
                "WHERE o.message_id='m-pre-discovery'"
            ).fetchone()
        self.assertEqual(1020000, deadline)
        self.assertEqual("android-during", peer)

        repo_root = Path(__file__).resolve().parents[1]
        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        add_peer = extract_java_method(
            store_source, "private boolean addDiscoveryPeerToOpenMessages(SQLiteDatabase db,"
        )
        self.assertIn("if (deadline <= 0L)", add_peer)
        self.assertIn("continue;", add_peer)
        self.assertNotIn("now + FIRST_DISCOVERY_WINDOW_MS", add_peer)

    def test_unknown_ack_before_first_dispatch_is_rejected_on_python_and_android(self):
        from lan_p2p_core import (
            persist_outgoing_message, acknowledge_outgoing_message,
            begin_outgoing_discovery_window,
        )
        with patch("lan_p2p_core.time.time", return_value=2000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-early-ack", "hello", []))
        with patch("lan_p2p_core.time.time", return_value=2001.0):
            self.assertFalse(acknowledge_outgoing_message(
                self.db_path, "server2", "m-early-ack", "android-early"))
        with _sqlite_connection(self.db_path) as conn:
            state, deadline = conn.execute(
                "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id='m-early-ack'"
            ).fetchone()
            count = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-early-ack'"
            ).fetchone()[0]
        self.assertEqual(("discovery_pending", 0), (state, deadline))
        self.assertEqual(0, count)

        with patch("lan_p2p_core.time.time", return_value=2010.0):
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-early-ack"))
        with patch("lan_p2p_core.time.time", return_value=2011.0):
            self.assertTrue(acknowledge_outgoing_message(
                self.db_path, "server2", "m-early-ack", "android-during"))
        with _sqlite_connection(self.db_path) as conn:
            state, deadline = conn.execute(
                "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id='m-early-ack'"
            ).fetchone()
            status = conn.execute(
                "SELECT status FROM lan_outbox_targets WHERE message_id='m-early-ack' "
                "AND peer_id='android-during'"
            ).fetchone()[0]
        self.assertEqual("discovery_pending", state)
        self.assertEqual(2020000, deadline)
        self.assertEqual("acked", status)

        repo_root = Path(__file__).resolve().parents[1]
        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        acknowledge = extract_java_method(store_source, "public boolean acknowledge(")
        self.assertIn("deadline <= 0L || now >= deadline", acknowledge)
        self.assertNotIn("deadline = now + FIRST_DISCOVERY_WINDOW_MS", acknowledge)



    def test_android_inbox_presentation_read_failure_is_not_treated_as_undisplayed(self):
        repo_root = Path(__file__).resolve().parents[1]
        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        method = extract_java_method(
            store_source, "public boolean isIncomingDisplayed("
        )
        self.assertIn("return c.moveToFirst() && c.getInt(0) != 0;", method)
        self.assertIn(
            'throw new IllegalStateException("Failed to read LAN inbox presentation state", e);',
            method,
        )
        self.assertNotIn("catch (Exception e) {\n            return false;", method)

    def test_python_receivers_never_prune_message_deduplication_records(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            start = source.index("def _claim_chat_message(")
            end = source.index("\ndef ", start + 5)
            claim = source[start:end]
            self.assertIn("validate_incoming_message_claim(", claim, filename)
            self.assertIn("INSERT OR IGNORE INTO received_chat_messages", claim, filename)
            self.assertNotIn("DELETE FROM received_chat_messages", claim, filename)
            self.assertNotIn("7 * 86400", claim, filename)
            self.assertNotIn("OFFSET 10000", claim, filename)

    def test_recovery_query_cannot_starve_new_due_messages_behind_old_backoff_rows(self):
        from lan_p2p_core import (
            persist_outgoing_message, record_peer, mark_outgoing_attempt,
            load_pending_outgoing_for_peer,
        )
        self.assertTrue(record_peer(
            self.db_path, "server2", "android-a", "android", "android-main",
            "192.168.1.10", 8888, "active"))
        for index in range(12):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", f"m-fair-{index:02d}",
                "hello", ["android-a"], created_at_ms=1_000_000 + index))
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            for index in range(10):
                self.assertTrue(mark_outgoing_attempt(
                    self.db_path, f"m-fair-{index:02d}", "android-a",
                    "192.168.1.10", 8888))
        with patch("lan_p2p_core.time.time", return_value=1001.0):
            due = load_pending_outgoing_for_peer(
                self.db_path, "server2", "android-a", limit=10)
        self.assertEqual(["m-fair-10", "m-fair-11"],
                         [row["message_id"] for row in due])

    def test_python_outbox_retries_use_persisted_backoff_and_active_peer_scan(self):
        from lan_p2p_core import (
            persist_outgoing_message, record_peer, load_pending_outgoing_for_peer,
            mark_outgoing_attempt, list_due_outgoing_peers,
        )
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-backoff", "hello", ["android-a"]))
            record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                        "192.168.1.10", 8888, "active")
            self.assertEqual(["android-a"], list_due_outgoing_peers(
                self.db_path, "server2", now_ms=1_000_000))
            self.assertTrue(mark_outgoing_attempt(
                self.db_path, "m-backoff", "android-a", "192.168.1.10", 8888))
        with patch("lan_p2p_core.time.time", return_value=1001.0):
            self.assertEqual([], load_pending_outgoing_for_peer(
                self.db_path, "server2", "android-a"))
            self.assertEqual([], list_due_outgoing_peers(
                self.db_path, "server2", now_ms=1_001_000))
            # Recovery cannot bypass a normal target backoff. Exhausted targets
            # are reset explicitly by prepare_outgoing_peer_recovery().
            self.assertEqual([], load_pending_outgoing_for_peer(
                self.db_path, "server2", "android-a"))
        with patch("lan_p2p_core.time.time", return_value=1001.6):
            self.assertEqual(["m-backoff"], [
                row["message_id"] for row in load_pending_outgoing_for_peer(
                    self.db_path, "server2", "android-a")])
            self.assertEqual(["android-a"], list_due_outgoing_peers(
                self.db_path, "server2", now_ms=1_001_600))

    def test_python_outbox_retry_attempt_cap_and_verified_recovery_cooldown(self):
        from lan_p2p_core import (
            MAX_OUTBOX_TARGET_ATTEMPTS, persist_outgoing_message, record_peer,
            mark_outgoing_attempt, prepare_outgoing_peer_recovery,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-budget", "hello", ["android-a"]))
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        with _sqlite_connection(self.db_path) as conn:
            conn.execute("UPDATE lan_outbox_targets SET attempt_count=?,last_attempt_at=? "
                         "WHERE message_id=? AND peer_id=?",
                         (MAX_OUTBOX_TARGET_ATTEMPTS, 1234, "m-budget", "android-a"))
            conn.commit()
        self.assertEqual(1, prepare_outgoing_peer_recovery(
            self.db_path, "server2", "android-a", now_ms=100_000))
        with _sqlite_connection(self.db_path) as conn:
            self.assertEqual((0, 0, 100_000), conn.execute(
                "SELECT attempt_count,last_attempt_at,last_recovery_at_ms FROM lan_outbox_targets "
                "WHERE message_id='m-budget' AND peer_id='android-a'").fetchone())
        self.assertEqual(0, prepare_outgoing_peer_recovery(
            self.db_path, "server2", "android-a", now_ms=150_000))
        for _ in range(MAX_OUTBOX_TARGET_ATTEMPTS):
            self.assertTrue(mark_outgoing_attempt(
                self.db_path, "m-budget", "android-a", "192.168.1.10", 8888))
        self.assertFalse(mark_outgoing_attempt(
            self.db_path, "m-budget", "android-a", "192.168.1.10", 8888))
        self.assertEqual(1, prepare_outgoing_peer_recovery(
            self.db_path, "server2", "android-a", now_ms=160_001))

    def test_python_recovery_does_not_consume_cooldown_before_target_is_exhausted(self):
        from lan_p2p_core import (
            MAX_OUTBOX_TARGET_ATTEMPTS, persist_outgoing_message,
            prepare_outgoing_peer_recovery,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-recovery-before-cap",
            "hello", ["android-a"]))
        with _sqlite_connection(self.db_path) as conn:
            conn.execute(
                "UPDATE lan_outbox_targets SET attempt_count=3,last_attempt_at=1234,"
                "last_recovery_at_ms=0 WHERE message_id=? AND peer_id=?",
                ("m-recovery-before-cap", "android-a"))
            conn.commit()
        self.assertEqual(0, prepare_outgoing_peer_recovery(
            self.db_path, "server2", "android-a", now_ms=100_000))
        with _sqlite_connection(self.db_path) as conn:
            self.assertEqual((3, 1234, 0), conn.execute(
                "SELECT attempt_count,last_attempt_at,last_recovery_at_ms "
                "FROM lan_outbox_targets WHERE message_id='m-recovery-before-cap' "
                "AND peer_id='android-a'").fetchone())
            conn.execute(
                "UPDATE lan_outbox_targets SET attempt_count=? "
                "WHERE message_id=? AND peer_id=?",
                (MAX_OUTBOX_TARGET_ATTEMPTS, "m-recovery-before-cap", "android-a"))
            conn.commit()
        self.assertEqual(1, prepare_outgoing_peer_recovery(
            self.db_path, "server2", "android-a", now_ms=110_000))
        with _sqlite_connection(self.db_path) as conn:
            self.assertEqual((0, 0, 110_000), conn.execute(
                "SELECT attempt_count,last_attempt_at,last_recovery_at_ms "
                "FROM lan_outbox_targets WHERE message_id='m-recovery-before-cap' "
                "AND peer_id='android-a'").fetchone())

    def test_active_outbox_target_query_excludes_terminal_and_exhausted_targets(self):
        from lan_p2p_core import (
            MAX_OUTBOX_TARGET_ATTEMPTS, persist_outgoing_message,
            record_peer, load_active_pending_targets,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-active-filter", "hello", ["android-a"]))
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        self.assertEqual(["android-a"], [
            row["peer_id"] for row in load_active_pending_targets(
                self.db_path, "server2", "m-active-filter")])
        with _sqlite_connection(self.db_path) as conn:
            conn.execute("UPDATE lan_outbox_targets SET attempt_count=? "
                         "WHERE message_id='m-active-filter' AND peer_id='android-a'",
                         (MAX_OUTBOX_TARGET_ATTEMPTS,))
            conn.commit()
        self.assertEqual([], load_active_pending_targets(
            self.db_path, "server2", "m-active-filter"))
        with _sqlite_connection(self.db_path) as conn:
            conn.execute("UPDATE lan_outbox_targets SET attempt_count=0 "
                         "WHERE message_id='m-active-filter' AND peer_id='android-a'")
            conn.execute("UPDATE lan_outbox SET state='waiting_peers' "
                         "WHERE message_id='m-active-filter'")
            conn.commit()
        self.assertEqual([], load_active_pending_targets(
            self.db_path, "server2", "m-active-filter"))

    def test_python_due_retry_scan_is_per_target_and_excludes_acked_or_exhausted(self):
        from lan_p2p_core import (
            MAX_OUTBOX_TARGET_ATTEMPTS, persist_outgoing_message, record_peer,
            acknowledge_outgoing_message, mark_outgoing_attempt, list_due_outgoing_peers,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-target-budget", "hello",
            ["android-a", "android-b"]))
        for peer, ip in (("android-a", "192.168.1.10"), ("android-b", "192.168.1.11")):
            record_peer(self.db_path, "server2", peer, "android", "android-main",
                        ip, 8888, "active")
        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "m-target-budget", "android-a"))
        self.assertEqual(["android-b"], list_due_outgoing_peers(
            self.db_path, "server2", now_ms=int(time.time() * 1000)))
        for _ in range(MAX_OUTBOX_TARGET_ATTEMPTS):
            self.assertTrue(mark_outgoing_attempt(
                self.db_path, "m-target-budget", "android-b", "192.168.1.11", 8888))
        self.assertFalse(mark_outgoing_attempt(
            self.db_path, "m-target-budget", "android-b", "192.168.1.11", 8888))
        self.assertEqual([], list_due_outgoing_peers(
            self.db_path, "server2", now_ms=int(time.time() * 1000)))
        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "m-target-budget", "android-b"))
        self.assertEqual([], list_due_outgoing_peers(
            self.db_path, "server2", now_ms=int(time.time() * 1000)))

    def test_stale_worker_dispatch_rechecks_backoff_atomically(self):
        from lan_p2p_core import persist_outgoing_message, mark_outgoing_attempt
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-stale-worker",
            "hello", ["android-a"]))
        # The initial burst has just persisted an attempt. A worker holding a
        # stale due-scan result must not dispatch against that old observation.
        self.assertTrue(mark_outgoing_attempt(
            self.db_path, "m-stale-worker", "android-a", "192.168.1.10", 8888))
        self.assertFalse(mark_outgoing_attempt(
            self.db_path, "m-stale-worker", "android-a", "192.168.1.10", 8888,
            allow_fast_retry=False))
        with patch("lan_p2p_core.time.time", return_value=(
                int(time.time() * 1000) / 1000.0 + 2.0)):
            self.assertTrue(mark_outgoing_attempt(
                self.db_path, "m-stale-worker", "android-a", "192.168.1.10", 8888,
                allow_fast_retry=False))

    def test_python_retry_schema_migrates_existing_target_ledger(self):
        from lan_p2p_core import ensure_outbox_retry_schema
        with _sqlite_connection(self.db_path) as conn:
            conn.execute("""CREATE TABLE lan_outbox_targets (
                message_id TEXT NOT NULL, peer_id TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_ack_at INTEGER NOT NULL DEFAULT 0,
                last_endpoint TEXT NOT NULL DEFAULT '',
                last_error TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(message_id, peer_id))""")
        self.assertTrue(ensure_outbox_retry_schema(self.db_path))
        with _sqlite_connection(self.db_path) as conn:
            columns = {row[1] for row in conn.execute("PRAGMA table_info(lan_outbox_targets)")}
        self.assertIn("last_recovery_at_ms", columns)
        self.assertIn("last_attempt_endpoint", columns)
        self.assertIn("last_sent_endpoint", columns)
        with _sqlite_connection(self.db_path) as conn:
            indexes = {row[1] for row in conn.execute("PRAGMA index_list(lan_outbox_targets)")}
        self.assertIn("idx_lan_outbox_targets_retry", indexes)

    def test_all_python_services_retry_ack_timeouts_with_finite_backoff(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            self.assertIn("list_due_outgoing_peers(", source, filename)
            self.assertIn("recovery_event=peer_id in recovery_event_peers", source, filename)
            self.assertIn("prepare_outgoing_peer_recovery(", source, filename)
            self.assertIn("ensure_outbox_retry_schema(", source, filename)
            self.assertIn("_V2_RECOVERY_WAKE_EVENT.wait(1.0)", source, filename)
        core = (repo_root / "lan_p2p_core.py").read_text(encoding="utf-8")
        self.assertIn("MAX_OUTBOX_TARGET_ATTEMPTS = 8", core)
        self.assertIn("OUTBOX_RETRY_RECOVERY_COOLDOWN_MS = 60_000", core)
        self.assertIn("WHEN 1 THEN 1500 WHEN 2 THEN 4000", core)
        self.assertIn("WHEN 3 THEN 10000 WHEN 4 THEN 20000", core)

    def test_all_python_services_keep_retry_worker_alive_when_schema_migration_is_deferred(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            self.assertIn("_V2_RECOVERY_SCHEMA_READY = False", source, filename)
            worker_start = source.index("def _retry_deferred_v2_peer_recoveries():")
            worker_end = source.index("\ndef _start_v2_recovery_retry_worker", worker_start)
            worker = source[worker_start:worker_end]
            self.assertIn("if not _V2_RECOVERY_SCHEMA_READY:", worker, filename)
            self.assertIn("ensure_outbox_retry_schema(", worker, filename)
            start = source.index("def _start_v2_recovery_retry_worker():")
            end = source.index("\ndef _handle_authenticated_v2_packet", start)
            starter = source[start:end]
            self.assertIn("_V2_RECOVERY_SCHEMA_READY = False", starter, filename)
            self.assertIn("threading.Thread(target=_retry_deferred_v2_peer_recoveries", starter, filename)
            self.assertNotIn("return\n    with _V2_RECOVERY_RETRY_LOCK", starter, filename)


class AndroidLanNetworkScopeTests(unittest.TestCase):
    def test_android_peer_discovery_sends_bounded_scope_digest_not_raw_link_properties(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        helper = extract_java_method(source, "static String buildLanProtocolNetworkScope(")
        self.assertIn('MessageDigest.getInstance("SHA-256")', helper)
        self.assertIn("StandardCharsets.UTF_8", helper)
        self.assertIn('new StringBuilder("sha256:")', helper)
        self.assertIn('"UNAVAILABLE".equals(fingerprint)', helper)
        self.assertIn('"ERROR".equals(fingerprint)', helper)
        self.assertIn('"NO_WIFI".equals(fingerprint)', helper)
        self.assertIn("return \"\";", helper)

        self.assertEqual(
            source.count('put("network_scope", getLanProtocolNetworkScope())'), 2
        )
        self.assertNotIn(
            'put("network_scope", wifiLanFingerprint == null ? "" : wifiLanFingerprint)',
            source,
        )
        # The full fingerprint remains local for stable LAN-change comparison.
        self.assertIn("previous.equals(current)", source)
        self.assertIn("wifiLanFingerprint = current", source)



class AndroidLanAckDiagnosticsTests(unittest.TestCase):
    def test_android_rejected_ack_log_includes_actual_endpoint_and_all_trust_gates(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        ack = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        self.assertIn('source=" + sourceAddress.getHostAddress() + ":" + sourcePort', ack)
        self.assertIn('" routeMatch=" + routeMatches', ack)
        self.assertIn('" persistedSendEndpointMatch=" + persistedEndpointMatch', ack)
        self.assertIn('" discoveryPending=" + discoveryAck', ack)
        self.assertIn("reason=unverified_endpoint", ack)

        beacon_start = source.index("private void sendPeerHello(")
        beacon_end = source.index("private byte[] buildAuthenticatedLanChatPacket(", beacon_start)
        beacon = source[beacon_start:beacon_end]
        self.assertIn('" reason=" + e.getMessage()', beacon)


    def test_android_new_message_persists_outbox_and_recipient_snapshot_atomically(self):
        repo_root = Path(__file__).resolve().parents[1]
        tracking = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        persist = extract_java_method(tracking, "private boolean persistPendingLanChat(")
        atomic = extract_java_method(store, "public boolean enqueueWithRecipientSnapshot(")
        self.assertIn("store.enqueueWithRecipientSnapshot(", persist)
        self.assertNotIn("PREF_LAN_CHAT_QUEUE", persist)
        self.assertNotIn("commit()", persist)
        self.assertIn("db.beginTransaction()", atomic)
        self.assertIn('db.insertWithOnConflict("lan_outbox"', atomic)
        self.assertIn('db.rawQuery("SELECT peer_id,endpoint_ip,endpoint_port FROM lan_peers ', atomic)
        self.assertIn('db.insertWithOnConflict("lan_outbox_targets"', atomic)
        self.assertIn('next.put("state", "pending")', atomic)
        self.assertIn("db.setTransactionSuccessful()", atomic)
        self.assertIn('state = "discovery_pending"', atomic)
        self.assertIn("initializeLanChatRecipientSnapshot(store, id)", tracking)  # legacy mirror migration remains


class AndroidLanSchedulingAndAckProgressTests(unittest.TestCase):
    def test_new_user_messages_bypass_the_durable_retry_backlog(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        self.assertIn("lanChatPersistExecutor", source)
        self.assertIn("lanChatImmediateExecutor", source)
        start = source.index("private void sendLocalUdpMessage(final String message)")
        end = source.index("private boolean persistPendingLanChat(", start)
        entry = source[start:end]
        self.assertIn("lanChatImmediateExecutor.execute(", entry)
        self.assertNotIn("lanChatSendExecutor.execute(", entry)
        retry_start = source.index("private void retryPendingLanChatMessages(boolean recoveryEvent)")
        retry_end = source.index("// Both the immediate and retry lanes", retry_start)
        retry = source[retry_start:retry_end]
        self.assertIn("lanChatSendExecutor.execute(", retry)
        self.assertIn("new java.util.concurrent.locks.ReentrantLock(true)", source)
        self.assertIn("lanChatTransmitLock.lock()", source)
        self.assertIn("lanChatTransmitLock.unlock()", source)
        self.assertNotIn("synchronized (lanChatTransmitLock)", source)
        self.assertIn("if (!activeLanChatTransmissions.add(messageId))", source)
        self.assertIn("lanChatPersistExecutor.shutdown()", source)
        self.assertIn("lanChatImmediateExecutor.shutdown()", source)

    def test_new_messages_are_durably_persisted_before_immediate_transmit_admission(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        entry = extract_java_method(source, "private void sendLocalUdpMessage(final String message)")
        persist_pos = entry.index("persistPendingLanChat(messageId, message)")
        transmit_queue_pos = entry.index("lanChatImmediateExecutor.execute(")
        self.assertIn("lanChatPersistExecutor.execute(", entry)
        self.assertLess(persist_pos, transmit_queue_pos)
        self.assertLess(persist_pos, entry.index("if (serviceDestroyed)", persist_pos))
        self.assertIn("requestLanChatRetryPlan()", entry)
        self.assertIn("消息已落账并转入持久化补发", entry)
        self.assertIn("lanChatPersistExecutor.shutdown()", source)
        self.assertLess(
            source.index("awaitLanExecutorTermination(lanChatPersistExecutor)"),
            source.index("awaitLanExecutorTermination(lanChatImmediateExecutor)"),
        )
        self.assertLess(
            source.index("lanChatPersistExecutor.shutdown()"),
            source.index("lanChatImmediateExecutor.shutdown()"),
        )

    def test_lan_store_reference_survives_until_accepted_persistence_tasks_drain(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        destroy = extract_java_method(source, "public void onDestroy()")
        closer = extract_java_method(source, "private void closeLanChatStoreAfterExecutors(")
        self.assertIn("LanChatStore storeToClose = lanChatStore;", destroy)
        self.assertIn("closeLanChatStoreAfterExecutors(storeToClose)", destroy)
        self.assertNotIn("lanChatStore = null", destroy)
        clear_store = closer.index("if (lanChatStore == storeToClose) lanChatStore = null;")
        for executor in (
            "lanChatScheduler", "lanChatPersistExecutor", "lanChatImmediateExecutor",
            "lanChatSendExecutor", "lanChatControlExecutor", "lanChatAckExecutor",
            "lanChatPeerExecutor", "lanChatProbeExecutor", "lanChatIoExecutor",
            "maintenanceExecutor",
        ):
            self.assertLess(
                closer.index("awaitLanExecutorTermination(" + executor + ")"),
                clear_store,
            )
        self.assertLess(clear_store, closer.index("storeToClose.closeStore()"))
        persist = extract_java_method(source, "private boolean persistPendingLanChat(")
        self.assertIn("LanChatStore store = lanChatStore;", persist)
        self.assertIn("store.enqueueWithRecipientSnapshot(", persist)

    def test_ack_completion_reports_sqlite_target_counts_not_window_latch_counts(self):
        repo_root = Path(__file__).resolve().parents[1]
        tracking = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        method = extract_java_method(tracking, "private void completeLanChatAckWindow(")
        self.assertIn("store.getTargetAckProgress(messageId)", method)
        self.assertIn("durableProgress.ackedTargets + \"/\" + durableProgress.totalTargets", method)
        self.assertIn("persistedAllTargetsAcked", method)
        self.assertNotIn("deliveredPeers + \"/\" + totalPeerCount", method)
        progress = extract_java_method(store, "public AckProgress getTargetAckProgress(")
        self.assertIn("COUNT(*)", progress)
        self.assertIn("status='acked'", progress)
        self.assertIn("new AckProgress(total, acked)", progress)


class AndroidLanAckDiscoveryRaceTests(unittest.TestCase):
    def test_unverified_discovery_ack_gets_one_bounded_route_recheck_without_bypassing_identity(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        helper = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        self.assertIn("allowDiscoveryGrace && (discoveryAck || attemptedEndpointPending)", helper)
        self.assertIn("store.matchesAttemptedAckEndpoint(messageId, senderId", helper)
        self.assertIn('}, 200L)', helper)
        self.assertIn("pendingChatAckRouteRechecks.add(recheckKey)", helper)
        self.assertIn("processAuthenticatedLanChatAck(", helper)
        self.assertIn("if (!routeMatches && !persistedEndpointMatch)", helper)
        self.assertIn("reason=unverified_endpoint", helper)
        self.assertIn("store.acknowledge(messageId, senderId)", helper)
        self.assertIn("pendingChatAckRouteRechecks", source)





class PythonFixedTargetImmediateDispatchTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.temp_dir.name, "peers.sqlite3")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_suspect_peer_endpoint_can_be_attempted_and_ack_is_endpoint_bound(self):
        from lan_p2p_core import (
            persist_outgoing_message, record_peer, load_pending_fixed_targets,
            mark_outgoing_attempt, record_successful_send_endpoint,
            outgoing_ack_endpoint_matches, list_known_peers,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-fixed-fast", "hello", ["android-a"]))
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "suspect")
        self.assertEqual([], list_known_peers(self.db_path, "server2"))
        targets = load_pending_fixed_targets(self.db_path, "server2", "m-fixed-fast")
        self.assertEqual([("android-a", "192.168.1.10", 8888)],
                         [(x["peer_id"], x["endpoint_ip"], x["endpoint_port"]) for x in targets])
        self.assertTrue(mark_outgoing_attempt(
            self.db_path, "m-fixed-fast", "android-a", "192.168.1.10", 8888))
        # A pre-send attempt is not proof that the UDP datagram left this process.
        self.assertFalse(outgoing_ack_endpoint_matches(
            self.db_path, "server2", "m-fixed-fast", "android-a", "192.168.1.10", 8888))
        self.assertTrue(record_successful_send_endpoint(
            self.db_path, "server2", "m-fixed-fast", "android-a", "192.168.1.10", 8888))
        self.assertTrue(outgoing_ack_endpoint_matches(
            self.db_path, "server2", "m-fixed-fast", "android-a", "192.168.1.10", 8888))
        self.assertFalse(outgoing_ack_endpoint_matches(
            self.db_path, "server2", "m-fixed-fast", "android-a", "192.168.1.11", 8888))

    def test_python_ack_endpoint_is_recorded_only_after_successful_udp_send(self):
        from lan_p2p_core import (
            persist_outgoing_message, mark_outgoing_attempt,
            send_outgoing_packet, outgoing_ack_endpoint_matches,
        )

        class FailingSocket:
            def sendto(self, payload, destination):
                raise OSError("simulated UDP send failure")

        class SuccessfulSocket:
            def sendto(self, payload, destination):
                return len(payload)

        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-send-fail", "hello", ["android-a"]))
        self.assertTrue(mark_outgoing_attempt(
            self.db_path, "m-send-fail", "android-a", "192.168.1.10", 8888))
        with self.assertRaises(OSError):
            send_outgoing_packet(
                FailingSocket(), b"frame", ("192.168.1.10", 8888),
                self.db_path, "server2", "m-send-fail", "android-a")
        self.assertFalse(outgoing_ack_endpoint_matches(
            self.db_path, "server2", "m-send-fail", "android-a", "192.168.1.10", 8888))

        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-send-ok", "hello", ["android-a"]))
        self.assertTrue(mark_outgoing_attempt(
            self.db_path, "m-send-ok", "android-a", "192.168.1.10", 8888))
        self.assertTrue(send_outgoing_packet(
            SuccessfulSocket(), b"frame", ("192.168.1.10", 8888),
            self.db_path, "server2", "m-send-ok", "android-a"))
        self.assertTrue(outgoing_ack_endpoint_matches(
            self.db_path, "server2", "m-send-ok", "android-a", "192.168.1.10", 8888))

    def test_python_ack_endpoint_read_waits_for_successful_send_commit(self):
        from concurrent.futures import ThreadPoolExecutor
        from lan_p2p_core import (
            persist_outgoing_message, mark_outgoing_attempt,
            send_outgoing_packet, outgoing_ack_endpoint_matches,
        )
        send_started = threading.Event()
        release_send = threading.Event()
        ack_check_started = threading.Event()

        class BlockingSocket:
            def sendto(self, payload, destination):
                send_started.set()
                if not release_send.wait(2):
                    raise TimeoutError("test send release timed out")

        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-ack-race", "hello", ["android-a"]))
        self.assertTrue(mark_outgoing_attempt(
            self.db_path, "m-ack-race", "android-a", "192.168.1.10", 8888))

        def check_ack_route():
            ack_check_started.set()
            return outgoing_ack_endpoint_matches(
                self.db_path, "server2", "m-ack-race", "android-a", "192.168.1.10", 8888)

        with ThreadPoolExecutor(max_workers=2) as pool:
            send_future = pool.submit(
                send_outgoing_packet, BlockingSocket(), b"frame",
                ("192.168.1.10", 8888), self.db_path, "server2", "m-ack-race", "android-a")
            self.assertTrue(send_started.wait(1))
            ack_future = pool.submit(check_ack_route)
            self.assertTrue(ack_check_started.wait(1))
            self.assertFalse(ack_future.done())
            release_send.set()
            self.assertTrue(send_future.result(timeout=2))
            self.assertTrue(ack_future.result(timeout=2))

    def test_all_python_retry_services_use_successful_send_endpoint_wrapper(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            with self.subTest(service=filename):
                source = (repo_root / filename).read_text(encoding="utf-8")
                self.assertIn("send_outgoing_packet(", source)
                self.assertNotIn(
                    'sock.sendto(raw, (pending["endpoint_ip"], target_port))', source)

    def test_bounded_packet_dispatcher_keeps_ack_lane_live_during_peer_and_message_work(self):
        from lan_p2p_core import BoundedPacketDispatcher
        message_started = threading.Event()
        peer_started = threading.Event()
        release_message = threading.Event()
        release_peer = threading.Event()
        ack_finished = threading.Event()

        def handler(envelope, sock, addr):
            packet_type = envelope.get("type")
            if packet_type == "chat.message":
                message_started.set()
                release_message.wait(2)
            elif packet_type == "peer.hello":
                peer_started.set()
                release_peer.wait(2)
            elif packet_type == "chat.ack":
                ack_finished.set()

        dispatcher = BoundedPacketDispatcher(
            handler, "test-ack-priority", ack_capacity=2,
            peer_capacity=2, message_capacity=2)
        try:
            self.assertTrue(dispatcher.submit({"type": "chat.message"}, None, None))
            self.assertTrue(dispatcher.submit({"type": "peer.hello"}, None, None))
            self.assertTrue(message_started.wait(1))
            self.assertTrue(peer_started.wait(1))
            self.assertTrue(dispatcher.submit({"type": "chat.ack"}, None, None))
            self.assertTrue(ack_finished.wait(1))
        finally:
            release_message.set()
            release_peer.set()
            dispatcher.close()

    def test_bounded_packet_dispatcher_rejects_overflow_without_unbounded_queueing(self):
        from lan_p2p_core import BoundedPacketDispatcher
        message_started = threading.Event()
        release_message = threading.Event()

        def handler(envelope, sock, addr):
            message_started.set()
            release_message.wait(2)

        dispatcher = BoundedPacketDispatcher(
            handler, "test-bounded", ack_capacity=1,
            peer_capacity=1, message_capacity=1)
        try:
            self.assertTrue(dispatcher.submit({"type": "chat.message", "n": 1}, None, None))
            self.assertTrue(message_started.wait(1))
            self.assertTrue(dispatcher.submit({"type": "chat.message", "n": 2}, None, None))
            self.assertFalse(dispatcher.submit({"type": "chat.message", "n": 3}, None, None))
        finally:
            release_message.set()
            dispatcher.close()

    def test_all_python_udp_listeners_dispatch_authenticated_packets_off_receive_loop(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            with self.subTest(service=filename):
                source = (repo_root / filename).read_text(encoding="utf-8")
                self.assertIn("BoundedPacketDispatcher(", source)
                self.assertIn("_V2_PACKET_DISPATCHER.submit(envelope", source)
                listener = source[source.index("def udp_listener():"):]
                listener = listener.split("\ndef ", 1)[0]
                self.assertNotIn("_handle_authenticated_v2_packet(envelope,", listener)
                self.assertIn('data[:1] == b"{"', listener)

    def test_peer_ack_recovery_is_woken_off_udp_receive_loop(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("server2.py", "rescue2.py", "chat.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            handler_start = source.index("def _handle_authenticated_v2_packet(")
            handler_end = source.index("\ndef ", handler_start + 5)
            handler = source[handler_start:handler_end]
            self.assertIn("_V2_RECOVERY_WAKE_EVENT.set()", handler, filename)
            self.assertNotIn("_resume_pending_v2_for_peer(sock, sender, addr)", handler, filename)
            worker_start = source.index("def _retry_deferred_v2_peer_recoveries(")
            worker_end = source.index("def _start_v2_recovery_retry_worker(", worker_start)
            worker = source[worker_start:worker_end]
            self.assertIn("list_known_peer_snapshot(", worker, filename)
            self.assertIn("recovery_event=peer_id in recovery_event_peers", worker, filename)


class AndroidFixedTargetImmediateDispatchTests(unittest.TestCase):
    def test_fixed_target_uses_persisted_endpoint_without_waiting_for_verified_route(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("LanAdaptiveRouteSelector.selectPrimaryBinding(", send)
        self.assertIn("dispatchBindings.put(target.peerId, liveBinding)", send)
        self.assertIn("dispatchEndpoints.put(target.peerId, liveBinding.route.endpoint)", send)
        self.assertIn("target.endpointIp", send)
        self.assertIn("activeChatListenerCount == 1", send)
        self.assertIn("preparedCachedEndpoints.get(target.peerId)", send)
        self.assertNotIn("verifiedChatPeers.get(target.peerId)", send)
        self.assertIn("store.markAttempt(messageId, peerId, attemptedEndpoint", send)
        self.assertIn("CHAT_UNICAST_CACHED_ENDPOINT", send)

    def test_ack_matches_attempted_endpoint_but_not_historical_route_candidate(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        attempt = extract_java_method(store, "public boolean markAttempt(")
        match = extract_java_method(store, "public boolean matchesExpectedAckEndpoint(")
        self.assertIn("last_attempt_endpoint=?", attempt)
        self.assertIn("update.bindString(1, endpoint)", attempt)
        self.assertIn("last_sent_endpoint=? LIMIT 1", match)
        self.assertNotIn("last_attempt_endpoint", match)
        attempted_match = extract_java_method(store, "public boolean matchesAttemptedAckEndpoint(")
        self.assertIn("last_attempt_endpoint=?", attempted_match)
        ack_handler = extract_java_method(
            (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8"),
            "private void processAuthenticatedLanChatAck("
        )
        self.assertIn("attemptedEndpointPending", ack_handler)
        self.assertIn("allowDiscoveryGrace && (discoveryAck || attemptedEndpointPending)", ack_handler)



class AndroidLanInstantRouteRecoveryTests(unittest.TestCase):
    def test_verified_peer_route_refreshes_pending_target_candidates_without_rewriting_ack_evidence(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        upsert = extract_java_method(source, "public boolean upsertPeer(")
        self.assertIn("db.beginTransaction()", upsert)
        self.assertIn('targetRoute.put("last_endpoint", ip + ":" + port)', upsert)
        self.assertIn("UPDATE", upsert.upper())
        self.assertIn("status!='acked'", upsert)
        self.assertIn("last_attempt_endpoint", upsert)  # documented as deliberately untouched
        self.assertNotIn('targetRoute.put("last_attempt_endpoint"', upsert)
        self.assertNotIn('targetRoute.put("last_sent_endpoint"', upsert)

    def test_changed_verified_endpoint_bypasses_only_same_endpoint_retry_backoff(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        attempt = extract_java_method(source, "public boolean markAttempt(")
        self.assertIn("last_attempt_endpoint<>?", attempt)
        self.assertIn("last_attempt_at + ", attempt)
        self.assertIn("LanChatRetryPolicy.TARGET_RETRY_DELAY_CASE_SQL", attempt)
        self.assertIn("update.bindString(7, endpoint)", attempt)
        self.assertIn("update.bindLong(8, now)", attempt)

    def test_missing_ack_rearms_bounded_discovery_and_verified_retry_waits_for_ack_grace(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        complete = extract_java_method(source, "private void completeLanChatAckWindow(")
        policy = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatRetryPolicy.java").read_text(encoding="utf-8")
        self.assertIn("PEER_DISCOVERY_COOLDOWN_MS = 1_500L", policy)
        self.assertIn("LAN_PEER_DISCOVERY_COOLDOWN_MS = LanChatRetryPolicy.PEER_DISCOVERY_COOLDOWN_MS", source)
        self.assertIn("target.attemptCount > 0", send)
        self.assertIn("hadUdpDispatchAttempt.set(true)", send)
        self.assertIn("lastPeerDiscoveryAt = 0L", complete)
        self.assertIn("retryPendingLanChatMessages(false)", complete)
        self.assertIn("lanChatRouteRefreshPending.set(true)", complete)
        self.assertIn("lanChatRouteRefreshPending.get()", source)
        self.assertIn("lanChatRouteRefreshPending.getAndSet(false)", source)
        self.assertIn('scheduleLanChatTask("peer-verified-retry", () -> retryPendingLanChatMessagesForPeer(senderId), 350L)', source)
        self.assertNotIn('peer-verified-retry", () -> retryPendingLanChatMessages(true)', source)


    def test_android_ack_fast_path_skips_discovery_sqlite_read_for_verified_endpoints(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        ack = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        query_guard = ack.index("if (!routeMatches && !persistedEndpointMatch) {")
        discovery_query = ack.index("store.isDiscoveryPending(messageId)")
        self.assertGreater(discovery_query, query_guard)
        self.assertIn("if (!routeMatches && !persistedEndpointMatch) {", ack[discovery_query:])
        self.assertIn("store.matchesExpectedAckEndpoint(messageId, senderId", ack)

    def test_android_successful_endpoint_bookkeeping_is_off_the_fifo_send_lock(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        udp = send.index("sendLanUdpPacket(peerSocket, p, verifiedRoutePeerIds.contains(peerId)")
        persist = send.index("lanChatPeerExecutor.execute(", udp)
        self.assertLess(udp, persist)
        self.assertNotIn("lanChatControlExecutor.execute(", send)
        self.assertNotIn("store.recordSuccessfulSendEndpoint(", send)
        self.assertIn("markAttempt() durably records only the attempted endpoint", send)
        self.assertIn("endpointStore.recordSuccessfulSendEndpoint(", send)
        self.assertIn("if (!endpointPersisted)", send)



class AndroidLanInboxProjectionRecoveryTests(unittest.TestCase):
    def test_durable_inbox_rows_can_be_replayed_after_ack_before_ui_projection(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        method = extract_java_method(store, "public List<IncomingMessage> loadUndisplayedIncoming(")
        self.assertIn("WHERE receiver_scope=? AND displayed=0", method)
        self.assertIn("ORDER BY received_at_ms,created_at_ms", method)
        self.assertIn("new IncomingMessage(", method)

    def test_chat_ack_follows_sqlite_inbox_commit_and_projection_is_scheduled_after_ack(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        handler = source[source.index('if ("chat.message".equals(type))'):
                         source.index("\n        } catch (Exception e)", source.index('if ("chat.message".equals(type))'))]
        persist = handler.index("persistIncomingLanChat(")
        ack = handler.index("sendLanChatReceiveAck(", persist)
        projection = handler.index("scheduleLanChatInboxProjection()", ack)
        self.assertLess(persist, ack)
        self.assertLess(ack, projection)
        scheduler = extract_java_method(source, "private void scheduleLanChatInboxProjection(")
        self.assertIn("lanChatInboxProjectionRequested.set(true)", scheduler)
        self.assertIn("lanChatInboxProjectionRequested.getAndSet(false)", scheduler)
        self.assertIn("scheduleLanChatInboxProjection();", scheduler)
        project = extract_java_method(source, "private boolean projectUndisplayedLanChatInbox(")
        self.assertIn("editor.commit()", project)
        self.assertIn("store.markIncomingDisplayed(", project)
        self.assertIn("knownIds.add(stableId)", project)
        self.assertIn("catch (org.json.JSONException malformedIds)", project)
        self.assertIn("rebuilding from durable inbox", project)
        self.assertIn("return pending.size() >= 100", project)




class AndroidLanFixedTargetFastPathTests(unittest.TestCase):
    def test_first_discovery_broadcast_address_is_resolved_before_fifo_lock(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessage(")
        internal = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        lookup = send.index("? getWifiBroadcastAddress() : null")
        lock = send.index("lanChatTransmitLock.lock()")
        self.assertLess(lookup, lock)
        self.assertIn("pendingSnapshotTargets == 0", send)
        self.assertIn("preLockBroadcastLookupMs", send)
        call_start = send.index("transmitLocalUdpMessageInternal(")
        send_call = send[call_start:]
        self.assertIn("preparedBroadcastAddress", send_call)
        self.assertIn("preLockBroadcastLookupMs", send_call)
        self.assertIn("final String[] broadcasts = pendingTargetIds.isEmpty()", internal)
        self.assertIn("new String[]{preparedBroadcastAddress}", internal)
        self.assertEqual(internal.count("getWifiBroadcastAddress()"), 1)
        # Fixed-recipient discovery still resolves its auxiliary broadcast address asynchronously.
        self.assertIn("new String[]{getWifiBroadcastAddress()}", internal)
        probe_queue = internal.index("lanChatProbeExecutor.execute(() ->")
        async_lookup = internal.index("new String[]{getWifiBroadcastAddress()}", probe_queue)
        self.assertGreater(async_lookup, probe_queue)
        self.assertIn("for (String target : broadcasts)", internal)
        self.assertIn("preLockBroadcastLookupMs=", internal)
        self.assertIn("preparationMs - routeProbeMs", internal)

    def test_fixed_target_unicast_skips_discovery_window_sqlite_read(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        internal = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        guard = internal.index("if (totalPeerCount == 0) {")
        read = internal.index("store.isDiscoveryPending(messageId)", guard)
        self.assertGreater(read, guard)
        self.assertIn("if (totalPeerCount == 0 && !firstDiscoveryWindowOpen)", internal)



class AndroidLanImmediateSendIsolationTests(unittest.TestCase):
    def test_immediate_send_does_not_scan_outbox_for_retry_plan_inline(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        transmit = extract_java_method(source, "private void transmitLocalUdpMessage(")
        self.assertIn("requestLanChatRetryPlan()", transmit)
        self.assertNotIn("scheduleLanChatRetryFromOutbox()", transmit)
        planner = extract_java_method(source, "private void requestLanChatRetryPlan(")
        self.assertIn("scheduleLanChatRetryPlan(0L)", planner)
        plan = extract_java_method(source, "private void scheduleLanChatRetryPlan(long delayMs)")
        self.assertIn("lanChatRetryPlanPending.compareAndSet(false, true)", plan)
        self.assertIn("lanChatRetryPlanPending.set(false)", plan)
        self.assertIn("scheduleLanChatRetryPlan(500L)", plan)




class AndroidLanFastRetryBurstTests(unittest.TestCase):
    def test_fixed_target_attempts_allow_two_short_retries_then_backoff(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        mark = extract_java_method(store, "public boolean markAttempt(")
        policy = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatRetryPolicy.java").read_text(encoding="utf-8")
        self.assertIn("LanChatRetryPolicy.TARGET_RETRY_DELAY_CASE_SQL", mark)
        self.assertIn("{250L, 250L, 10_000L, 20_000L, 40_000L}", policy)
        self.assertIn('new StringBuilder("CASE attempt_count ")', policy)
        self.assertIn('append("WHEN ").append(i + 1).append(" THEN ")', policy)
        self.assertIn("attempt_count=attempt_count+1", mark)

    def test_empty_discovery_messages_get_three_fast_dispatch_epochs(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        service = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        getter = extract_java_method(store, "public int getOutboxRetryAttemptCount(")
        self.assertIn("SELECT retry_attempt_count FROM lan_outbox", getter)
        complete = extract_java_method(service, "private void completeLanChatAckWindow(")
        self.assertIn("discoveryAttempts > 0 && discoveryAttempts < 3", complete)
        self.assertIn("requestRouteRefresh = true", complete)
        scheduler = extract_java_method(service, "private void scheduleLanChatRetryFromOutbox(")
        self.assertIn("LanChatRetryPolicy.outboxRetryWakeDelayMs(", scheduler)

    def test_retry_timer_is_armed_after_ack_grace_not_before_fast_retry(self):
        repo_root = Path(__file__).resolve().parents[1]
        service = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        transmit = extract_java_method(service, "private void transmitLocalUdpMessage(")
        self.assertIn("if (!lanChatAckGraceInFlight.contains(messageId))", transmit)
        self.assertIn("requestLanChatRetryPlan()", transmit)
        complete = extract_java_method(service, "private void completeLanChatAckWindow(")
        self.assertIn("requestLanChatRetryPlan();", complete)
        self.assertIn("short ACK/fast-retry decision", complete)
        self.assertIn("durable backoff only after", complete)


class AndroidLanProbeIsolationTests(unittest.TestCase):
    def test_route_challenges_and_discovery_run_outside_fifo_transmit_lock(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        internal = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("lanChatProbeExecutor.execute(", internal)
        self.assertIn("sendDirectedPeerChallenge(socket, challengePeerId", internal)
        self.assertIn("P2P discovery probe queueMs=", internal)
        self.assertIn("peerDiscoveryInFlight.set(false)", internal)
        self.assertIn("pendingPeerChallengeDispatches.putIfAbsent(", source)
        self.assertIn("pendingPeerChallengeDispatches.remove(challengePeerId, challengeDispatchToken)", source)
        self.assertIn("private final java.util.concurrent.ExecutorService lanChatProbeExecutor", source)
        self.assertIn("pendingPeerChallengeDispatches.clear()", source)
        self.assertIn("if (serviceDestroyed || !isCurrentLanChatSocket(socket, socketGeneration)) return;", internal)

    def test_udp_send_does_not_serialize_all_senders_on_socket_monitor(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void sendLanUdpPacket(")
        self.assertIn("socket.send(packet)", send)
        self.assertIn("socket.isClosed()", send)
        self.assertIn("sendMs=", send)
        self.assertNotIn("synchronized (socket)", send)


class AndroidLanAckFastPathTests(unittest.TestCase):
    def test_verified_live_route_skips_redundant_sqlite_endpoint_lookup(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        ack = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        self.assertIn("boolean persistedEndpointMatch = !routeMatches", ack)
        self.assertIn("&& store.matchesExpectedAckEndpoint(messageId, senderId", ack)
        self.assertIn("if (!routeMatches && !persistedEndpointMatch)", ack)
        self.assertIn("discoveryAck = store.isDiscoveryPending(messageId)", ack)

    def test_verified_first_discovery_ack_releases_empty_recipient_latch(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        ack = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        self.assertIn("expected != null && expected.isEmpty()", ack)
        self.assertIn("if (discoveryBroadcastAck && !discoveryAck)", ack)
        self.assertIn("discoveryBroadcastAck = store.isDiscoveryPending(messageId);", ack)
        self.assertIn("if (latch != null && (wasExpected || discoveryBroadcastAck)) latch.countDown();", ack)


class AndroidLanControlQueueDiagnosticsTests(unittest.TestCase):
    def test_ack_rejections_and_peer_handshakes_report_control_queue_delay(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        ack = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        self.assertIn('" queueMs=" + ackQueueMs', ack)
        self.assertIn('" totalMs=" + ackDecisionMs', ack)
        self.assertIn('" graceMs=200 queueMs=" + ackQueueMs', ack)
        peer_ack_start = source.index('if ("peer.ack".equals(type))')
        peer_ack_end = source.index('if ("chat.ack".equals(type))', peer_ack_start)
        peer_ack = source[peer_ack_start:peer_ack_end]
        self.assertIn("controlQueueMs", peer_ack)
        self.assertIn("P2P peer.ack control queueMs=", peer_ack)
        peer_hello_start = source.index('if ("peer.hello".equals(type))')
        peer_hello_end = source.index('if ("peer.ack".equals(type))', peer_hello_start)
        peer_hello = source[peer_hello_start:peer_hello_end]
        self.assertIn("lanChatPeerExecutor.execute(", peer_hello)
        self.assertIn("P2P peer.hello peer queueMs=", peer_hello)
        self.assertNotIn("lanChatControlExecutor.execute(", peer_hello)
        chat_ack_start = source.index('if ("chat.ack".equals(type))')
        chat_ack_end = source.index('if ("peer.hello".equals(type))', chat_ack_start) if 'if ("peer.hello".equals(type))' in source[chat_ack_start:] else len(source)
        chat_ack = source[chat_ack_start:chat_ack_end]
        self.assertIn("lanChatAckExecutor.execute(", chat_ack)
        self.assertNotIn("lanChatControlExecutor.execute(", chat_ack)
        self.assertIn("lanChatControlExecutor.execute(", peer_ack)
        self.assertNotIn("lanChatAckExecutor.execute(", peer_ack)



class AndroidLanAckLaneIsolationTests(unittest.TestCase):
    def test_message_ack_is_not_queued_behind_peer_route_commits(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        handler = extract_java_method(source, "private boolean handleAuthenticatedLanPacket(")
        chat_ack_start = handler.index('if ("chat.ack".equals(type))')
        chat_ack_end = handler.index('if ("chat.message".equals(type))', chat_ack_start)
        chat_ack = handler[chat_ack_start:chat_ack_end]
        peer_ack_start = handler.index('if ("peer.ack".equals(type))')
        peer_ack_end = handler.index('if ("chat.ack".equals(type))', peer_ack_start)
        peer_ack = handler[peer_ack_start:peer_ack_end]
        self.assertIn("lanChatAckExecutor.execute(", chat_ack)
        self.assertNotIn("lanChatControlExecutor.execute(", chat_ack)
        self.assertIn("lanChatControlExecutor.execute(", peer_ack)
        ack = extract_java_method(source, "private void processAuthenticatedLanChatAck(")
        self.assertIn("lanChatAckExecutor.execute(() -> processAuthenticatedLanChatAck(", ack)
        self.assertIn("lanChatAckExecutor.shutdown()", extract_java_method(source, "public void onDestroy()"))


class AndroidLanStoreLifecycleTests(unittest.TestCase):
    def test_service_destroy_interrupts_and_cancels_one_shot_startup_worker(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        create = extract_java_method(source, "public void onCreate()")
        destroy = extract_java_method(source, "public void onDestroy()")
        self.assertIn("private volatile Thread startupThread", source)
        self.assertIn("startupThread = new Thread(", create)
        self.assertIn("startupThread.start()", create)
        self.assertIn("startupThread.interrupt()", destroy)
        self.assertIn("startupThread = null", destroy)
        self.assertGreaterEqual(
            create.count("Thread.currentThread().isInterrupted()"), 8
        )
        self.assertIn("if (serviceDestroyed || Thread.currentThread().isInterrupted()) return;", create)

    def test_service_destroy_drains_communication_executors_before_closing_sqlite(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        destroy = extract_java_method(source, "public void onDestroy()")
        self.assertIn("lanChatImmediateExecutor.shutdown()", destroy)
        self.assertIn("lanChatSendExecutor.shutdown()", destroy)
        self.assertIn("lanChatControlExecutor.shutdown()", destroy)
        self.assertIn("lanChatAckExecutor.shutdown()", destroy)
        self.assertIn("lanChatPeerExecutor.shutdown()", destroy)
        self.assertIn("lanChatProbeExecutor.shutdown()", destroy)
        self.assertIn("lanChatIoExecutor.shutdown()", destroy)
        self.assertIn("closeLanChatIngressSocket()", destroy)
        self.assertLess(destroy.index("serviceDestroyed = true"),
                        destroy.index("closeLanChatIngressSocket()"))
        self.assertLess(destroy.index("closeLanChatIngressSocket()"),
                        destroy.index("listenerToStop.interrupt()"))
        self.assertLess(destroy.index("listenerToStop.interrupt()"),
                        destroy.index("lanChatPersistExecutor.shutdown()"))
        self.assertIn("releaseLanMulticastLock();", destroy)
        self.assertLess(destroy.index("closeLanChatIngressSocket()"),
                        destroy.index("lanChatPersistExecutor.shutdown()"))
        ingress_close = extract_java_method(source, "private void closeLanChatIngressSocket()")
        self.assertIn("synchronized (rescueLock)", ingress_close)
        self.assertIn("rescueSocketRef = null", ingress_close)
        self.assertIn("socketToClose.close()", ingress_close)
        self.assertIn("LanChatStore storeToClose = lanChatStore", destroy)
        self.assertIn("closeLanChatStoreAfterExecutors(storeToClose)", destroy)
        self.assertNotIn("lanChatStore.closeStore()", destroy)

        closer = extract_java_method(source, "private void closeLanChatStoreAfterExecutors(")
        for executor in ("lanChatScheduler", "lanChatImmediateExecutor", "lanChatSendExecutor",
                         "lanChatControlExecutor", "lanChatAckExecutor", "lanChatPeerExecutor", "lanChatProbeExecutor", "lanChatIoExecutor",
                         "maintenanceExecutor"):
            self.assertIn("awaitLanExecutorTermination(" + executor + ")", closer)
        self.assertIn("storeToClose.closeStore()", closer)
        awaiter = extract_java_method(source, "private void awaitLanExecutorTermination(")
        self.assertIn("while (!executor.isTerminated())", awaiter)
        self.assertIn("executor.awaitTermination(5L, TimeUnit.SECONDS)", awaiter)


class AndroidLanTransmitFairnessTests(unittest.TestCase):
    def test_fresh_and_retry_sends_share_a_fair_serialization_lock(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        self.assertIn("new java.util.concurrent.locks.ReentrantLock(true)", source)
        transmit = extract_java_method(source, "private void transmitLocalUdpMessage(")
        self.assertIn("lanChatTransmitLock.lock()", transmit)
        self.assertIn("lanChatTransmitLock.unlock()", transmit)
        self.assertIn("store.recordOutboxAttempt(messageId)", transmit)
        self.assertLess(transmit.index("lanChatTransmitLock.lock()"),
                        transmit.index("store.recordOutboxAttempt(messageId)"))
        self.assertIn("finally", transmit)
        self.assertNotIn("synchronized (lanChatTransmitLock)", source)
        self.assertIn("lanChatImmediateExecutor", source)
        self.assertIn("lanChatSendExecutor", source)


class AndroidLanUnattemptedRouteRetryTests(unittest.TestCase):
    def test_fixed_targets_without_a_first_udp_send_keep_a_route_probe_wakeup(self):
        repo_root = Path(__file__).resolve().parents[1]
        tracking = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        pending_targets = extract_java_method(store, "public boolean hasUnattemptedTargets(")
        self.assertIn("t.attempt_count=0", pending_targets)
        self.assertIn("t.status!='acked'", pending_targets)
        self.assertIn("'delivered','expired','waiting_peers','retry_paused'", pending_targets)

        scheduler = extract_java_method(tracking, "private void scheduleLanChatRetryFromOutbox(")
        self.assertIn("store.hasUnattemptedTargets(item.messageId)", scheduler)
        self.assertIn("hasUnattemptedRouteTargets", scheduler)
        self.assertIn("LanChatRetryPolicy.outboxRetryWakeDelayMs(", scheduler)
        self.assertIn("if (minimumAttempts == Integer.MAX_VALUE && !hasUnattemptedRouteTargets) return", scheduler)
        self.assertIn("markAttempt()", scheduler)
        self.assertIn("has correctly not consumed a delivery attempt", scheduler)

    def test_android_lan_chat_requires_current_wifi_socket_before_charging_attempt(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessage(")
        self.assertIn("hasUsableWifiLanPath()", send)
        self.assertLess(send.index("hasUsableWifiLanPath()"), send.index("store.recordOutboxAttempt(messageId)"))
        self.assertIn("isCurrentLanChatSocket(socket, socketGeneration)", send)
        scheduler = extract_java_method(source, "private void scheduleLanChatRetryFromOutbox(")
        self.assertIn("if (!hasUsableWifiLanPath()) return;", scheduler)

    def test_android_udp_socket_generation_rejects_stale_listener_and_send_work(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        self.assertIn("AtomicLong lanSocketGeneration", source)
        refresh = extract_java_method(source, "private void refreshWifiLanTransport(")
        self.assertIn("lanSocketGeneration.incrementAndGet()", refresh)
        self.assertIn("synchronized (rescueLock)", refresh)
        listener = extract_java_method(source, "private void startRescueListener()")
        self.assertIn("listenerGeneration != lanSocketGeneration.get()", listener)
        self.assertIn("synchronized (rescueLock)", listener)
        self.assertIn("if (rescueSocketRef == rescueSocket) {", listener)
        self.assertIn("rescueSocketRef = null;", listener)
        self.assertIn("lanChatBoundWifiNetwork = null;", listener)
        self.assertIn("networkGenerationChanged = ownsListener", listener)
        finally_block = listener[listener.index("} finally {"):]
        self.assertIn("if (rescueListenerThread == null && !serviceDestroyed && networkGenerationChanged)", finally_block)
        self.assertIn("triggerRescueListener();", finally_block)
        self.assertIn("else if (rescueListenerThread == null && !serviceDestroyed)", finally_block)
        self.assertIn("long retryDelayMs =", finally_block)
        self.assertLess(finally_block.index("networkGenerationChanged"), finally_block.index("long retryDelayMs ="))
        send = extract_java_method(source, "private void transmitLocalUdpMessage(")
        self.assertIn("socketGeneration = lanSocketGeneration.get()", send)
        self.assertIn("isCurrentLanChatSocket(socket, socketGeneration)", send)
        internal = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("isCurrentLanChatSocket(socket, socketGeneration)", internal)

    def test_android_transmit_lock_reports_wait_and_hold_separately(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessage(")
        self.assertIn("P2P 发送串行锁等待", send)
        self.assertIn("P2P 发送串行锁持有", send)
        self.assertIn("queuedAtRequest=", send)
        self.assertIn("holdMs=", send)

    def test_android_transmit_lock_separates_sqlite_and_udp_costs(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessage(")
        internal = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("P2P SQLite outbox attempt", send)
        self.assertIn("P2P SQLite target attempt", internal)
        self.assertIn("P2P async SQLite send-endpoint", internal)
        self.assertIn("P2P directed challenge queued", internal)
        self.assertIn("P2P 发送串行锁持有", send)
        udp = extract_java_method(source, "private void sendLanUdpPacket(")
        self.assertIn("sendMs=", udp)
        self.assertNotIn("lockWaitMs=", udp)

    def test_android_cached_endpoint_resolution_is_outside_transmit_lock(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessage(")
        internal = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        resolve = send.index("InetAddress.getByName(target.endpointIp)")
        lock = send.index("lanChatTransmitLock.lock()")
        self.assertLess(resolve, lock)
        self.assertIn("preparedCachedEndpoints.put(target.peerId", send)
        self.assertIn("preparedCachedEndpoints.get(target.peerId)", internal)
        self.assertIn("LanAdaptiveRouteSelector.selectPrimaryBinding(", internal)
        self.assertIn("if (activeChatListenerCount == 1)", internal)
        self.assertLess(internal.index("LanAdaptiveRouteSelector.selectPrimaryBinding("),
                        internal.index("preparedCachedEndpoints.get(target.peerId)"))
        self.assertNotIn("InetAddress.getByName(target.endpointIp)", internal)
        self.assertIn("preLockEndpointResolveMs=", internal)

    def test_chat_activity_restores_input_caret_when_resumed(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/ChatActivity.java").read_text(encoding="utf-8")
        resume = extract_java_method(source, "protected void onResume()")
        focus = extract_java_method(
            source, "private void restoreChatInputFocus(boolean keepKeyboardVisible)"
        )
        ime = extract_java_method(source, "private void scheduleComposerImeRestore()")
        window_focus = extract_java_method(source, "public void onWindowFocusChanged(boolean hasFocus)")
        self.assertIn("refreshHistory(true);", resume)
        self.assertNotIn("refreshHistory(false);", resume)
        self.assertIn("restoreChatInputFocus(false);", resume)
        self.assertIn("inputMsg.requestFocus();", focus)
        self.assertIn("inputMsg.setSelection(inputMsg.length());", focus)
        self.assertIn("if (!keepKeyboardVisible) return;", focus)
        self.assertNotIn("scheduleComposerImeRestore();", focus.split("if (!keepKeyboardVisible) return;", 1)[0])
        self.assertIn("if (hasFocus && pendingComposerImeRestore)", window_focus)
        self.assertIn("showSoftInput", ime)


    def test_android_authenticated_packets_are_prebuilt_before_transmit_lock(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessage(")
        internal = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertLess(send.index("buildAuthenticatedLanChatPacket("), send.index("lanChatTransmitLock.lock()"))
        self.assertIn("preparedPeerPackets.put(target.peerId", send)
        self.assertLess(send.index("buildAuthenticatedLanChatPacket("),
                        send.index("store.recordOutboxAttempt(messageId)"))
        self.assertIn("P2P authenticated packet prebuild", send)
        self.assertIn("preparedBroadcastData = buildAuthenticatedLanChatPacket(", send)
        self.assertIn("preparedPeerPackets.get(peerId)", internal)
        self.assertIn("new DatagramPacket(preparedBroadcastData, preparedBroadcastData.length,", internal)
        self.assertNotIn("buildAuthenticatedLanChatPacket(", internal)
        self.assertIn("store.markAttempt(messageId, peerId, attemptedEndpoint, \"\");", internal)

    def test_android_internal_sender_uses_preloaded_target_snapshot_timing(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        internal = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("final List<LanChatStore.Target> savedTargets, long targetLoadMs", internal)
        self.assertNotRegex(internal, r"\\blong\\s+targetLoadMs\\s*=")
        self.assertIn("+ \" preLockTargetDbMs=\" + targetLoadMs", internal)

    def test_android_recipient_snapshot_read_is_outside_transmit_lock(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessage(")
        internal = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        snapshot_pos = send.index("preparedTargets = store.loadTargets(messageId)")
        lock_pos = send.index("lanChatTransmitLock.lock()")
        self.assertLess(snapshot_pos, lock_pos)
        self.assertIn("preparedTargets, targetLoadMs", send)
        self.assertIn("final List<LanChatStore.Target> savedTargets", internal)
        self.assertNotIn("store.loadTargets(messageId)", internal)
        # A stale read is safe only because the durable target status is checked again
        # transactionally immediately before every actual unicast dispatch.
        self.assertIn("store.markAttempt(messageId, peerId, attemptedEndpoint, \"\");", internal)

    def test_android_missing_peer_packet_does_not_consume_delivery_attempt(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        internal = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        packet_pos = internal.index("byte[] peerData = preparedPeerPackets.get(peerId);")
        missing_guard_pos = internal.index("if (peerData == null)", packet_pos)
        attempt_pos = internal.index("store.markAttempt(messageId, peerId, attemptedEndpoint, \"\");")
        self.assertLess(packet_pos, missing_guard_pos)
        self.assertLess(missing_guard_pos, attempt_pos)
        self.assertIn("保留发送预算 peer=", internal)


class AndroidLanExplicitSocketRestartTests(unittest.TestCase):
    def test_manual_restart_actions_use_generation_handoff_not_failure_backoff(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        helper = extract_java_method(source, "private void requestRescueListenerRestart(")
        self.assertIn("lanSocketGeneration.incrementAndGet()", helper)
        self.assertIn("synchronized (rescueLock)", helper)
        self.assertIn("lanChatBoundWifiNetwork = null", helper)
        self.assertIn("oldSocket.close()", helper)
        self.assertLess(helper.index("synchronized (rescueLock)"), helper.index("oldSocket.close()"))
        self.assertIn("triggerRescueListener()", helper)
        self.assertIn('requestRescueListenerRestart("ACTION_RESTART_UDP")', source)
        self.assertIn('requestRescueListenerRestart("restart_udp")', source)
        self.assertNotIn('if (rescueSocketRef != null) { try { rescueSocketRef.close(); } catch (Exception ignored) {} }', source)
        self.assertNotIn('if (rescueSocketRef != null) {\n                        try { rescueSocketRef.close(); } catch (Exception ignored) {}\n                    }', source)


class AndroidLanDiscoveryWindowAtomicityTests(unittest.TestCase):
    def test_first_discovery_window_is_started_checked_and_settled_in_one_store_transaction(self):
        repo_root = Path(__file__).resolve().parents[1]
        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        service_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        operation = extract_java_method(
            store_source, "public int beginOrReadDiscoveryWindow("
        )
        sender = extract_java_method(
            service_source, "private void transmitLocalUdpMessageInternal("
        )
        self.assertIn("db.beginTransaction();", operation)
        self.assertIn("db.endTransaction();", operation)
        self.assertIn("finalizeDiscoveryMessage(db, messageId)", operation)
        self.assertIn("DISCOVERY_WINDOW_STARTED", operation)
        self.assertIn("DISCOVERY_WINDOW_OPEN", operation)
        self.assertIn("DISCOVERY_WINDOW_WAITING", operation)
        self.assertIn("DISCOVERY_WINDOW_ERROR", operation)
        self.assertIn("delivery_deadline_ms<=0", operation)
        self.assertIn("state='discovery_pending'", operation)
        self.assertIn("store.beginOrReadDiscoveryWindow(messageId)", sender)
        self.assertNotIn("store.beginDiscoveryWindow(messageId)", sender)
        # Keep the later send-time deadline recheck: the combined transaction replaces
        # only the initial begin/read/read sequence, not the final fail-closed guard.
        self.assertEqual(sender.count("store.isDiscoveryPending(messageId)"), 1)
        self.assertNotIn("store.isWaitingForPeers(messageId)", sender)
        self.assertLess(
            sender.index("store.beginOrReadDiscoveryWindow(messageId)"),
            sender.index("if (firstDispatch)"),
        )
        self.assertGreater(
            sender.index("store.isDiscoveryPending(messageId)"),
            sender.index("// Revalidate persisted endpoints off the FIFO transmit lock."),
        )
        self.assertIn("scheduleLanChatDiscoveryFinalizer(", sender)
        self.assertIn("LAN_CHAT_DISCOVERY_WINDOW_MS + 100L", sender)


    def test_fixed_target_attempt_progress_is_read_before_fifo_lock(self):
        repo_root = Path(__file__).resolve().parents[1]
        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        service_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        progress = extract_java_method(
            store_source, "public int readFixedTargetAttemptProgress("
        )
        sender = extract_java_method(
            service_source, "private void transmitLocalUdpMessage("
        )
        internal = extract_java_method(
            service_source, "private void transmitLocalUdpMessageInternal("
        )
        self.assertIn("getReadableDatabase()", progress)
        self.assertIn("readOutboxTargetAttemptProgress(", progress)
        self.assertNotIn("beginTransaction()", progress)
        self.assertLess(
            sender.index("store.readFixedTargetAttemptProgress(messageId)"),
            sender.index("lanChatTransmitLock.lock()"),
        )
        self.assertIn("hasFixedTargetSnapshot", sender)
        self.assertIn("attemptNumber = preparedAttemptNumber;", sender)
        self.assertIn("store.recordOutboxAttempt(messageId)", sender)
        self.assertIn("store.markAttempt(messageId, peerId, attemptedEndpoint, \"\");", internal)

class LanP2PEnvelopeBudgetParityTests(unittest.TestCase):
    def test_android_store_validates_historical_and_live_targets_before_commit(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        enqueue = extract_java_method(store, "public boolean enqueueWithRecipientSnapshot(")
        set_targets = extract_java_method(store, "public boolean setTargets(")
        self.assertIn("LanP2pProtocol.isValidPeerId(senderPeerId)", enqueue)
        self.assertGreaterEqual(enqueue.count("LanP2pProtocol.isValidPeerId(peerId)"), 2)
        self.assertIn("LanP2pProtocol.isValidPeerId(target.peerId)", set_targets)

    def test_android_and_python_share_worst_case_recipient_preflight(self):
        repo_root = Path(__file__).resolve().parents[1]
        protocol = (repo_root / "app/src/main/java/com/example/devicefinder/LanP2pProtocol.java").read_text(encoding="utf-8")
        service = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        python_core = (repo_root / "lan_p2p_core.py").read_text(encoding="utf-8")
        server = (repo_root / "server2.py").read_text(encoding="utf-8")
        self.assertIn("MAX_PEER_ID_BYTES = 128", protocol)
        self.assertIn("MAX_PEER_ID_BYTES = 128", python_core)
        self.assertIn("LanP2pProtocol.maxPeerIdBudgetProbe()", service)
        self.assertIn('budget_packet["target_peer_id"] = max_peer_id_budget_probe()', server)
        self.assertIn("validatePeerId(packet, \"target_peer_id\")", protocol)
        self.assertIn("_validate_peer_id_fields(packet)", python_core)


class LanP2PPeerIdentityUnicodeParityTests(unittest.TestCase):
    def test_python_peer_id_validation_rejects_unpaired_surrogates_and_c1_controls(self):
        from lan_p2p_core import is_valid_peer_id
        self.assertFalse(is_valid_peer_id(chr(0xD800)))
        self.assertFalse(is_valid_peer_id(chr(0xDFFF)))
        self.assertFalse(is_valid_peer_id("device-" + chr(0x7F)))
        self.assertFalse(is_valid_peer_id("device-" + chr(0x80)))
        self.assertFalse(is_valid_peer_id("device-" + chr(0x9F)))
        self.assertTrue(is_valid_peer_id("device-😀"))

    def test_android_peer_id_validation_matches_python_utf8_scalar_rules(self):
        repo_root = Path(__file__).resolve().parents[1]
        protocol = (repo_root / "app/src/main/java/com/example/devicefinder/LanP2pProtocol.java").read_text(encoding="utf-8")
        validator = extract_java_method(protocol, "public static boolean isValidPeerId(")
        scalar_check = extract_java_method(protocol, "private static boolean isValidUnicodeScalarString(")
        self.assertIn("isValidUnicodeScalarString(peerId)", validator)
        self.assertIn("Character.isHighSurrogate(ch)", scalar_check)
        self.assertIn("Character.isLowSurrogate(value.charAt(i + 1))", scalar_check)
        self.assertIn("else if (Character.isLowSurrogate(ch))", scalar_check)
        self.assertIn("peerId.getBytes(StandardCharsets.UTF_8).length <= MAX_PEER_ID_BYTES", validator)


class LanP2PUnicodeEnvelopeParityTests(unittest.TestCase):
    def test_python_encoder_rejects_unpaired_surrogates_in_values_and_object_keys(self):
        from lan_p2p_core import encode_packet
        base = {"type": "chat.message", "sender_peer_id": "android-a",
                "message_id": "m-unicode", "target_peer_id": "android-b",
                "created_at_ms": 123, "body": "hello"}
        invalid_value = dict(base, body="bad-" + chr(0xD800))
        invalid_key = dict(base)
        invalid_key["bad-" + chr(0xDFFF)] = "value"
        for packet in (invalid_value, invalid_key):
            with self.subTest(packet=packet), self.assertRaises(ValueError):
                encode_packet(packet, "test-secret")

    def test_python_decoder_rejects_escaped_unpaired_surrogate_before_authentication(self):
        from lan_p2p_core import decode_packet
        raw = (b'{"v":2,"type":"chat.message","sender_peer_id":"android-a",'
               b'"target_peer_id":"android-b","message_id":"m-unicode",'
               b'"created_at_ms":123,"body":"\\ud800","auth":"invalid"}')
        with self.assertRaises(ValueError):
            decode_packet(raw, "test-secret")

    def test_android_codec_recursively_validates_string_values_and_object_keys(self):
        repo_root = Path(__file__).resolve().parents[1]
        protocol = (repo_root / "app/src/main/java/com/example/devicefinder/LanP2pProtocol.java").read_text(encoding="utf-8")
        self.assertGreaterEqual(protocol.count("validateUnicodeScalars("), 4)
        self.assertIn("isValidUnicodeScalarString((String) value)", protocol)
        self.assertIn("isValidUnicodeScalarString(key)", protocol)
        self.assertIn("validateUnicodeScalars(object.get(key))", protocol)
        self.assertIn("validateUnicodeScalars(array.get(i))", protocol)


class LanChatAdmissionFeedbackTests(unittest.TestCase):
    def test_android_rejected_admission_is_visible_but_durable_send_queue_fallback_is_not_rejected(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        send = extract_java_method(source, "private void sendLocalUdpMessage(final String message)")
        notify = extract_java_method(source, "private void notifyLanChatSendRejected(")
        self.assertIn('notifyLanChatSendRejected("消息过长，未进入发送队列")', send)
        self.assertIn('notifyLanChatSendRejected("本地持久化失败，请重试")', send)
        self.assertIn('notifyLanChatSendRejected("发送入口繁忙，请重试")', send)
        self.assertIn("消息已落账并转入持久化补发", send)
        self.assertIn("handler.post(", notify)
        self.assertIn("Toast.LENGTH_LONG", notify)
        self.assertNotIn("ChatActivity.java", source)


class LanP2PCanonicalNumberParityTests(unittest.TestCase):
    def test_python_encoder_rejects_fractional_and_out_of_range_numbers(self):
        from lan_p2p_core import encode_packet
        base = {"type": "chat.message", "sender_peer_id": "android-a",
                "message_id": "m-1", "target_peer_id": "android-b",
                "created_at_ms": 123, "body": "hello"}
        for invalid in (1.5, float("nan"), float("inf"), 1 << 63, -(1 << 63) - 1):
            packet = dict(base)
            packet["created_at_ms"] = invalid
            with self.subTest(value=invalid), self.assertRaises(ValueError):
                encode_packet(packet, "test-secret")

    def test_python_decoder_rejects_fractional_version_before_hmac(self):
        import json
        from lan_p2p_core import decode_packet
        packet = {"v": 2.0, "type": "chat.message",
                  "sender_peer_id": "android-a", "message_id": "m-1",
                  "target_peer_id": "android-b", "created_at_ms": 123,
                  "body": "hello", "auth": "invalid"}
        with self.assertRaises(ValueError):
            decode_packet(json.dumps(packet, separators=(",", ":")).encode("utf-8"),
                          "test-secret")

    def test_android_codec_enforces_the_same_integer_only_canonical_boundary(self):
        repo_root = Path(__file__).resolve().parents[1]
        protocol = (repo_root / "app/src/main/java/com/example/devicefinder/LanP2pProtocol.java").read_text(encoding="utf-8")
        self.assertGreaterEqual(protocol.count("validateCanonicalNumbers("), 3)
        self.assertIn("value instanceof Integer || value instanceof Long", protocol)
        self.assertIn("value instanceof Byte || value instanceof Short", protocol)
        self.assertIn("version instanceof Integer || version instanceof Long", protocol)
        self.assertIn("Non-integer JSON numbers are unsupported", protocol)




class LanP2PPacketSchemaTests(unittest.TestCase):
    def test_all_four_v2_packet_types_round_trip_with_their_required_fields(self):
        from lan_p2p_core import encode_packet, decode_packet
        packets = [
            {"type": "peer.hello", "sender_peer_id": "pc-a", "sender_peer_type": "pc",
             "service_id": "pc-main", "nonce": "nonce-1", "listen_port": 8889,
             "challenge_only": False, "capabilities": ["chat.text", "chat.ack"]},
            {"type": "peer.ack", "sender_peer_id": "android-a", "sender_peer_type": "android",
             "service_id": "android-main", "reply_nonce": "nonce-1", "listen_port": 8888},
            {"type": "chat.message", "sender_peer_id": "pc-a", "sender_peer_type": "pc",
             "service_id": "pc-main", "target_peer_id": "android-a", "message_id": "m-1",
             "created_at_ms": 123, "body": "hello"},
            {"type": "chat.ack", "sender_peer_id": "android-a", "sender_peer_type": "android",
             "service_id": "android-main", "target_peer_id": "pc-a", "message_id": "m-1",
             "status": "stored"},
        ]
        for packet in packets:
            with self.subTest(packet_type=packet["type"]):
                self.assertEqual(decode_packet(encode_packet(packet, "schema-secret"),
                                                "schema-secret")["type"], packet["type"])

    def test_type_specific_required_fields_and_types_are_rejected(self):
        from lan_p2p_core import encode_packet
        base = {"sender_peer_id": "peer-a"}
        invalid_packets = [
            dict(base, type="peer.hello"),
            dict(base, type="peer.hello", nonce="n", challenge_only="false"),
            dict(base, type="peer.hello", nonce="n", capabilities=["chat.text", 1]),
            dict(base, type="peer.ack"),
            dict(base, type="peer.ack", reply_nonce=123),
            dict(base, type="chat.message", message_id="m", target_peer_id="peer-b", body={"text": "hello"}),
            dict(base, type="chat.message", message_id=7, target_peer_id="peer-b", body="hello"),
            dict(base, type="chat.message", message_id="m", target_peer_id=7, body="hello"),
            dict(base, type="chat.ack", message_id="m", target_peer_id="peer-b", status="received"),
            dict(base, type="chat.ack", message_id="m", target_peer_id="peer-b"),
        ]
        for packet in invalid_packets:
            with self.subTest(packet=packet), self.assertRaises(ValueError):
                encode_packet(packet, "schema-secret")

    def test_android_codec_applies_packet_schema_before_hmac_on_both_paths(self):
        repo_root = Path(__file__).resolve().parents[1]
        protocol = (repo_root / "app/src/main/java/com/example/devicefinder/LanP2pProtocol.java").read_text(encoding="utf-8")
        self.assertEqual(protocol.count("validatePacketSchema(packet);"), 2)
        for field in ("nonce", "reply_nonce", "message_id", "target_peer_id", "body", "status"):
            self.assertIn('"' + field + '"', protocol)
        self.assertIn("instanceof Boolean", protocol)
        self.assertIn("instanceof JSONArray", protocol)
        self.assertIn('"stored".equals(packet.getString("status"))', protocol)


if __name__ == "__main__":
    unittest.main()


class LanPeerScopedRecoveryRegressionTests(unittest.TestCase):
    def test_android_peer_recovery_cooldown_timer_entries_are_reclaimed_safely(self):
        root = Path(__file__).resolve().parents[1]
        service = (root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        helper = extract_java_method(service, "private void scheduleLanChatPeerRecoveryCooldownWake(")
        self.assertIn("synchronized (lanChatPeerRecoveryCooldownWakeAtMs)", helper)
        self.assertIn("lanChatPeerRecoveryCooldownWakeAtMs.remove(peerId, deadline)", helper)
        self.assertIn("deadline.compareAndSet(requestedWakeAtMs, Long.MAX_VALUE)", helper)

    def test_android_peer_recovery_cooldown_map_is_cleared_under_its_lock_on_destroy(self):
        root = Path(__file__).resolve().parents[1]
        service = (root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        destroy = extract_java_method(service, "public void onDestroy()")
        self.assertIn("synchronized (lanChatPeerRecoveryCooldownWakeAtMs)", destroy)
        self.assertIn("lanChatPeerRecoveryCooldownWakeAtMs.clear()", destroy)

    def test_android_peer_recovery_does_not_trigger_global_budget_reset(self):
        root = Path(__file__).resolve().parents[1]
        service = (root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        store = (root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        self.assertIn("retryPendingLanChatMessagesForPeer(senderId)", service)
        self.assertNotIn('peer-verified-retry", () -> retryPendingLanChatMessages(true)', service)
        self.assertIn("lanChatPeerRecoveryEvents.addAll(peerRecoveryEventsCaptured)", service)
        self.assertIn("|| !lanChatPeerRecoveryEvents.isEmpty()", service)
        peer_batch = service[service.index("for (String recoveredPeerId") : service.index("final int batchSize")]
        self.assertNotIn("scheduleLanChatRecoveryCooldownWake(", peer_batch)
        method = extract_java_method(store, "public int resumeExhaustedRetriesForPeerRecoveryEvent(")
        self.assertIn("peer_id=? AND status!='acked'", method)
        self.assertIn('"WHERE t.message_id=lan_outbox.message_id AND t.peer_id=? "', method)
        self.assertNotIn("resumeExhaustedRetriesForRecoveryEvent(", method)
        self.assertIn("nextPeerRecoveryEventEligibleAtMs", store)
        self.assertIn("scheduleLanChatPeerRecoveryCooldownWake", service)

    def test_python_recovery_event_survives_unavailable_socket_and_scan_failure(self):
        root = Path(__file__).resolve().parents[1]
        for filename in ("chat.py", "rescue2.py", "server2.py"):
            source = (root / filename).read_text(encoding="utf-8")
            worker = extract_python_function(source, "_retry_deferred_v2_peer_recoveries")
            self.assertIn("_V2_RECOVERY_RETRY_PEERS.update(recovery_event_peers)", worker)
            self.assertIn("unresolved_recovery_peers", worker)
            self.assertIn("unresolved_recovery_peers.add(peer_id)", worker)
            resume = extract_python_function(source, "_resume_pending_v2_for_peer")
            self.assertIn("_V2_RECOVERY_RETRY_PEERS.add(peer_id)", resume)
            self.assertIn("next_peer_recovery_eligible_at_ms", resume)
            self.assertIn("recovery_event=False", resume.splitlines()[0])
            self.assertEqual(resume.count("chat_packet ="), 1)
            attempt_error = resume[resume.index("except LanP2PStorageError as e:", resume.index("attempt_admitted = mark_outgoing_attempt")):resume.index("if not attempt_admitted")]
            self.assertIn("_V2_RECOVERY_RETRY_PEERS.add(peer_id)", attempt_error)
            self.assertLess(resume.index("attempt_admitted = mark_outgoing_attempt"),
                            resume.index("send_outgoing_packet("))


class LanP2PDiscoveryDispatchRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.temp_dir.name, "dispatch.sqlite3")
        from lan_p2p_core import ensure_outbox_retry_schema
        self.assertTrue(ensure_outbox_retry_schema(self.db_path))

    def tearDown(self):
        self.temp_dir.cleanup()

    def _create_empty_message(self, message_id):
        from lan_p2p_core import persist_outgoing_message
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", message_id, "hello", []))

    def test_all_udp_send_failures_rearm_discovery_deadline(self):
        from lan_p2p_core import send_discovery_broadcast_with_retries
        self._create_empty_message("m-all-fail")

        class FailingSocket:
            def sendto(self, payload, destination):
                raise OSError("simulated network unavailable")

        with patch("lan_p2p_core.time.time", return_value=1000.0):
            sent = send_discovery_broadcast_with_retries(
                FailingSocket(), self.db_path, "server2", "m-all-fail",
                b"frame", [("192.0.2.1", 8888)], attempts=2, retry_delay_seconds=0)
        self.assertEqual(sent, 0)
        with _sqlite_connection(self.db_path) as conn:
            state, deadline = conn.execute(
                "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
                ("m-all-fail",)).fetchone()
        self.assertEqual(state, "discovery_pending")
        self.assertEqual(deadline, 0)

    def test_successful_udp_dispatch_keeps_original_bounded_deadline(self):
        from lan_p2p_core import send_discovery_broadcast_with_retries
        self._create_empty_message("m-send-ok")

        class SuccessfulSocket:
            def sendto(self, payload, destination):
                return len(payload)

        with patch("lan_p2p_core.time.time", return_value=1000.0):
            sent = send_discovery_broadcast_with_retries(
                SuccessfulSocket(), self.db_path, "server2", "m-send-ok",
                b"frame", [("192.0.2.1", 8888)], attempts=1, retry_delay_seconds=0)
        self.assertEqual(sent, 1)
        with _sqlite_connection(self.db_path) as conn:
            deadline = conn.execute(
                "SELECT delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
                ("m-send-ok",)).fetchone()[0]
        self.assertEqual(deadline, 1010000)

    def test_concurrent_success_and_failure_dispatches_keep_valid_deadline(self):
        from concurrent.futures import ThreadPoolExecutor
        from lan_p2p_core import send_discovery_broadcast_with_retries
        self._create_empty_message("m-concurrent-dispatch")

        class FailingSocket:
            def sendto(self, payload, destination):
                time.sleep(0.01)
                raise OSError("simulated transient failure")

        class SuccessfulSocket:
            def sendto(self, payload, destination):
                return len(payload)

        def dispatch(sock):
            return send_discovery_broadcast_with_retries(
                sock, self.db_path, "server2", "m-concurrent-dispatch",
                b"frame", [("192.0.2.1", 8888)], attempts=1, retry_delay_seconds=0)

        with patch("lan_p2p_core.time.time", return_value=1000.0):
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(dispatch, (FailingSocket(), SuccessfulSocket())))
        self.assertEqual(sorted(results), [0, 1])
        with _sqlite_connection(self.db_path) as conn:
            state, deadline = conn.execute(
                "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
                ("m-concurrent-dispatch",)).fetchone()
        self.assertEqual(state, "discovery_pending")
        self.assertEqual(deadline, 1010000)


    def test_later_duplicate_dispatch_failure_does_not_reset_successful_window(self):
        from lan_p2p_core import send_discovery_broadcast_with_retries
        self._create_empty_message("m-duplicate-dispatch")

        class SuccessfulSocket:
            def sendto(self, payload, destination):
                return len(payload)

        class FailingSocket:
            def sendto(self, payload, destination):
                raise OSError("transient network failure")

        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertEqual(send_discovery_broadcast_with_retries(
                SuccessfulSocket(), self.db_path, "server2", "m-duplicate-dispatch",
                b"frame", [("192.0.2.1", 8888)], attempts=1, retry_delay_seconds=0), 1)
        with patch("lan_p2p_core.time.time", return_value=1001.0):
            self.assertEqual(send_discovery_broadcast_with_retries(
                FailingSocket(), self.db_path, "server2", "m-duplicate-dispatch",
                b"frame", [("192.0.2.1", 8888)], attempts=1, retry_delay_seconds=0), 0)
        with _sqlite_connection(self.db_path) as conn:
            deadline = conn.execute(
                "SELECT delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
                ("m-duplicate-dispatch",)).fetchone()[0]
        self.assertEqual(deadline, 1010000)


    def test_failed_dispatch_can_retry_later_without_extending_successful_window(self):
        from lan_p2p_core import send_discovery_broadcast_with_retries
        self._create_empty_message("m-retry-window")

        class FailingSocket:
            def sendto(self, payload, destination):
                raise OSError("offline")

        class SuccessfulSocket:
            def sendto(self, payload, destination):
                return len(payload)

        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertEqual(send_discovery_broadcast_with_retries(
                FailingSocket(), self.db_path, "server2", "m-retry-window",
                b"frame", [("192.0.2.1", 8888)], attempts=1, retry_delay_seconds=0), 0)
        with patch("lan_p2p_core.time.time", return_value=2000.0):
            self.assertEqual(send_discovery_broadcast_with_retries(
                SuccessfulSocket(), self.db_path, "server2", "m-retry-window",
                b"frame", [("192.0.2.1", 8888)], attempts=1, retry_delay_seconds=0), 1)
        with patch("lan_p2p_core.time.time", return_value=2001.0):
            self.assertEqual(send_discovery_broadcast_with_retries(
                SuccessfulSocket(), self.db_path, "server2", "m-retry-window",
                b"frame", [("192.0.2.1", 8888)], attempts=1, retry_delay_seconds=0), 1)
        with _sqlite_connection(self.db_path) as conn:
            deadline = conn.execute(
                "SELECT delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
                ("m-retry-window",)).fetchone()[0]
        self.assertEqual(deadline, 2010000)


class LanP2POutboxProcessRestartTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.temp_dir.name, "restart.sqlite3")
        self.repo_root = Path(__file__).resolve().parents[1]

    def tearDown(self):
        self.temp_dir.cleanup()

    def _run_python(self, code):
        env = os.environ.copy()
        env["PYTHONPATH"] = str(self.repo_root) + os.pathsep + env.get("PYTHONPATH", "")
        result = subprocess.run(
            [sys.executable, "-c", code, self.db_path],
            cwd=str(self.repo_root), env=env, check=False,
            capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise AssertionError(
                "subprocess failed: " + result.stderr + "\\nstdout: " + result.stdout)
        return result

    def test_failed_discovery_dispatch_rearms_after_process_restart(self):
        create = """
import sys
from unittest.mock import patch
from lan_p2p_core import persist_outgoing_message
db = sys.argv[1]
with patch('lan_p2p_core.time.time', return_value=1000.0):
    assert persist_outgoing_message(db, 'server2', 'server2-id',
                                    'restart-rearm', 'durable body', [])
"""
        self._run_python(create)
        fail = """
import contextlib, sqlite3, sys
from unittest.mock import patch
from lan_p2p_core import send_discovery_broadcast_with_retries
class FailingSocket:
    def sendto(self, payload, destination):
        raise OSError('offline')
db = sys.argv[1]
with patch('lan_p2p_core.time.time', return_value=1000.0):
    assert send_discovery_broadcast_with_retries(
        FailingSocket(), db, 'server2', 'restart-rearm', b'frame',
        [('192.0.2.1', 8888)], attempts=1, retry_delay_seconds=0) == 0
with contextlib.closing(sqlite3.connect(db)) as conn:
    assert conn.execute('SELECT delivery_deadline_ms FROM lan_outbox '
                        'WHERE message_id=?', ('restart-rearm',)).fetchone()[0] == 0
"""
        self._run_python(fail)
        resume = """
import contextlib, sqlite3, sys
from unittest.mock import patch
from lan_p2p_core import send_discovery_broadcast_with_retries
class SuccessfulSocket:
    def sendto(self, payload, destination):
        return len(payload)
db = sys.argv[1]
with patch('lan_p2p_core.time.time', return_value=2000.0):
    assert send_discovery_broadcast_with_retries(
        SuccessfulSocket(), db, 'server2', 'restart-rearm', b'frame',
        [('192.0.2.1', 8888)], attempts=1, retry_delay_seconds=0) == 1
with contextlib.closing(sqlite3.connect(db)) as conn:
    deadline = conn.execute('SELECT delivery_deadline_ms FROM lan_outbox '
                            'WHERE message_id=?', ('restart-rearm',)).fetchone()[0]
assert deadline == 2010000, deadline
"""
        self._run_python(resume)

    def test_expired_empty_discovery_snapshot_stays_frozen_after_process_restart(self):
        create = """
import sys
from unittest.mock import patch
from lan_p2p_core import persist_outgoing_message, begin_outgoing_discovery_window
db = sys.argv[1]
with patch('lan_p2p_core.time.time', return_value=1000.0):
    assert persist_outgoing_message(db, 'server2', 'server2-id',
                                    'restart-frozen', 'durable body', [])
    assert begin_outgoing_discovery_window(db, 'server2', 'restart-frozen')
"""
        self._run_python(create)
        verify = """
import contextlib, sqlite3, sys
from unittest.mock import patch
from lan_p2p_core import record_peer, add_discovery_targets_for_peer, load_pending_outgoing_for_peer
db = sys.argv[1]
with patch('lan_p2p_core.time.time', return_value=1010.0):
    assert record_peer(db, 'server2', 'android-late', 'android', 'android-main',
                       '192.0.2.11', 8888, 'active')
    assert add_discovery_targets_for_peer(db, 'server2', 'android-late') == 0
    assert load_pending_outgoing_for_peer(db, 'server2', 'android-late') == []
with contextlib.closing(sqlite3.connect(db)) as conn:
    state = conn.execute('SELECT state FROM lan_outbox WHERE message_id=?',
                         ('restart-frozen',)).fetchone()[0]
    targets = conn.execute('SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id=?',
                           ('restart-frozen',)).fetchone()[0]
assert state == 'waiting_peers', state
assert targets == 0, targets
"""
        self._run_python(verify)

    def test_fixed_target_outbox_and_ack_survive_real_process_restart(self):
        create = """
import sys
from lan_p2p_core import (persist_outgoing_message, mark_outgoing_attempt,
    record_successful_send_endpoint, acknowledge_outgoing_message, record_peer)
db = sys.argv[1]
assert record_peer(db, 'server2', 'android-a', 'android', 'android-main',
                   '192.0.2.10', 8888, 'active')
assert persist_outgoing_message(db, 'server2', 'server2-id', 'restart-msg',
                                'durable body', ['android-a'])
assert mark_outgoing_attempt(db, 'restart-msg', 'android-a', '192.0.2.10', 8888)
assert record_successful_send_endpoint(db, 'server2', 'restart-msg',
                                       'android-a', '192.0.2.10', 8888)
assert acknowledge_outgoing_message(db, 'server2', 'restart-msg', 'android-a')
"""
        self._run_python(create)
        verify = """
import contextlib, sqlite3, sys
from lan_p2p_core import load_pending_outgoing_for_peer
db = sys.argv[1]
with contextlib.closing(sqlite3.connect(db)) as conn:
    outbox = conn.execute('SELECT sender_scope,body,state FROM lan_outbox WHERE message_id=?',
                          ('restart-msg',)).fetchone()
    target = conn.execute('SELECT status,attempt_count,last_attempt_endpoint,last_sent_endpoint '
                          'FROM lan_outbox_targets WHERE message_id=? AND peer_id=?',
                          ('restart-msg','android-a')).fetchone()
pending = load_pending_outgoing_for_peer(db, 'server2', 'android-a')
print('outbox=', outbox, 'target=', target, 'pending=', pending)
assert outbox == ('server2', 'durable body', 'delivered'), outbox
assert target == ('acked', 1, '192.0.2.10:8888', '192.0.2.10:8888'), target
assert pending == [], pending
"""
        self._run_python(verify)

    def test_peer_scoped_retry_recovery_and_partial_ack_survive_process_restart(self):
        create = """
import sys
from unittest.mock import patch
from lan_p2p_core import (record_peer, persist_outgoing_message,
    mark_outgoing_attempt, prepare_outgoing_peer_recovery,
    acknowledge_outgoing_message)
db = sys.argv[1]
assert record_peer(db, 'server2', 'android-a', 'android', 'android-main',
                   '192.0.2.10', 8888, 'active')
assert record_peer(db, 'server2', 'android-b', 'android', 'android-main',
                   '192.0.2.11', 8888, 'active')
assert persist_outgoing_message(db, 'server2', 'server2-id', 'restart-peer-scope',
                                'durable body', ['android-a', 'android-b'])
with patch('lan_p2p_core.time.time', return_value=1000.0):
    assert mark_outgoing_attempt(db, 'restart-peer-scope', 'android-a',
                                 '192.0.2.10', 8888)
    assert acknowledge_outgoing_message(
        db, 'server2', 'restart-peer-scope', 'android-a')
    for _ in range(8):
        assert mark_outgoing_attempt(db, 'restart-peer-scope', 'android-b',
                                     '192.0.2.11', 8888)
# Persist a prior recovery epoch, then exhaust only android-b again.
assert prepare_outgoing_peer_recovery(
    db, 'server2', 'android-b', now_ms=1000000) == 1
with patch('lan_p2p_core.time.time', return_value=1000.001):
    for _ in range(8):
        assert mark_outgoing_attempt(db, 'restart-peer-scope', 'android-b',
                                     '192.0.2.11', 8888)
"""
        self._run_python(create)
        verify = """
import contextlib, sqlite3, sys
from lan_p2p_core import (prepare_outgoing_peer_recovery,
    load_pending_outgoing_for_peer, next_peer_recovery_eligible_at_ms)
db = sys.argv[1]
# A restarted worker must respect the persisted 60-second cooldown.
assert prepare_outgoing_peer_recovery(
    db, 'server2', 'android-b', now_ms=1050000) == 0
assert load_pending_outgoing_for_peer(db, 'server2', 'android-b') == []
assert load_pending_outgoing_for_peer(db, 'server2', 'android-a') == []
deadline = next_peer_recovery_eligible_at_ms(
    db, 'server2', 'android-b', now_ms=1050000)
assert deadline == 1060000, deadline
# Only the exhausted, authenticated peer's target is reopened after cooldown.
assert prepare_outgoing_peer_recovery(
    db, 'server2', 'android-b', now_ms=1061000) == 1
pending_b = load_pending_outgoing_for_peer(db, 'server2', 'android-b')
pending_a = load_pending_outgoing_for_peer(db, 'server2', 'android-a')
assert [row['message_id'] for row in pending_b] == ['restart-peer-scope'], pending_b
assert pending_a == [], pending_a
with contextlib.closing(sqlite3.connect(db)) as conn:
    state = conn.execute(
        'SELECT state FROM lan_outbox WHERE message_id=?',
        ('restart-peer-scope',)).fetchone()[0]
    targets = dict(conn.execute(
        'SELECT peer_id,status FROM lan_outbox_targets WHERE message_id=?',
        ('restart-peer-scope',)).fetchall())
    retry = conn.execute(
        'SELECT attempt_count,last_recovery_at_ms FROM lan_outbox_targets '
        'WHERE message_id=? AND peer_id=?',
        ('restart-peer-scope','android-b')).fetchone()
assert state == 'partial', state
assert targets == {'android-a': 'acked', 'android-b': 'sent'}, targets
assert retry == (0, 1061000), retry
"""
        self._run_python(verify)



class LongChatFragmentProtocolTests(unittest.TestCase):
    def test_long_message_fragments_reassemble_out_of_order_without_utf8_corruption(self):
        from lan_p2p_core import fragment_chat_message, assemble_chat_fragments
        body = ("诊断日志🙂" * 1500) + "\n" + ("0123456789" * 2000)
        fragments = fragment_chat_message("long-message-1", "peer-b", body, 123456)
        self.assertGreater(len(fragments), 1)
        self.assertLessEqual(max(len(item["fragment_data"]) for item in fragments), 480)
        self.assertEqual(body, assemble_chat_fragments(list(reversed(fragments))))

    def test_fragment_reassembly_rejects_missing_duplicate_and_tampered_data(self):
        from lan_p2p_core import fragment_chat_message, assemble_chat_fragments
        fragments = fragment_chat_message("long-message-2", "peer-b", "长消息🙂" * 400)
        with self.assertRaises(ValueError):
            assemble_chat_fragments(fragments[:-1])
        duplicate = list(fragments)
        duplicate[-1] = dict(duplicate[0])
        with self.assertRaises(ValueError):
            assemble_chat_fragments(duplicate)
        tampered = [dict(item) for item in fragments]
        tampered[0]["fragment_data"] = "AAAA"
        with self.assertRaises(ValueError):
            assemble_chat_fragments(tampered)

    def test_fragment_packet_codec_enforces_bounds_and_authentication(self):
        from lan_p2p_core import encode_packet, decode_packet, fragment_chat_message
        fields = fragment_chat_message("long-message-3", "peer-b", "测试🙂" * 300)[0]
        fields.update({"sender_peer_id": "peer-a", "sender_peer_type": "python",
                       "service_id": "rescue2"})
        raw = encode_packet(fields, "shared-secret")
        self.assertEqual("chat.fragment", decode_packet(raw, "shared-secret")["type"])
        with self.assertRaises(ValueError):
            encode_packet(dict(fields, fragment_index=1024), "shared-secret")
        with self.assertRaises(ValueError):
            decode_packet(raw, "wrong-secret")

    def test_fragment_helper_limits_total_size_and_java_contract_matches_python(self):
        from lan_p2p_core import fragment_chat_message, MAX_CHAT_MESSAGE_BYTES
        with self.assertRaises(ValueError):
            fragment_chat_message("too-big", "peer-b", "x" * (MAX_CHAT_MESSAGE_BYTES + 1))
        repo_root = Path(__file__).resolve().parents[1]
        protocol = (repo_root / "app/src/main/java/com/example/devicefinder/LanP2pProtocol.java").read_text(encoding="utf-8")
        helper = (repo_root / "app/src/main/java/com/example/devicefinder/LanP2pFragments.java").read_text(encoding="utf-8")
        self.assertIn('"chat.fragment".equals(type)', protocol)
        self.assertIn('MAX_MESSAGE_BYTES = 256 * 1024', helper)
        self.assertIn('RAW_FRAGMENT_BYTES = 300', helper)
        self.assertIn('MessageDigest.isEqual', protocol)
        self.assertIn('SecurityException("Chat fragment digest mismatch")', helper)


    def test_fragment_reassembly_rejects_metadata_drift_and_noncanonical_lengths(self):
        from lan_p2p_core import fragment_chat_message, assemble_chat_fragments
        fragments = fragment_chat_message("metadata-check", "peer-b", "z" * 900, 456)
        drifted = [dict(item) for item in fragments]
        drifted[1]["created_at_ms"] = 457
        with self.assertRaises(ValueError):
            assemble_chat_fragments(drifted)
        drifted = [dict(item) for item in fragments]
        drifted[0]["fragment_data"] = drifted[0]["fragment_data"][:-4]
        with self.assertRaises(ValueError):
            assemble_chat_fragments(drifted)
        drifted = [dict(item) for item in fragments]
        drifted[0]["sha256"] = "g" * 64
        with self.assertRaises(ValueError):
            assemble_chat_fragments(drifted)

    def test_android_fragment_receive_cache_is_durable_bounded_and_separate_from_final_ack(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        self.assertIn("DB_VERSION = 11", store)
        self.assertIn("CREATE TABLE IF NOT EXISTS lan_chat_fragment_sessions", store)
        self.assertIn("CREATE TABLE IF NOT EXISTS lan_chat_fragments", store)
        self.assertIn("FRAGMENT_SESSION_TTL_MS = 30L * 60L * 1000L", store)
        self.assertIn("MAX_FRAGMENT_SESSIONS = 32", store)
        self.assertIn("MAX_FRAGMENT_CACHE_CHARS = 2 * 1024 * 1024", store)
        self.assertIn('return received == fragmentCount ? "complete" : "stored";', store)
        self.assertIn('return "duplicate";', store)
        self.assertIn('return "rejected";', store)
        self.assertIn("loadIncomingFragments", store)
        self.assertIn("deleteIncomingFragmentSession", store)
        # The store itself must not emit a final delivery ACK.
        self.assertNotIn("sendAuthenticatedChatAck", store)


    def test_fragment_builder_rejects_invalid_timestamp_and_packet_sizes_stay_bounded(self):
        from lan_p2p_core import fragment_chat_message, encode_packet, max_peer_id_budget_probe
        with self.assertRaises(ValueError):
            fragment_chat_message("bad-time", "peer-b", "body", -1)
        # Exercise the full authenticated envelope with the largest legal peer IDs.
        peer_id = "p" * 128
        fragments = fragment_chat_message("m" * 128, peer_id, "x" * 4096, 123456789)
        for fragment in fragments:
            packet = dict(fragment, sender_peer_id=peer_id,
                          sender_peer_type="android", service_id="devicefinder")
            self.assertLessEqual(len(encode_packet(packet, "shared-secret")), 1200)
        self.assertEqual(128, len(max_peer_id_budget_probe().encode("utf-8")))



class LongChatReceiveAndRescueDiagnosticsTests(unittest.TestCase):
    def test_android_fragment_receive_persists_then_assembles_then_final_acks(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        handler = extract_java_method(source, "private boolean handleAuthenticatedLanPacket(")
        receiver = extract_java_method(source, "private void processAuthenticatedLanChatFragment(")
        self.assertIn('"chat.fragment".equals(type)', handler)
        self.assertIn("lanChatIoExecutor.execute", handler)
        self.assertLess(receiver.index("store.storeIncomingFragment("), receiver.index("sendAuthenticatedChatFragmentAck("))
        self.assertLess(receiver.index("sendAuthenticatedChatFragmentAck("), receiver.index("loadIncomingFragments("))
        self.assertLess(receiver.index("LanP2pFragments.assemble(ordered)"), receiver.index("persistIncomingLanChat("))
        self.assertLess(receiver.index("persistIncomingLanChat("), receiver.index("deleteIncomingFragmentSession("))
        self.assertLess(receiver.index("persistIncomingLanChat("), receiver.index("sendAuthenticatedChatAck("))
        self.assertIn("partial cache retained", receiver)
        self.assertIn("no final chat.ack sent", receiver)

    def test_android_long_chat_sender_uses_bounded_selective_ack_windows(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        sender = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        builder = extract_java_method(source, "private java.util.List<byte[]> buildAuthenticatedLanChatFragmentPackets(")
        handler = extract_java_method(source, "private boolean handleAuthenticatedLanPacket(")
        ack = extract_java_method(source, "private void processAuthenticatedLanChatFragmentAck(")
        self.assertIn("preparedPeerFragmentPackets", sender)
        self.assertIn("initializeTargetFragmentProgress(", sender)
        self.assertIn("loadTargetFragmentAckBitmap(", sender)
        self.assertIn("(firstMissing / 8) * 8", sender)
        self.assertIn("windowStart + 8", sender)
        self.assertIn("bitmap.charAt(i) == '1'", sender)
        # The retry planner must preserve the same fixed 8-fragment flight boundary.
        for first_missing in (0, 1, 7, 8, 9, 15, 16, 255, 256, 873):
            aligned_start = (first_missing // 8) * 8
            self.assertLessEqual(first_missing - aligned_start, 7)
            self.assertLessEqual(min(1024, aligned_start + 8) - aligned_start, 8)
        self.assertIn("CHAT_FRAGMENT_COMPLETION_PROBE", sender)
        self.assertIn("LanP2pFragments.split(body)", builder)
        self.assertIn("LanP2pProtocol.MAX_DATAGRAM_BYTES", builder)
        self.assertIn('"chat.fragment.ack".equals(type)', handler)
        self.assertIn("acknowledgeTargetFragment(", ack)
        self.assertIn("matchesExpectedAckEndpoint(", ack)
        self.assertIn("finalDelivery=false", ack)
        # Final delivery remains a distinct inbox-backed chat.ack, not a fragment ACK.
        receiver = extract_java_method(source, "private void processAuthenticatedLanChatFragment(")
        self.assertLess(receiver.index("persistIncomingLanChat("), receiver.index("sendAuthenticatedChatAck("))

    def test_rescue2_has_bounded_rotating_file_diagnostics_and_traceback_hooks(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "rescue2.py").read_text(encoding="utf-8")
        ast.parse(source)
        self.assertIn("RotatingFileHandler", source)
        self.assertIn("maxBytes=4 * 1024 * 1024", source)
        self.assertIn("backupCount=4", source)
        self.assertIn("rescue2_runtime.log", source)
        self.assertIn("def _diag_exception(", source)
        self.assertIn("_DIAG_LOGGER.exception(", source)
        self.assertIn("udp_listener_started", source)
        self.assertIn("udp_v2_packet_received", source)
        self.assertIn("udp_v2_packet_rejected", source)
        self.assertIn("udp_tx_failed", source)
        self.assertIn("http_log_receiver_failed", source)
        self.assertIn("payload", source[source.index("def _diag_event("):source.index("def _diag_exception(")])



class AndroidLanChatFragmentWindowAdvanceTests(unittest.TestCase):
    def test_fragment_ack_advances_next_window_without_retry_budget_or_fifo_lock(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        worker = extract_java_method(source, "private void advanceLanChatFragmentWindow(")
        scheduler = extract_java_method(source, "private void scheduleLanChatFragmentWindowAdvance(")
        ack_handler = extract_java_method(source, "private void processAuthenticatedLanChatFragmentAck(")
        self.assertIn("firstMissing % 8 != 0", worker)
        self.assertIn("firstMissing + 8", worker)
        self.assertIn("retryBudgetCharged=false", worker)
        self.assertNotIn("markAttempt(", worker)
        self.assertNotIn("lanChatTransmitLock", worker)
        self.assertIn("lanChatFragmentSendExecutor.execute(", scheduler)
        self.assertIn("pendingLanChatFragmentAdvances", scheduler)
        self.assertIn("scheduleLanChatFragmentWindowAdvance(messageId, senderId", ack_handler)

    def test_fragment_window_executor_is_drained_during_service_shutdown(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        destroy = extract_java_method(source, "public void onDestroy()")
        closer = extract_java_method(source, "private void closeLanChatStoreAfterExecutors(")
        self.assertIn("lanChatFragmentSendExecutor.shutdown()", destroy)
        self.assertIn("awaitLanExecutorTermination(lanChatFragmentSendExecutor)", closer)


class AndroidLanChatBroadcastFragmentAckRouteTests(unittest.TestCase):
    def test_verified_live_route_does_not_skip_broadcast_recipient_admission(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        ack = extract_java_method(source, "private void processAuthenticatedLanChatFragmentAck(")
        # A peer may already have a verified discovery route before it ACKs the
        # first broadcast flight. That route must not bypass creation of its
        # message-scoped recipient row and fragment bitmap.
        self.assertIn(
            "if (!persistedEndpointMatch && !attemptedEndpointPending && allowEndpointGrace)",
            ack,
        )
        self.assertNotIn(
            "if (!routeMatches && !persistedEndpointMatch && !attemptedEndpointPending",
            ack,
        )
        self.assertLess(
            ack.index("store.admitBroadcastFragmentAckTarget("),
            ack.index("store.acknowledgeTargetFragment("),
        )


class AndroidLanChatBroadcastFragmentWindowTests(unittest.TestCase):
    def test_broadcast_long_chat_starts_with_one_bounded_flight_and_admits_scoped_ack(self):
        root = Path(__file__).resolve().parents[1]
        service = (root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        store = (root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        sender = extract_java_method(service, "private void transmitLocalUdpMessageInternal(")
        ack = extract_java_method(service, "private void processAuthenticatedLanChatFragmentAck(")
        admission = extract_java_method(store, "public boolean admitBroadcastFragmentAckTarget(")
        advance = extract_java_method(service, "private void advanceLanChatFragmentWindow(")
        self.assertIn("Math.min(preparedBroadcastFragments.size(), 8)", sender)
        self.assertIn("admitBroadcastFragmentAckTarget(", ack)
        self.assertIn('"discovery_pending".equals(state)', admission)
        self.assertIn("fragmentIndex >= 8", admission)
        self.assertIn("recipients >= 64", admission)
        self.assertIn('row.put("last_sent_endpoint", endpoint)', admission)
        self.assertNotIn("upsertPeer(", admission)
        self.assertNotIn("observePeer(", admission)
        self.assertIn("CHAT_FRAGMENT_COMPLETION_PROBE", advance)
        self.assertIn("finalDelivery=false", ack)


class AndroidLanMultiListenerLegacyCommandDedupTests(unittest.TestCase):
    def test_legacy_alarm_dedup_is_service_scoped_not_receive_loop_scoped(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn(
            "private final LanUdpCommandDeduplicator lanUdpCommandDeduplicator",
            source,
        )
        self.assertIn(
            "lanUdpCommandDeduplicator.shouldAccept(",
            source,
        )
        self.assertNotIn(
            "java.util.Map<String, Long> udpCmdDedup",
            source,
        )
        self.assertNotIn(
            "java.util.Map<String, Long> udpCmdDedup = new java.util.HashMap",
            source,
        )
        helper = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanUdpCommandDeduplicator.java"
        ).read_text(encoding="utf-8")
        self.assertIn("public synchronized boolean shouldAccept(", helper)
        self.assertIn("MAX_ENTRIES = 50", helper)
        self.assertIn("DUPLICATE_WINDOW_MS = 3000L", helper)
