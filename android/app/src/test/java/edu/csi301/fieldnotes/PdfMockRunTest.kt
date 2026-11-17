package edu.csi301.fieldnotes

import org.junit.Assert.*
import org.junit.Test

class PdfMockRunTest {
    private val replies = mutableListOf<() -> Unit>()
    private val run = PdfMockRun { _, deliver -> replies.add(deliver) }

    @Test fun successRequiresExplicitStartAndAcceptsOnlyOneSubmission() {
        assertEquals(MockStage.CONFIRM, run.stage)
        assertTrue(replies.isEmpty())
        run.start(MockOutcome.SUCCESS)
        run.start(MockOutcome.FAILURE)
        assertEquals(MockStage.RUNNING, run.stage)
        replies.single().invoke()
        assertEquals(MockStage.READY, run.stage)
    }

    @Test fun failureCanRetryAndAnOldFailureCannotReplaceTheNewResult() {
        run.start(MockOutcome.FAILURE)
        val first = replies.single()
        first()
        assertEquals(MockStage.FAILED, run.stage)
        run.start(MockOutcome.SUCCESS)
        first()
        assertEquals(MockStage.RUNNING, run.stage)
        replies.last().invoke()
        first()
        assertEquals(MockStage.READY, run.stage)
        assertTrue(run.ignoredLateResult)
    }

    @Test fun cancelLeavesFileAtConfirmationEvenAfterItsPendingSuccessArrives() {
        run.start(MockOutcome.LATE_SUCCESS)
        run.cancel()
        replies.single().invoke()
        assertEquals(MockStage.CANCELLED, run.stage)
        assertTrue(run.ignoredLateResult)
    }

    @Test fun cancelledSuccessCannotAdvanceAnotherAttemptOrOverwriteItsFailure() {
        run.start(MockOutcome.LATE_SUCCESS)
        val staleSuccess = replies.single()
        run.cancel()
        run.start(MockOutcome.FAILURE)
        staleSuccess()
        assertEquals(MockStage.RUNNING, run.stage)
        replies.last().invoke()
        staleSuccess()
        assertEquals(MockStage.FAILED, run.stage)
    }
}
