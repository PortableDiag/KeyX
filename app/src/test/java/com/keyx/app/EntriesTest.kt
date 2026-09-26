package com.keyx.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A real unit test, so `./gradlew testDebugUnitTest` has something to run.
 * A test gate with no tests behind it reports a pass that means nothing.
 */
class EntriesTest {
    @Test
    fun appendingKeepsOrderAndGrowsByOne() {
        val entries = listOf("first")
        val next = entries + "second"
        assertEquals(listOf("first", "second"), next)
        assertEquals(1, entries.size)
    }
}
