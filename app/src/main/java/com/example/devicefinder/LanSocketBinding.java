package com.example.devicefinder;

import android.net.Network;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;

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
        Network network = requireAndroidNetwork(path);
        network.bindSocket(socket);
    }

    public static void bindStreamSocket(LanNetworkPath path, Socket socket)
            throws IOException {
        if (socket == null) throw new IllegalArgumentException("Socket is required");
        Network network = requireAndroidNetwork(path);
        network.bindSocket(socket);
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
