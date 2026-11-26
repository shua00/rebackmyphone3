package com.example.devicefinder;

import java.net.Inet4Address;
import java.net.InetAddress;

/** IPv4 directed-broadcast calculation for LAN discovery; /31 and /32 have no broadcast. */
public final class LanTransferIpv4Subnet {
    private LanTransferIpv4Subnet() {}

    public static Inet4Address directedBroadcast(Inet4Address address, int prefixLength) {
        if (address == null || prefixLength < 1 || prefixLength > 30) return null;
        byte[] raw = address.getAddress();
        long value = ((raw[0] & 255L) << 24) | ((raw[1] & 255L) << 16)
                | ((raw[2] & 255L) << 8) | (raw[3] & 255L);
        long mask = (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
        long broadcast = (value & mask) | (~mask & 0xffffffffL);
        byte[] bytes = new byte[] {
                (byte) (broadcast >>> 24), (byte) (broadcast >>> 16),
                (byte) (broadcast >>> 8), (byte) broadcast };
        try {
            return (Inet4Address) InetAddress.getByAddress(bytes);
        } catch (java.net.UnknownHostException impossible) {
            throw new IllegalStateException("Four-byte IPv4 address rejected", impossible);
        }
    }
}
