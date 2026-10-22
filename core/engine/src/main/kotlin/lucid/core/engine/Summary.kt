package lucid.core.engine

import kotlinx.serialization.Serializable
import lucid.core.model.SleepStage

/** Night-level numbers for dashboards and experiments. All stage figures are estimates. */
@Serializable
data class SessionSummary(
    val epochs: Int,
    val recordedMin: Float,
    val sleepOnsetMs: Long?,
    /** Minutes per estimated stage after sleep onset (most-likely stage per epoch). */
    val estimatedMin: Map<SleepStage, Float>,
    val estimatedSleepMin: Float,
    val meanQuality: Float,
    val contactLostMin: Float,
    val noDataMin: Float,
    val cuesDelivered: Int,
    val cuesWouldDeliver: Int,
    val cueCandidatesSuppressed: Int,
    val suppressedByGate: Map<CueGate, Int>,
    val suspectedArousals: Int,
    val maxPRem: Float,
)

internal class SummaryBuilder(private val startMs: Long, private val epochMs: Long) {
    var epochs = 0
        private set
    private val stageEpochsAfterOnset = IntArray(SleepStage.entries.size)
    private val epochStarts = ArrayList<Pair<Long, SleepStage>>()
    private var qualitySum = 0.0
    private var contactLost = 0
    private var noData = 0
    private var delivered = 0
    private var would = 0
    private var suppressed = 0
    private val byGate = HashMap<CueGate, Int>()
    private var arousals = 0
    private var maxPRem = 0f

    fun add(f: EpochFeatures, p: StagePrediction) {
        epochs++
        qualitySum += f.quality
        if (f.contactLost) contactLost++
        if (f.hrMean == null && f.activityMg == null) noData++
        epochStarts += f.startMs to p.mostLikely
        maxPRem = maxOf(maxPRem, p.pRem)
    }

    fun addDecision(d: CueDecision) {
        when (d.action) {
            CueAction.DELIVER -> delivered++
            CueAction.WOULD_DELIVER -> would++
            CueAction.SUPPRESS -> {
                suppressed++
                d.failures.forEach { byGate.merge(it.gate, 1, Int::plus) }
            }
        }
    }

    fun addAssessment(a: CueResponseAssessment) {
        if (a.response == CueResponse.AROUSAL_SUSPECTED) arousals++
    }

    fun build(onsetMs: Long?): SessionSummary {
        stageEpochsAfterOnset.fill(0)
        if (onsetMs != null) for ((t, s) in epochStarts) if (t >= onsetMs) stageEpochsAfterOnset[s.ordinal]++
        val minPerEpoch = epochMs / 60_000f
        val est = SleepStage.entries.associateWith { stageEpochsAfterOnset[it.ordinal] * minPerEpoch }
        return SessionSummary(
            epochs = epochs,
            recordedMin = epochs * minPerEpoch,
            sleepOnsetMs = onsetMs,
            estimatedMin = est,
            estimatedSleepMin = est.filterKeys { it != SleepStage.WAKE }.values.sum(),
            meanQuality = if (epochs == 0) 0f else (qualitySum / epochs).toFloat(),
            contactLostMin = contactLost * minPerEpoch,
            noDataMin = noData * minPerEpoch,
            cuesDelivered = delivered,
            cuesWouldDeliver = would,
            cueCandidatesSuppressed = suppressed,
            suppressedByGate = byGate.toMap(),
            suspectedArousals = arousals,
            maxPRem = maxPRem,
        )
    }
}
