package lucid.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lucid.app.data.CueEventEntity
import lucid.app.data.CueRecognition
import lucid.app.data.EpochEntity
import lucid.app.data.ExperimentEntity
import lucid.app.data.ExperimentTemplates
import lucid.app.data.JournalEntity
import lucid.app.data.LucidDatabase
import lucid.app.data.SampleEntity
import lucid.app.data.SessionEntity
import lucid.app.data.armForNight
import lucid.app.data.arms
import lucid.app.data.encodeArms
import lucid.core.engine.CueMode
import lucid.core.engine.CueingConfig
import lucid.core.model.DataOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device or emulator: `./gradlew :app:connectedDebugAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: LucidDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LucidDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun sessionRoundTrip() = runTest {
        db.sessions().insert(
            SessionEntity(
                id = "s1", startedAt = 1000, origin = DataOrigin.SIMULATED, device = "sim", provider = "sim",
                linkKind = "IN_APP_SIMULATOR", configJson = "{}", estimatorId = "e", policyId = "p",
            ),
        )
        db.samples().insertAll(List(100) { SampleEntity(0, "s1", 1000L + it * 1000, SampleEntity.HR, 60f, 0f, 0, false) })
        db.epochs().upsertAll(
            listOf(EpochEntity("s1", 0, 1000, 60f, 1f, 2f, 40f, 3f, 0, 1f, false, 0.1f, 0.2f, 0.3f, 0.4f, 0.8f, 3, "[]")),
        )
        db.cues().insert(CueEventEntity(sessionId = "s1", t = 31_000, epochIdx = 0, action = "DELIVER", failuresJson = "[]", explanation = "x",
            pRem = 0.9f, pWake = 0f, confidence = 0.8f, quality = 1f, intensity = 0.3f, patternId = "pulse-200", cueId = "c1"))
        db.cues().setOutcome("c1", "EMULATED", 32_000, "ok")
        db.cues().setResponse("c1", "NO_REACTION", "none")

        assertEquals(DataOrigin.SIMULATED, db.sessions().get("s1")!!.origin)
        assertEquals(100, db.samples().count("s1"))
        assertEquals(3, db.epochs().forSession("s1").single().stage)
        val cue = db.cues().forSession("s1").single()
        assertEquals("EMULATED", cue.outcome)
        assertEquals("NO_REACTION", cue.response)
        assertEquals(1, db.sessions().observeAll().first().size)
    }

    @Test
    fun journalLinksToSession() = runTest {
        val id = db.journal().insert(JournalEntity(createdAt = 1, updatedAt = 1, sessionId = "s1", lucid = true, cueRecognition = CueRecognition.RECOGNIZED))
        val j = db.journal().get(id)!!
        assertTrue(j.lucid)
        assertEquals(CueRecognition.RECOGNIZED, j.cueRecognition)
        assertEquals(1, db.journal().forSession("s1").size)
    }

    @Test
    fun experimentArmsAreStableAndBalanced() = runTest {
        val e = ExperimentEntity("x", "n", "h", 0, true, "RANDOM", true, encodeArms(ExperimentTemplates.cueVsBaseline(CueingConfig())), seed = 42)
        db.experiments().upsert(e)
        val stored = db.experiments().active()!!
        assertEquals(2, stored.arms().size)
        assertEquals(CueMode.BASELINE, stored.arms().first { it.id == "baseline" }.cueing.mode)
        val nights = (0 until 200).map { stored.armForNight(it).id }
        assertEquals("deterministic", nights, (0 until 200).map { stored.armForNight(it).id })
        val share = nights.count { it == "cue" } / 200.0
        assertTrue("roughly balanced: $share", share in 0.35..0.65)
        val alt = stored.copy(assignment = "ALTERNATE")
        assertEquals(listOf("baseline", "cue", "baseline", "cue"), (0 until 4).map { alt.armForNight(it).id })
    }
}
