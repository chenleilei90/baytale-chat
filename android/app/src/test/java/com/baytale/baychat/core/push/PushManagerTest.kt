package com.baytale.baychat.core.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushManagerTest {
    @Test
    fun registrationIdUpdatesStateFlow() {
        PushManager.onRegistrationIdReceived("  reg_jpush_sample_001  ")
        assertEquals("reg_jpush_sample_001", PushManager.registrationId.value)
    }
}
