package com.wkq.nas.transfer

import org.junit.Assert.*
import org.junit.Test

class TransferConnectivityTest {
    @Test fun localNetworkDoesNotNeedInternetValidation() {
        assertTrue(TransferNetwork(connected = true, metered = false).allowed(false))
    }
    @Test fun offlineDoesNotStartEvenWhenMeteredIsAllowed() {
        assertFalse(TransferNetwork(connected = false, metered = false).allowed(true))
    }
    @Test fun meteredNetworkRequiresOptIn() {
        assertFalse(TransferNetwork(connected = true, metered = true).allowed(false))
        assertTrue(TransferNetwork(connected = true, metered = true).allowed(true))
    }
}
