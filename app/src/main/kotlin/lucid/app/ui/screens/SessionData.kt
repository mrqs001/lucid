package lucid.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import lucid.app.LocalContainer
import lucid.app.data.BatteryEntity
import lucid.app.data.CueEventEntity
import lucid.app.data.EpochEntity
import lucid.app.data.JournalEntity
import lucid.app.data.SessionEntity
import lucid.core.engine.CueAction
import lucid.core.engine.EngineConfig
import lucid.app.session.SessionManager

/** Everything a night view needs, loaded reactively from the database. */
data class NightData(
    val session: SessionEntity?,
    val epochs: List<EpochEntity>,
    val cues: List<CueEventEntity>,
    val truth: List<Int>?,
    val journal: List<JournalEntity>,
    val blinded: Boolean = false,
    val battery: List<BatteryEntity> = emptyList(),
) {
    val config: EngineConfig? = session?.configJson?.let {
        runCatching { SessionManager.json.decodeFromString(EngineConfig.serializer(), it) }.getOrNull()
    }
    val stages: List<Int> get() = epochs.map { it.stage }
    val pRem: List<Float> get() = epochs.map { it.pRem }
    val startMs: Long get() = session?.startedAt ?: 0
    private val firstIdx get() = epochs.firstOrNull()?.idx ?: 0
    fun epochIndexes(action: CueAction): Set<Int> = cues.filter { it.action == action.name }.map { it.epochIdx - firstIdx }.toSet()
    /** Blinded experiment nights hide cue details until a journal entry exists. */
    val hidden: Boolean get() = session?.experimentId != null && blinded && journal.isEmpty()
}

@Composable
fun rememberNight(sessionId: String?): NightData {
    val c = LocalContainer.current
    val flow = remember(sessionId) {
        if (sessionId == null) {
            flowOf(NightData(null, emptyList(), emptyList(), null, emptyList()))
        } else {
            combine(
                c.db.sessions().observe(sessionId),
                c.db.epochs().observe(sessionId),
                c.db.cues().observe(sessionId),
                c.db.truth().observe(sessionId),
                c.db.journal().observeAll(),
            ) { s, e, cues, truth, journal -> Quint(s, e, cues, truth, journal) }
                .combine(c.db.battery().observe(sessionId)) { q, battery ->
                    val (s, e, cues, truth, journal) = q
                val exp = s?.experimentId?.let { c.db.experiments().get(it) }
                val byIdx = truth.associate { it.idx to it.stage }
                NightData(
                    session = s,
                    epochs = e,
                    cues = cues,
                    truth = if (truth.isEmpty()) null else e.map { byIdx[it.idx] ?: -1 },
                    journal = journal.filter { it.sessionId == sessionId },
                    blinded = exp?.blinded == true,
                    battery = battery,
                )
            }
        }
    }
    val data by flow.collectAsStateWithLifecycle(NightData(null, emptyList(), emptyList(), null, emptyList()))
    return data
}

private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
