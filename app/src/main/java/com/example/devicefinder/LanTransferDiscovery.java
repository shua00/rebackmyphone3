package com.example.devicefinder;

import android.content.Context;
import android.content.Intent;
import android.net.Network;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Independent, unauthenticated LAN candidate discovery for file transfer.
 * Packets contain identity/address hints only; the TCP mutual-authentication handshake remains
 * the sole authority for peer identity and authorization.
 */
public final class LanTransferDiscovery implements AutoCloseable {
    public interface PortProvider { int getListeningPort(); }

    public static final int UDP_PORT = 38472;
    private static final String MAGIC = "LTFD1";
    private static final long BEACON_INTERVAL_MS = 15_000L;
    private final Context context;
    private final Network network;
    private final String localPeerId;
    private final Inet4Address localAddress;
    private final Inet4Address broadcastAddress;
    private final String networkFingerprint;
    private final PortProvider portProvider;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile DatagramSocket receiver;
    private volatile Thread thread;

    public LanTransferDiscovery(Context context, Network network, String localPeerId,
            Inet4Address localAddress, Inet4Address broadcastAddress,
            String networkFingerprint, PortProvider portProvider) {
        if (context == null || network == null || localPeerId == null
                || !localPeerId.matches("[A-Za-z0-9._:-]{1,128}")
                || localAddress == null || broadcastAddress == null
                || networkFingerprint == null || networkFingerprint.isEmpty()
                || portProvider == null) {
            throw new IllegalArgumentException("Invalid file-transfer discovery configuration");
        }
        this.context = context.getApplicationContext();
        this.network = network;
        this.localPeerId = localPeerId;
        this.localAddress = localAddress;
        this.broadcastAddress = broadcastAddress;
        this.networkFingerprint = networkFingerprint;
        this.portProvider = portProvider;
    }

    public synchronized void start() throws IOException {
        if (closed.get()) throw new IllegalStateException("Discovery is closed");
        if (thread != null) return;
        DatagramSocket socket = new DatagramSocket(null);
        boolean ready = false;
        try {
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            network.bindSocket(socket);
            socket.bind(new InetSocketAddress(localAddress, UDP_PORT));
            socket.setSoTimeout(1000);
            receiver = socket;
            Thread worker = new Thread(this::runLoop, "LanTransfer-Discovery");
            worker.setDaemon(true);
            thread = worker;
            worker.start();
            ready = true;
        } finally {
            if (!ready) socket.close();
        }
    }

    public boolean isRunning() { return !closed.get() && thread != null && thread.isAlive(); }

    private void runLoop() {
        long nextBeaconAt = 0L;
        byte[] buffer = new byte[256];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            while (!closed.get()) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now >= nextBeaconAt) {
                    announce();
                    nextBeaconAt = now + BEACON_INTERVAL_MS;
                }
                try {
                    packet.setLength(buffer.length);
                    receiver.receive(packet);
                    if (closed.get()) break;
                    acceptCandidate(packet);
                } catch (java.net.SocketTimeoutException timeout) {
                    // Bounded wait lets close() stop the receiver promptly.
                }
            }
        } catch (IOException failure) {
            if (!closed.get()) {
                android.util.Log.w("LanTransferDiscovery",
                        "Independent discovery stopped: " + failure.getClass().getSimpleName());
            }
        } finally {
            DatagramSocket current = receiver;
            if (current != null) current.close();
        }
    }

    private void announce() {
        int port;
        try { port = portProvider.getListeningPort(); }
        catch (RuntimeException unavailable) { port = 0; }
        if (port < 0 || port > 65535) port = 0;
        byte[] payload = (MAGIC + "|" + localPeerId + "|" + port)
                .getBytes(StandardCharsets.US_ASCII);
        DatagramSocket sender = null;
        try {
            sender = new DatagramSocket(null);
            sender.setBroadcast(true);
            network.bindSocket(sender);
            sender.send(new DatagramPacket(payload, payload.length,
                    broadcastAddress, UDP_PORT));
        } catch (IOException | RuntimeException failure) {
            if (!closed.get()) android.util.Log.d("LanTransferDiscovery",
                    "Beacon unavailable: " + failure.getClass().getSimpleName());
        } finally {
            if (sender != null) sender.close();
        }
    }

    private void acceptCandidate(DatagramPacket packet) {
        if (!(packet.getAddress() instanceof Inet4Address)
                || packet.getAddress().isLoopbackAddress()
                || packet.getAddress().equals(localAddress)
                || packet.getLength() < 8 || packet.getLength() > 200) return;
        String message = new String(packet.getData(), packet.getOffset(),
                packet.getLength(), StandardCharsets.US_ASCII);
        String[] fields = message.split("\\|", -1);
        if (fields.length != 3 || !MAGIC.equals(fields[0])
                || !fields[1].matches("[A-Za-z0-9._:-]{1,128}")) return;
        if (localPeerId.equals(fields[1])) return;
        final int port;
        try { port = Integer.parseInt(fields[2]); }
        catch (NumberFormatException invalid) { return; }
        if (port < 0 || port > 65535) return;
        try {
            LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, fields[1],
                    packet.getAddress().getHostAddress(), port, networkFingerprint,
                    System.currentTimeMillis());
            context.sendBroadcast(new Intent("ACTION_LAN_TRANSFER_PEERS_UPDATED")
                    .setPackage(context.getPackageName()));
        } catch (RuntimeException invalid) {
            // A malformed discovery hint is disposable and never changes authentication state.
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        DatagramSocket current = receiver;
        if (current != null) current.close();
        Thread worker = thread;
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(1500L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
