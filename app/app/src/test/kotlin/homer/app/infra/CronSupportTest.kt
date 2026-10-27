package homer.app.infra

import homer.app.models.CronConfig
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CronSupportTest {
    @Test
    fun `validate returns null for a valid expression with an explicit timezone`() {
        val config = CronConfig(expression = "0 9 * * 1", timezone = "Europe/Warsaw")
        assertNull(CronSupport.validate(config))
    }

    @Test
    fun `validate returns a reason for an invalid expression`() {
        assertReason(CronConfig(expression = "not a cron", timezone = "UTC"))
    }

    @Test
    fun `validate returns a reason for an invalid timezone`() {
        assertReason(CronConfig(expression = "* * * * *", timezone = "Not/AZone"))
    }

    @Test
    fun `validate returns a reason for a six-field expression because UNIX cron has no seconds field`() {
        // Observed: CronType.UNIX is five fields, so a leading seconds field is rejected.
        assertReason(CronConfig(expression = "0 * * * * *", timezone = "UTC"))
    }

    @Test
    fun `executionTimeFor returns the requested zone`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val parsed = CronSupport.executionTimeFor(CronConfig(expression = "0 9 * * *", timezone = zone.id))
        assertEquals(zone, parsed.first)
    }

    @Test
    fun `executionTimeFor throws for an invalid expression`() {
        assertFailsWith<IllegalArgumentException> {
            CronSupport.executionTimeFor(CronConfig(expression = "not a cron", timezone = "UTC"))
        }
    }

    @Test
    fun `executionTimeFor throws for an invalid timezone`() {
        assertFailsWith<java.time.DateTimeException> {
            CronSupport.executionTimeFor(CronConfig(expression = "* * * * *", timezone = "Not/AZone"))
        }
    }

    @Test
    fun `an in-range five-field expression is valid in the requested zone`() =
        runTest {
            val zones = Arb.element("UTC", "Europe/Warsaw", "America/New_York", "Asia/Tokyo")
            checkAll(Arb.int(0..59), Arb.int(0..23), Arb.int(1..31), Arb.int(1..12), Arb.int(0..7), zones) {
                minute,
                hour,
                day,
                month,
                weekday,
                zone,
                ->
                val config = CronConfig(expression = "$minute $hour $day $month $weekday", timezone = zone)
                assertNull(CronSupport.validate(config), config.toString())
                assertEquals(ZoneId.of(zone), CronSupport.executionTimeFor(config).first)
            }
        }

    private fun assertReason(config: CronConfig) {
        val reason = CronSupport.validate(config)
        assertNotNull(reason)
        assertTrue(reason.isNotBlank(), "expected a reason for $config")
    }
}
