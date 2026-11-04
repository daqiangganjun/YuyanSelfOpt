package com.yuyan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yuyan.imemodule.handwriting.HandwritingResult
import com.yuyan.imemodule.handwriting.HandwritingSession
import com.yuyan.imemodule.handwriting.InkStrokes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 可控的异步识别返回，覆盖真实服务无法稳定制造的时序。 */
@RunWith(AndroidJUnit4::class)
class HandwritingSessionTest {
    private class Request(val ink: android.os.Bundle, val callback: (HandwritingResult) -> Unit) {
        var cancelled = false
        fun answer(vararg text: String) = callback(HandwritingResult.Candidates(text.toList()))
    }

    private class Fixture {
        val requests = mutableListOf<Request>()
        val previews = mutableListOf<String>()
        val commits = mutableListOf<String>()
        var result: HandwritingResult? = null
        var pending = false
        val session = HandwritingSession(
            { ink: InkStrokes, callback ->
                val request = Request(ink.toBundle(), callback)
                requests.add(request)
                val cancel: () -> Unit = { request.cancelled = true }
                cancel
            },
            { result, pending -> this.result = result; this.pending = pending },
            previews::add,
            commits::add,
        )
        fun stroke() {
            session.beginStroke(10f, 10f, 100)
            session.addPoint(100f, 10f, 120)
            session.endStroke()
        }
    }

    @Test fun resultAfterPauseImmediatelyCreatesPreview() {
        val f = Fixture()
        f.stroke()
        f.session.stopWriting()
        assertTrue(f.pending)
        assertTrue(f.previews.isEmpty())
        f.requests.single().answer("一", "乙")
        assertEquals(listOf("一"), f.previews)
        assertFalse(f.pending)
        assertTrue(f.commits.isEmpty())
    }

    @Test fun earlyResultWaitsForPauseAndRemainsSelectable() {
        val f = Fixture()
        f.stroke()
        f.requests.single().answer("一", "乙")
        assertTrue(f.previews.isEmpty())
        f.session.stopWriting()
        assertEquals(listOf("一"), f.previews)
        assertTrue(f.session.choose(1))
        assertEquals(listOf("乙"), f.commits)
        assertFalse(f.session.hasInput)
    }

    @Test fun oldStrokeCannotReplaceNewStrokeResult() {
        val f = Fixture()
        f.stroke()
        f.stroke()
        assertTrue(f.requests[0].cancelled)
        f.requests[1].answer("二")
        f.requests[0].answer("一")
        f.session.stopWriting()
        assertEquals(listOf("二"), f.previews)
        assertEquals(2, f.requests[1].ink.getIntArray("se")!!.size)
    }

    @Test fun finalStrokeMustFinishBeforePreviewingPartialCandidate() {
        val f = Fixture()
        f.stroke()
        f.requests[0].answer("一")
        f.stroke()
        f.session.stopWriting()
        assertTrue(f.previews.isEmpty())
        assertTrue(f.pending)
        f.requests[1].answer("二")
        assertEquals(listOf("二"), f.previews)
    }

    @Test fun outOfOrderCharactersKeepCommitOrderAndCurrentCandidates() {
        val f = Fixture()
        f.stroke()
        f.session.stopWriting()
        f.stroke()
        f.session.stopWriting()
        f.requests[1].answer("好")
        assertTrue(f.previews.isEmpty())
        f.requests[0].answer("你")
        assertEquals(listOf("你"), f.commits)
        assertEquals(listOf("好"), f.previews)
        assertEquals(HandwritingResult.Candidates(listOf("好")), f.result)
        f.session.choose(0)
        assertEquals(listOf("你", "好"), f.commits)
    }

    @Test fun selectingBeforePauseStartsAnIndependentCharacter() {
        val f = Fixture()
        f.stroke()
        f.requests[0].answer("一")
        f.session.choose(0)
        f.stroke()
        assertEquals(1, f.requests[1].ink.getIntArray("se")!!.size)
        f.requests[0].answer("旧")
        f.requests[1].answer("乙")
        f.session.stopWriting()
        assertEquals(listOf("乙"), f.previews)
        assertEquals(listOf("一"), f.commits)
    }

    @Test fun resettingRejectsCallbacksAndCancelsAllOutstandingCharacters() {
        val f = Fixture()
        f.stroke()
        f.session.stopWriting()
        f.stroke()
        f.session.reset()
        f.requests.forEach { assertTrue(it.cancelled); it.answer("旧") }
        assertNull(f.result)
        assertFalse(f.pending)
        assertTrue(f.previews.isEmpty())
        assertTrue(f.commits.isEmpty())
    }

    @Test fun emptyFailureTimeoutAndUnavailableAreNeverCommitted() {
        listOf(HandwritingResult.Empty, HandwritingResult.Failed, HandwritingResult.TimedOut,
            HandwritingResult.Unavailable, HandwritingResult.Candidates(emptyList())).forEach { result ->
            val f = Fixture()
            f.stroke()
            f.session.stopWriting()
            f.requests.single().callback(result)
            assertNotNull(f.result)
            assertFalse(f.session.choose(0))
            f.session.confirmReady()
            assertTrue(f.previews.isEmpty())
            assertTrue(f.commits.isEmpty())
        }
    }

    @Test fun pauseDuringActiveStrokeCannotComposeOrSplitTheCharacter() {
        val f = Fixture()
        f.stroke()
        f.requests[0].answer("一")
        f.session.beginStroke(10f, 20f, 150)
        f.session.stopWriting()
        assertFalse(f.session.isStopped)
        assertTrue(f.previews.isEmpty())
        f.session.endStroke()
        f.requests[1].answer("二")
        f.session.stopWriting()
        assertEquals(listOf("二"), f.previews)
    }

    @Test fun externalInputBoundaryCancelsPendingResults() {
        val f = Fixture()
        f.stroke()
        f.session.confirmReady()
        f.requests.single().answer("迟")
        assertTrue(f.commits.isEmpty())
        assertTrue(f.previews.isEmpty())
        assertFalse(f.session.hasInput)
    }
}
