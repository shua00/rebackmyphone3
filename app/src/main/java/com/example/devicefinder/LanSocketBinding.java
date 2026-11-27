package com.example.devicefinder;

import android.net.Network;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.InetAddress;

/**
 * The only supported socket-binding boundary for LAN paths.
 *
 * A local interface candidate is not a bindable Android Network. Android's public Java API
 * does not provide a portable SO_BINDTODEVICE equivalent for such a candidate, so fail closed
 * rather than binding a wildcard socket to the process default (which may be cellular/VPN).
 */
public final class LanSocketBinding {
    private LanSocketBinding() { }

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
     * address from a Network-backed snapshot constrains inbound traffic to that interface.
     * This is address binding, not a claim that Android has bound the ServerSocket via Network.
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
