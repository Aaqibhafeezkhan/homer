package homer.app.infra

import homer.app.models.Retain
import homer.app.models.Workspace
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PurgeScheduleTest {
    private val day = 86_400L

    @Test
    fun `of derives the record window and retained globs from the retain block`() {
        val schedule =
            PurgeSchedule.of(Workspace(purgeAfter = "1d", retain = Retain(recordFor = "7d", artifacts = listOf("decision.json"))))

        assertEquals(PurgeSchedule(day, 7 * day, listOf("decision.json")), schedule)
    }

    @Test
    fun `without a retain block the record dies with the directory and nothing is retained`() {
        assertEquals(PurgeSchedule(2 * day, 2 * day, emptyList()), PurgeSchedule.of(Workspace(purgeAfter = "2d")))
    }

    @Test
    fun `DEFAULT is the model's default workspace`() {
        assertEquals(PurgeSchedule(5 * day, 5 * day, emptyList()), PurgeSchedule.DEFAULT)
    }
}
