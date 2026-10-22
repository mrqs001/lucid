package lucid.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes local data to files and hands them to the Android share sheet. Nothing is uploaded by
 * the app itself; the user picks where the file goes.
 */
class Exporter(private val context: Context, private val db: LucidDatabase) {
    private val dir get() = File(context.cacheDir, "exports").apply { mkdirs() }
    private val stamp get() = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    /** One night as CSV files: raw samples, epochs (features + probabilities), cue decisions. */
    suspend fun exportSession(sessionId: String): List<File> {
        val s = db.sessions().get(sessionId) ?: return emptyList()
        val prefix = "lucid-${s.origin.name.lowercase()}-${sessionId.take(8)}"
        val truth = db.truth().forSession(sessionId).associate { it.idx to it.stage }
        val samples = File(dir, "$prefix-samples.csv").apply {
            printWriter().use { w ->
                w.println("t_ms,kind,v1,v2,quality,backfill")
                for (r in db.samples().forSession(sessionId)) w.println("${r.t},${kindName(r.kind)},${r.v1},${r.v2},${r.quality},${r.backfill}")
            }
        }
        val epochs = File(dir, "$prefix-epochs.csv").apply {
            printWriter().use { w ->
                w.println("idx,start_ms,hr_mean,hr_rel,hr_instability,rmssd,activity_mg,active_s,quality,contact_lost,p_wake,p_light,p_deep,p_rem,confidence,stage,sim_truth")
                for (e in db.epochs().forSession(sessionId)) {
                    w.println(
                        listOf(
                            e.idx, e.startMs, e.hrMean, e.hrRel, e.hrInstability, e.rmssd, e.activityMg, e.activeSeconds, e.quality,
                            e.contactLost, e.pWake, e.pLight, e.pDeep, e.pRem, e.confidence, e.stage, truth[e.idx],
                        ).joinToString(",") { it?.toString() ?: "" },
                    )
                }
            }
        }
        val cues = File(dir, "$prefix-cues.csv").apply {
            printWriter().use { w ->
                w.println("t_ms,epoch,action,p_rem,p_wake,confidence,quality,intensity,pattern,cue_id,outcome,ack_ms,response,explanation")
                for (c in db.cues().forSession(sessionId)) {
                    w.println(
                        listOf(
                            c.t, c.epochIdx, c.action, c.pRem, c.pWake, c.confidence, c.quality, c.intensity, c.patternId, c.cueId,
                            c.outcome, c.ackAt, c.response, "\"" + c.explanation.replace("\"", "'") + "\"",
                        ).joinToString(",") { it?.toString() ?: "" },
                    )
                }
            }
        }
        val battery = File(dir, "$prefix-battery.csv").apply {
            printWriter().use { w ->
                w.println("t_ms,device,pct,charging")
                for (b in db.battery().forSession(sessionId)) w.println("${b.t},${b.device},${b.pct},${b.charging ?: ""}")
            }
        }
        val meta = File(dir, "$prefix-session.json").apply {
            writeText(json.encodeToString(JsonElement.serializer(), sessionJson(s)))
        }
        return listOf(meta, samples, epochs, cues, battery)
    }

    /** Journal, training, experiments and session summaries (no raw samples) as one JSON file. */
    suspend fun exportAll(): File {
        val root = buildJsonObject {
            put("exportedAt", System.currentTimeMillis())
            put("format", "lucid-export-v1")
            put("sessions", JsonArray(db.sessions().all().map { sessionJson(it) }))
            put("journal", JsonArray(db.journal().all().map { j ->
                buildJsonObject {
                    put("id", j.id); put("createdAt", j.createdAt); put("sessionId", j.sessionId); put("title", j.title)
                    put("body", j.body); put("recall", j.recall.name); put("lucid", j.lucid); put("lucidity", j.lucidity)
                    put("cueRecognition", j.cueRecognition.name); put("vividness", j.vividness); put("control", j.control)
                    put("sleepQuality", j.sleepQuality); put("awakenings", j.awakenings); put("tags", j.tags)
                }
            }))
            put("training", JsonArray(db.training().all().map { t ->
                buildJsonObject { put("t", t.t); put("type", t.type.name); put("detail", t.detail); put("durationSec", t.durationSec); put("source", t.source) }
            }))
        }
        return File(dir, "lucid-export-$stamp.json").apply { writeText(json.encodeToString(JsonElement.serializer(), root)) }
    }

    fun share(files: List<File>) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (files.all { it.extension == "json" }) "application/json" else "text/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export Lucid data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun sessionJson(s: SessionEntity): JsonObject = buildJsonObject {
        put("id", s.id); put("startedAt", s.startedAt); put("endedAt", s.endedAt); put("origin", s.origin.name)
        put("device", s.device); put("provider", s.provider); put("scenarioId", s.scenarioId); put("seed", s.seed)
        put("link", s.linkKind); put("estimator", s.estimatorId); put("policy", s.policyId); put("status", s.status.name)
        put("experimentId", s.experimentId); put("arm", s.armId)
        put("config", runCatching { json.parseToJsonElement(s.configJson) }.getOrDefault(JsonNull))
        put("summary", s.summaryJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
    }

    private fun kindName(k: Int) = when (k) {
        SampleEntity.HR -> "hr"
        SampleEntity.IBI -> "ibi"
        SampleEntity.MOTION -> "motion"
        else -> "status"
    }

    private companion object {
        val json = Json { prettyPrint = true }
    }
}
