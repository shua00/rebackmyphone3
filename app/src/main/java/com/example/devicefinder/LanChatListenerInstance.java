package com.example.devicefinder;

import java.net.DatagramSocket;

/**
 * Immutable identity and resource owner token for one LAN chat receive-loop generation.
 * This object creates no thread, timer, socket, or probe; TrackingService supplies resources.
 */
public final class LanChatListenerInstance {
    public final String pathFingerprint;
    public final long generation;

    private DatagramSocket socket;
    private Thread thread;
    private boolean stopRequested;

    public LanChatListenerInstance(String pathFingerprint, long generation) {
        if (generation < 0L) throw new IllegalArgumentException("generation must be non-negative");
        this.pathFingerprint = pathFingerprint;
        this.generation = generation;
    }

    /** Attach exactly one socket; a stopped or replaced instance cannot acquire resources. */
    public synchronized boolean attachSocket(DatagramSocket candidate) {
        if (candidate == null || candidate.isClosed() || stopRequested || socket != null) return false;
        socket = candidate;
        return true;
    }

    /** Attach exactly one worker before start; no worker is created by this class. */
    public synchronized boolean attachThread(Thread candidate) {
        if (candidate == null || candidate.getState() != Thread.State.NEW
                || stopRequested || thread != null) return false;
        thread = candidate;
        return true;
    }

    public synchronized boolean owns(DatagramSocket candidateSocket, Thread candidateThread) {
        return !stopRequested && socket == candidateSocket
                && (candidateThread == null || thread == candidateThread);
    }

    public synchronized boolean ownsSocket(DatagramSocket candidateSocket) {
        return socket == candidateSocket;
    }

    public synchronized boolean ownsThread(Thread candidateThread) {
        return thread == candidateThread;
    }

    /** Mark stale before closing/interruption; callers perform blocking I/O outside this monitor. */
    public synchronized void requestStop() {
        stopRequested = true;
    }

    public synchronized boolean isStopRequested() {
        return stopRequested;
    }

    public synchronized DatagramSocket socket() {
        return socket;
    }

    public synchronized Thread thread() {
        return thread;
    }
}
