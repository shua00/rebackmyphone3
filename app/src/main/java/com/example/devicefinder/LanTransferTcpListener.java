package com.example.devicefinder;

import android.content.Context;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded TCP listener for the LAN transfer data plane.
 *
 * The listener binds only to the caller-selected Wi-Fi address. Accepted sockets inherit
 * that local interface; the owner must close/recreate this listener when the selected Wi-Fi
 * Network or LinkProperties change. Secret provisioning and trust policy are injected.
 */
public final class LanTransferTcpListener implements AutoCloseable {
    public interface FailureListener { void onFailure(String stage, Exception failure); }

    public static final int DEFAULT_PORT = 38471;
    public static final int MAX_CONNECTIONS = 2;
    public static final int QUEUE_CAPACITY = 4;
    private static final int ACCEPT_BACKLOG = 8;

    private final Context context;
    private final LanTransferStore store;
    private final LanTransferFilesystem filesystem;
    private final String localPeerId;
    private final String sharedSecret;
    private final LanTransferAuthenticator.TrustedPeerAuthorizer authorizer;
    private final FailureListener failures;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<Socket> activeSockets =
            Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Socket, Boolean>());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            MAX_CONNECTIONS, MAX_CONNECTIONS, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "LanTransfer-TCP-Worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    public LanTransferTcpListener(Context context, LanTransferStore store,
            LanTransferFilesystem filesystem, String localPeerId, String sharedSecret,
            LanTransferAuthenticator.TrustedPeerAuthorizer authorizer,
            FailureListener failures) {
        if (context == null || store == null || filesystem == null
                || localPeerId == null || localPeerId.isEmpty()
                || sharedSecret == null || sharedSecret.length() < 16
                || authorizer == null)
            throw new IllegalArgumentException("Listener requires store, filesystem, identity, provisioned secret and trust policy");
        this.context = context.getApplicationContext();
        this.store = store;
        this.filesystem = filesystem;
        this.localPeerId = localPeerId;
        this.sharedSecret = sharedSecret;
        this.authorizer = authorizer;
        this.failures = failures;
    }

    public synchronized void start(InetAddress wifiAddress, int port) throws IOException {
        if (closed.get()) throw new IllegalStateException("Listener is closed");
        if (serverSocket != null) throw new IllegalStateException("Listener already started");
        if (wifiAddress == null || wifiAddress.isAnyLocalAddress() || wifiAddress.isLoopbackAddress())
            throw new IllegalArgumentException("A concrete non-loopback Wi-Fi address is required");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid TCP port");
        ServerSocket listener = new ServerSocket();
        boolean success = false;
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(wifiAddress, port), ACCEPT_BACKLOG);
            serverSocket = listener;
            Thread thread = new Thread(this::acceptLoop, "LanTransfer-TCP-Accept");
            thread.setDaemon(true);
            acceptThread = thread;
            thread.start();
            success = true;
        } finally {
            if (!success) {
                try { listener.close(); } catch (IOException ignored) {}
            }
        }
    }

    public int getBoundPort() {
        ServerSocket current = serverSocket;
        return current == null ? -1 : current.getLocalPort();
    }

    public boolean isRunning() {
        ServerSocket current = serverSocket;
        return !closed.get() && current != null && current.isBound() && !current.isClosed();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            final Socket socket;
            try {
                ServerSocket current = serverSocket;
                if (current == null) return;
                socket = current.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                // The listener is bound to the selected Wi-Fi address. Accepted sockets
                // inherit that local interface; never call Network.bindSocket after accept,
                // because the accepted socket is already connected.
                if (closed.get()) {
                    socket.close();
                    return;
                }
                activeSockets.add(socket);
                if (closed.get()) {
                    activeSockets.remove(socket);
                    closeQuietly(socket);
                    return;
                }
                try {
                    workers.execute(() -> serveOne(socket));
                } catch (java.util.concurrent.RejectedExecutionException saturated) {
                    activeSockets.remove(socket);
                    closeQuietly(socket);
                    report("admission", saturated);
                }
            } catch (IOException failure) {
                if (!closed.get()) report("accept", failure);
                return;
            } catch (RuntimeException failure) {
                if (!closed.get()) report("accept-loop", failure);
                return;
            }
        }
    }

    private void serveOne(Socket socket) {
        try (Socket owned = socket) {
            LanTransferTcpSession session = new LanTransferTcpSession(context, store, filesystem,
                    localPeerId, sharedSecret, authorizer, System::currentTimeMillis);
            session.serve(owned);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            if (!closed.get()) report("session", failure);
        } finally {
            activeSockets.remove(socket);
        }
    }

    private void report(String stage, Exception failure) {
        if (failures != null) {
            try { failures.onFailure(stage, failure); } catch (RuntimeException ignored) {}
        }
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ServerSocket listener = serverSocket;
        if (listener != null) {
            try { listener.close(); } catch (IOException ignored) {}
        }
        for (Socket socket : activeSockets) closeQuietly(socket);
        workers.shutdownNow();
        Thread accept = acceptThread;
        if (accept != null && accept != Thread.currentThread()) {
            try { accept.join(2000L); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        try { workers.awaitTermination(2L, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
