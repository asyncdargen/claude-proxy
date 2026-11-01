package org.claudeproxy.datapath

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportDedupTest {
    @Test fun `a retried report is taken once`() {
        val id = "report-${System.nanoTime()}"
        assertTrue(ReportDedup.firstSighting(id))
        assertFalse(ReportDedup.firstSighting(id))
    }

    @Test fun `reports without an id always pass`() {
        assertTrue(ReportDedup.firstSighting(null))
        assertTrue(ReportDedup.firstSighting(null))
        assertTrue(ReportDedup.firstSighting(""))
    }
}
