package com.almus.ytdownloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStateTest {
    @Test fun terminalStates() {
        assertEquals(setOf(EngineState.Completed, EngineState.Failed, EngineState.Cancelled), EngineState.entries.filter { it.isTerminal }.toSet())
    }

    @Test fun activeStatesHoldASlot() {
        assertEquals(setOf(EngineState.Connecting, EngineState.Running, EngineState.Verifying), EngineState.entries.filter { it.isActive }.toSet())
    }

    @Test fun busyMeansWorkingOrAboutToStart() {
        assertTrue(EngineState.Queued.isBusy)
        assertTrue(EngineState.WaitingToRetry.isBusy)
        assertTrue(EngineState.Running.isBusy)
        assertFalse(EngineState.Paused.isBusy)
        assertFalse(EngineState.WaitingForNetwork.isBusy)
        assertFalse(EngineState.Completed.isBusy)
    }

    @Test fun unknownNamesAreTreatedAsFailed() {
        assertEquals(EngineState.Failed, EngineState.fromName("FromTheFuture"))
        assertEquals(EngineState.Failed, EngineState.fromName(null))
        EngineState.entries.forEach { assertEquals(it, EngineState.fromName(it.name)) }
    }
}
