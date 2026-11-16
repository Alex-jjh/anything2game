package edu.csi301.fieldnotes

enum class MockStage { CONFIRM, RUNNING, FAILED, CANCELLED, READY }
enum class MockOutcome(val delayMs: Long) { SUCCESS(1500), FAILURE(1500), LATE_SUCCESS(12000) }

/** Only the timer knows the simulated outcome. Each submission gets its own ID. */
class PdfMockRun(
    initial: MockStage = MockStage.CONFIRM,
    private val after: (Long, () -> Unit) -> Unit,
) {
    var stage = initial
        private set
    var ignoredLateResult = false
        private set
    var onChange: (() -> Unit)? = null
    private var lastRunId = 0L

    fun start(outcome: MockOutcome) {
        if (stage != MockStage.CONFIRM && stage != MockStage.CANCELLED && stage != MockStage.FAILED) return
        val id = ++lastRunId
        ignoredLateResult = false
        stage = MockStage.RUNNING
        onChange?.invoke()
        after(outcome.delayMs) {
            if (id == lastRunId && stage == MockStage.RUNNING) {
                stage = if (outcome == MockOutcome.FAILURE) MockStage.FAILED else MockStage.READY
            } else if (id != lastRunId || stage == MockStage.CANCELLED) {
                ignoredLateResult = true
            }
            onChange?.invoke()
        }
    }

    fun cancel() {
        if (stage != MockStage.RUNNING) return
        stage = MockStage.CANCELLED
        // Keep the scheduled callback to exercise cancellation racing with delivery.
        onChange?.invoke()
    }
}
