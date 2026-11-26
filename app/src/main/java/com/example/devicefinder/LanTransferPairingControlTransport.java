package com.example.devicefinder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dedicated bounded TCP transport for LAN-transfer pairing control messages. */
public final class LanTransferPairingControlTransport {
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 10000;
    private static final int MAX_ACTIVE = 2;
    private static final int QUEUE_CAPACITY = 8;

    public interface Handler {
        LanTransferPairingControlProtocol.Message onMessage(
                LanTransferPairingControlProtocol.Message request) throws Exception;
    }

    private LanTransferPairingControlTransport() {}

    public static LanTransferPairingControlProtocol.Message exchange(
            InetAddress address, int port, LanTransferPairingControlProtocol.Message request,
            int connectTimeoutMs, int readTimeoutMs) throws IOException {
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()
                || port < 1 || port > 65535 || connectTimeoutMs < 1 || readTimeoutMs < 1)
            throw new IllegalArgumentException("Invalid pairing endpoint or timeout");
        byte[] frame = LanTransferPairingControlProtocol.encode(request);
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(address, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(frame.length);
            out.write(frame);
            out.flush();
            LanTransferPairingControlProtocol.Message decoded =
                    LanTransferPairingControlProtocol.decode(readFrame(
                            new DataInputStream(socket.getInputStream())));
            if (request.type == LanTransferPairingControlProtocol.Type.REQUEST
                    && decoded.type != LanTransferPairingControlProtocol.Type.RESPONSE)
                throw new IOException("Pairing request received a non-response message");
            if (!decoded.sessionId.equals(request.sessionId)
                    || !decoded.senderPeerId.equals(request.targetPeerId)
                    || !decoded.targetPeerId.equals(request.senderPeerId)
                    || decoded.sequence != request.sequence)
                throw new IOException("Pairing response identity, session, or sequence mismatch");
            return decoded;
        }
    }

    public static final class Server implements AutoCloseable {
        private final String localPeerId;
        private final Handler handler;
        private final LanTransferPairingReplayGuard replayGuard;
        private final long monotonicOriginNanos = System.nanoTime();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Set<Socket> sockets = Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<Socket, Boolean>());
        private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
                MAX_ACTIVE, MAX_ACTIVE, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "LanPairing-Control-Worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        private volatile ServerSocket serverSocket;
        private volatile Thread acceptThread;

        public Server(String localPeerId, Handler handler, long replayWindowMs) {
            if (localPeerId == null || !localPeerId.matches("[A-Za-z0-9._:-]{1,128}")
                    || handler == null) throw new IllegalArgumentException("Pairing server identity and handler are required");
            this.localPeerId = localPeerId;
            this.handler = handler;
            this.replayGuard = new LanTransferPairingReplayGuard(replayWindowMs);
        }

        public synchronized void start(InetAddress bindAddress, int port) throws IOException {
            if (closed.get() || serverSocket != null) throw new IllegalStateException("Pairing server is not startable");
            if (bindAddress == null || bindAddress.isAnyLocalAddress()
                    || bindAddress.isMulticastAddress() || port < 0 || port > 65535)
                throw new IllegalArgumentException("A concrete bind address and valid port are required");
            ServerSocket socket = new ServerSocket();
            boolean started = false;
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(bindAddress, port), 8);
                serverSocket = socket;
                acceptThread = new Thread(this::acceptLoop, "LanPairing-Control-Accept");
                acceptThread.setDaemon(true);
                acceptThread.start();
                started = true;
            } finally {
                if (!started) socket.close();
            }
        }

        public int getBoundPort() {
            ServerSocket current = serverSocket;
            return current == null || current.isClosed() ? -1 : current.getLocalPort();
        }

        public boolean isRunning() {
            ServerSocket current = serverSocket;
            return !closed.get() && current != null && current.isBound() && !current.isClosed();
        }

        private void acceptLoop() {
            while (!closed.get()) {
                try {
                    Socket accepted = serverSocket.accept();
                    accepted.setTcpNoDelay(true);
                    accepted.setSoTimeout(DEFAULT_READ_TIMEOUT_MS);
                    if (closed.get()) { accepted.close(); return; }
                    sockets.add(accepted);
                    try {
                        workers.execute(() -> serveOne(accepted));
                    } catch (java.util.concurrent.RejectedExecutionException full) {
                        sockets.remove(accepted);
                        closeQuietly(accepted);
                    }
                } catch (IOException failure) {
                    if (!closed.get()) close();
                    return;
                }
            }
        }

        private void serveOne(Socket socket) {
            try (Socket owned = socket) {
                LanTransferPairingControlProtocol.Message request =
                        LanTransferPairingControlProtocol.decode(readFrame(
                                new DataInputStream(owned.getInputStream())));
                long nowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - monotonicOriginNanos);
                if (!localPeerId.equals(request.targetPeerId)
                        || request.senderPeerId.equals(localPeerId)
                        || !replayGuard.accept(request.sessionId, request.senderPeerId,
                                request.sequence, nowMs)) return;
                LanTransferPairingControlProtocol.Message response = handler.onMessage(request);
                if (response == null) return;
                if ((request.type == LanTransferPairingControlProtocol.Type.REQUEST
                            && response.type != LanTransferPairingControlProtocol.Type.RESPONSE)
                        || !response.sessionId.equals(request.sessionId)
                        || !response.senderPeerId.equals(localPeerId)
                        || !response.targetPeerId.equals(request.senderPeerId)
                        || response.sequence != request.sequence)
                    throw new IOException("Pairing handler returned a mismatched response");
                byte[] encoded = LanTransferPairingControlProtocol.encode(response);
                DataOutputStream out = new DataOutputStream(owned.getOutputStream());
                out.writeInt(encoded.length);
                out.write(encoded);
                out.flush();
            } catch (IOException | RuntimeException failure) {
                // Malformed, unauthenticated, timed-out, or replayed connections fail closed.
            } catch (Exception failure) {
                // Application policy/consent failures must not terminate the accept loop.
            } finally {
                sockets.remove(socket);
            }
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            ServerSocket listener = serverSocket;
            if (listener != null) try { listener.close(); } catch (IOException ignored) {}
            for (Socket socket : sockets) closeQuietly(socket);
            workers.shutdownNow();
            Thread accept = acceptThread;
            if (accept != null && accept != Thread.currentThread()) {
                try { accept.join(1500L); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static byte[] readFrame(DataInputStream in) throws IOException {
        int length;
        try { length = in.readInt(); } catch (EOFException end) { throw end; }
        if (length < 32 || length > LanTransferPairingControlProtocol.MAX_FRAME_BYTES)
            throw new IOException("Invalid pairing transport frame length");
        byte[] frame = new byte[length];
        in.readFully(frame);
        return frame;
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
