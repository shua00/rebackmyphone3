package com.example.devicefinder;

import android.net.Network;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.InetAddress;

/**
 * The only supported socket-binding boundary for LAN paths.
 *
 * Network-backed paths use Network.bindSocket. OEM hotspot AP interfaces that are not
 * represented by ConnectivityManager can still host listeners on their exact local address,
 * and outgoing direct-LAN sockets are source-address-bound after the caller validates that the
 * peer is in the same subnet. This is not equivalent to Network.bindSocket: if the OS cannot
 * route that source/subnet directly, the connection fails rather than falling back to wildcard.
 */
public final class LanSocketBinding {
    private LanSocketBinding() { }

    /**
     * Bind a UDP listener to one exact LAN address and port.
     *
     * Unlike bindDatagramSocket (an outbound socket helper which reserves an ephemeral
     * source port for local-interface candidates), this method owns the listener bind.
     * Exact-address binding permits independent STA/AP listeners on the same UDP port
     * without a wildcard socket silently capturing traffic for another path.
     */
    public static void bindDatagramListenerSocket(LanNetworkPath path,
            DatagramSocket socket, int port) throws IOException {
        if (socket == null) throw new IllegalArgumentException("Socket is required");
        if (path == null) throw new IOException("LAN path is required");
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Listener port is invalid");
        if (socket.isClosed() || socket.isBound())
            throw new IOException("Listener socket must be open and unbound");
        if (path.kind == LanNetworkPath.Kind.ANDROID_NETWORK) {
            Network network = requireAndroidNetwork(path);
            network.bindSocket(socket);
        } else if (path.kind != LanNetworkPath.Kind.LOCAL_INTERFACE_CANDIDATE
                || path.network != null) {
            throw new IOException("Unsupported LAN listener path");
        }
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new java.net.InetSocketAddress(path.localAddress, port));
        if (!path.localAddress.equals(socket.getLocalAddress())
                || socket.getLocalPort() != port) {
            try { socket.close(); } catch (Exception ignored) { }
            throw new IOException("UDP listener did not bind to the requested LAN path");
        }
    }

    public static void bindDatagramSocket(LanNetworkPath path, DatagramSocket socket)
            throws IOException {
        if (socket == null) throw new IllegalArgumentException("Socket is required");
        if (path == null) throw new IOException("LAN path is required");
        if (path.kind == LanNetworkPath.Kind.ANDROID_NETWORK) {
            Network network = requireAndroidNetwork(path);
            network.bindSocket(socket);
        } else {
            // AP interfaces are not always exposed as Android Network objects.
            // For a candidate, pin the UDP sender's source address; discovery
            // receivers remain wildcard-bound and filter packets by this subnet.
            socket.bind(new java.net.InetSocketAddress(path.localAddress, 0));
        }
    }

    public static void bindStreamSocket(LanNetworkPath path, Socket socket)
            throws IOException {
        if (socket == null) throw new IllegalArgumentException("Socket is required");
        if (path == null) throw new IOException("LAN path is required");
        if (path.kind == LanNetworkPath.Kind.ANDROID_NETWORK) {
            Network network = requireAndroidNetwork(path);
            network.bindSocket(socket);
        } else {
            if (socket.isConnected() || socket.isBound())
                throw new IOException("Candidate-path socket must be unconnected and unbound");
            // A local AP interface may have no ConnectivityManager Network. Binding
            // its concrete source address plus same-subnet endpoint validation avoids
            // wildcard/default-route fallback for direct LAN peers.
            socket.bind(new java.net.InetSocketAddress(path.localAddress, 0));
        }
    }

    /**
     * ServerSocket cannot be passed to Network.bindSocket. Binding it to the exact local
     * address from the selected interface snapshot constrains inbound traffic to that address.
     */
    public static void validateServerSocketAddress(LanNetworkPath path, InetAddress address)
            throws IOException {
        if (path == null || address == null || !path.localAddress.equals(address)
                || path.interfaceName == null || path.interfaceName.isEmpty()
                || path.prefixLength < 1 || path.prefixLength > 32) {
            throw new IOException("Server socket address does not match selected LAN path");
        }
        // ServerSocket cannot be passed to Network.bindSocket. Binding to the exact
        // local AP address is supported even when the AP interface is only a candidate.
    }

    private static Network requireAndroidNetwork(LanNetworkPath path) throws IOException {
        if (path == null || path.kind != LanNetworkPath.Kind.ANDROID_NETWORK
                || path.network == null) {
            throw new IOException("LAN path is a local-interface candidate; "
                    + "public Android socket binding is unavailable");
        }
        return path.network;
    }
}
