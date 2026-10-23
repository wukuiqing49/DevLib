package com.beautysdk.rfb

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AnalyzerLifetimeTest {
    @Test fun closeIsIdempotentAndPreventsInference() {
        val lifetime = AnalyzerLifetime()
        lifetime.requireOpen()
        assertTrue(lifetime.beginClose())
        assertFalse(lifetime.beginClose())
        assertThrows(IllegalStateException::class.java) { lifetime.requireOpen() }
    }

    @Test fun foreignThreadCannotCloseOwnedSession() {
        val lifetime = AnalyzerLifetime()
        val failure = AtomicReference<Throwable>()
        Thread { try { lifetime.beginClose() } catch (error: Throwable) { failure.set(error) } }
            .apply { start(); join(5000); assertFalse(isAlive) }
        assertTrue(failure.get() is IllegalStateException)
        lifetime.requireOpen()
        assertTrue(lifetime.beginClose())
    }
}
