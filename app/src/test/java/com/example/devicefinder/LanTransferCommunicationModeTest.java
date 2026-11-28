package com.example.devicefinder;

import org.junit.Test;
import static org.junit.Assert.*;

public class LanTransferCommunicationModeTest {
    @Test public void allModesKeepMandatorySecurityBaseline() {
        for (LanTransferCommunicationMode.Mode mode : LanTransferCommunicationMode.Mode.values()) {
            for (LanTransferCommunicationMode.Traffic traffic : LanTransferCommunicationMode.Traffic.values()) {
                LanTransferCommunicationMode profile = LanTransferCommunicationMode.resolve(
                        mode, traffic, LanTransferCommunicationMode.LinkQuality.GOOD);
                assertTrue(profile.hasSecurityBaseline());
                assertTrue(profile.authenticatedPeerRequired);
                assertTrue(profile.encryptionRequired);
                assertTrue(profile.integrityRequired);
                assertTrue(profile.replayProtectionRequired);
            }
        }
    }

    @Test public void highPerformanceOnlyChangesBoundedTransportTuning() {
        LanTransferCommunicationMode secure = LanTransferCommunicationMode.resolve(
                LanTransferCommunicationMode.Mode.SECURE,
                LanTransferCommunicationMode.Traffic.FILE,
                LanTransferCommunicationMode.LinkQuality.GOOD);
        LanTransferCommunicationMode fast = LanTransferCommunicationMode.resolve(
                LanTransferCommunicationMode.Mode.HIGH_PERFORMANCE,
                LanTransferCommunicationMode.Traffic.FILE,
                LanTransferCommunicationMode.LinkQuality.GOOD);
        assertTrue(fast.maxInFlightMessages > secure.maxInFlightMessages);
        assertTrue(fast.maxInFlightBytes > secure.maxInFlightBytes);
        assertTrue(fast.ackBatchSize > secure.ackBatchSize);
        assertTrue(fast.hasSecurityBaseline());
    }

    @Test public void autoUsesTrafficAndLinkQualityWithoutWeakeningSecurity() {
        LanTransferCommunicationMode fastFile = LanTransferCommunicationMode.resolve(
                LanTransferCommunicationMode.Mode.AUTO,
                LanTransferCommunicationMode.Traffic.FILE,
                LanTransferCommunicationMode.LinkQuality.GOOD);
        LanTransferCommunicationMode safeFile = LanTransferCommunicationMode.resolve(
                LanTransferCommunicationMode.Mode.AUTO,
                LanTransferCommunicationMode.Traffic.FILE,
                LanTransferCommunicationMode.LinkQuality.POOR);
        LanTransferCommunicationMode voice = LanTransferCommunicationMode.resolve(
                LanTransferCommunicationMode.Mode.AUTO,
                LanTransferCommunicationMode.Traffic.VOICE,
                LanTransferCommunicationMode.LinkQuality.GOOD);
        assertEquals(LanTransferCommunicationMode.Mode.HIGH_PERFORMANCE, fastFile.effectiveMode);
        assertEquals(LanTransferCommunicationMode.Mode.SECURE, safeFile.effectiveMode);
        assertEquals(LanTransferCommunicationMode.Mode.SECURE, voice.effectiveMode);
        assertTrue(fastFile.hasSecurityBaseline());
        assertTrue(safeFile.hasSecurityBaseline());
        assertTrue(voice.hasSecurityBaseline());
    }

    @Test public void sessionProfileIsImmutableSnapshotAndDoesNotFollowLaterSettingChanges() {
        LanTransferCommunicationMode active = LanTransferCommunicationMode.resolve(
                LanTransferCommunicationMode.Mode.SECURE,
                LanTransferCommunicationMode.Traffic.FILE,
                LanTransferCommunicationMode.LinkQuality.UNKNOWN);
        LanTransferCommunicationMode next = LanTransferCommunicationMode.resolve(
                LanTransferCommunicationMode.Mode.HIGH_PERFORMANCE,
                LanTransferCommunicationMode.Traffic.FILE,
                LanTransferCommunicationMode.LinkQuality.GOOD);
        assertEquals(LanTransferCommunicationMode.Mode.SECURE, active.effectiveMode);
        assertEquals(LanTransferCommunicationMode.Mode.HIGH_PERFORMANCE, next.effectiveMode);
        assertEquals(2, active.maxInFlightMessages);
    }

    @Test public void rejectsMissingPolicyInputs() {
        try {
            LanTransferCommunicationMode.resolve(null,
                    LanTransferCommunicationMode.Traffic.FILE,
                    LanTransferCommunicationMode.LinkQuality.GOOD);
            fail("missing mode must be rejected");
        } catch (IllegalArgumentException expected) { }
    }
}
