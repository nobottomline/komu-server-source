package suwayomi.tachidesk.util

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HASchedulerTest {
    @Test
    fun `empty scheduler has no hibernation timer and cancels it with the last job`() {
        assertFalse(HAScheduler.hasHibernateChecker)
        val task = HAScheduler.schedule({}, 3_600_000, 3_600_000, "energy-fixture")
        try {
            assertTrue(HAScheduler.hasHibernateChecker)
            HAScheduler.reschedule(task, 7_200_000)
            assertTrue(HAScheduler.hasHibernateChecker)
        } finally {
            assertNotNull(HAScheduler.deschedule(task))
        }
        assertFalse(HAScheduler.hasHibernateChecker)
        HAScheduler.deschedule("")
        assertFalse(HAScheduler.hasHibernateChecker)
    }

    @Test
    fun `cron and interval owners retain the checker until both are removed`() {
        val interval = HAScheduler.schedule({}, 3_600_000, 3_600_000, "energy-fixture")
        val cron = HAScheduler.scheduleCron({}, "0 0 1 1 *", "energy-fixture")
        try {
            HAScheduler.deschedule(interval)
            assertTrue(HAScheduler.hasHibernateChecker)
            HAScheduler.rescheduleCron(cron, "0 0 2 1 *")
        } finally {
            HAScheduler.deschedule(interval)
            HAScheduler.descheduleCron(cron)
        }
        assertFalse(HAScheduler.hasHibernateChecker)
    }
}
