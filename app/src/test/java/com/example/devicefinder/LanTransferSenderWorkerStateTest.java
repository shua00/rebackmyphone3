package com.example.devicefinder;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class LanTransferSenderWorkerStateTest {
    @Test public void noOfferResponseMeansWaitingForNetwork() {
        assertEquals("waiting_for_network",
                LanTransferSenderWorker.waitingStateForOffer(false));
    }

    @Test public void acknowledgedOfferMeansWaitingForRecipientAcceptance() {
        assertEquals("waiting_for_acceptance",
                LanTransferSenderWorker.waitingStateForOffer(true));
    }
}
