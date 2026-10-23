package lucid.app.session

import android.util.Log
import androidx.room.withTransaction
import lucid.app.data.LucidDatabase
import lucid.app.data.SampleEntity
import lucid.app.data.SessionEntity
import lucid.app.data.SessionEventEntity
import lucid.app.data.SessionStatus
import lucid.core.engine.CueAction
import lucid.core.engine.CueMode
import lucid.core.engine.EngineConfig
import lucid.core.engine.EngineEvent
import lucid.core.engine.HmmStageEstimator
import lucid.core.engine.SessionSummary
import lucid.core.engine.replayNight
import lucid.core.model.HeartRateSample
import lucid.core.model.IbiSample
import lucid.core.model.MotionSample
import lucid.core.model.SampleQuality
import lucid.core.model.SensorSample
import lucid.core.model.SensorStatus
import lucid.core.model.SensorStatusSample
import lucid.core.model.SleepStage

/**
 * Nights recorded with an older stage model are re-estimated with the current one from their stored
 * samples, so every report shows the current model. Only model outputs change: stages,
 * probabilities, sleep onset and "would cue" decisions. A night in which cues were delivered keeps
 * its record exactly as it happened.
 */
class Reanalysis(private val db: LucidDatabase) {
    suspend fun run() {
        val model = HmmStageEstimator().id
        for (s in db.sessions().all()) {
            if (s.status == SessionStatus.RECORDING || s.isSimulated || s.estimatorId == model) continue
            if (db.cues().forSession(s.id).any { it.action == CueAction.DELIVER.name }) continue
            try {
                reanalyse(s, model)
            } catch (e: Exception) {
                Log.e(TAG, "re-analysing night ${s.id} failed", e)
            }
        }
    }

    private suspend fun reanalyse(s: SessionEntity, model: String) {
        val samples = db.samples().forSession(s.id).mapNotNull(::toSample)
        if (samples.isEmpty()) return
        val recorded = runCatching { SessionManager.json.decodeFromString(EngineConfig.serializer(), s.configJson) }.getOrDefault(EngineConfig())
        // Re-estimation never pretends a cue was sent: decisions are logged as "would cue".
        val config = recorded.copy(cueing = recorded.cueing.copy(mode = CueMode.BASELINE))
        val r = replayNight(s.startedAt, config, samples)
        val summary = r.summary
        db.withTransaction {
            db.epochs().deleteFor(s.id)
            db.epochs().upsertAll(r.events.filterIsInstance<EngineEvent.EpochProcessed>().map { SessionManager.epochRow(s.id, it) })
            db.cues().deleteFor(s.id)
            for (d in r.events.filterIsInstance<EngineEvent.CueDecided>()) db.cues().insert(SessionManager.cueRow(s.id, d.decision))
            db.events().deleteType(s.id, "sleep_onset")
            r.sleepOnsetMs?.let { db.events().insert(SessionEventEntity(sessionId = s.id, t = it, type = "sleep_onset", detail = "estimated sleep onset")) }
            db.events().insert(
                SessionEventEntity(
                    sessionId = s.id, t = s.endedAt ?: s.startedAt, type = "reanalysed",
                    detail = "stages re-estimated with $model (recorded with ${s.estimatorId})",
                ),
            )
            db.sessions().update(
                s.copy(
                    estimatorId = model,
                    summaryJson = SessionManager.json.encodeToString(SessionSummary.serializer(), summary),
                    sleepOnsetAt = summary.sleepOnsetMs,
                    estSleepMin = summary.estimatedSleepMin,
                    estRemMin = summary.estimatedMin[SleepStage.REM],
                    cuesDelivered = summary.cuesDelivered,
                    cuesWouldDeliver = summary.cuesWouldDeliver,
                    suspectedArousals = summary.suspectedArousals,
                    meanQuality = summary.meanQuality,
                ),
            )
        }
        Log.i(TAG, "night ${s.id}: re-estimated with $model (was ${s.estimatorId})")
    }

    private fun toSample(row: SampleEntity): SensorSample? = when (row.kind) {
        SampleEntity.HR -> HeartRateSample(row.t, row.v1, SampleQuality.entries[row.quality])
        SampleEntity.IBI -> IbiSample(row.t, row.v1.toInt(), SampleQuality.entries[row.quality])
        SampleEntity.MOTION -> MotionSample(row.t, row.v1, row.v2)
        SampleEntity.STATUS -> SensorStatus.entries.getOrNull(row.v1.toInt())?.let { SensorStatusSample(row.t, it) }
        else -> null
    }

    private companion object {
        const val TAG = "Reanalysis"
    }
}
