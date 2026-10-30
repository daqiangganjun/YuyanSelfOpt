package com.yuyan

import android.app.ActivityManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.handwriting.HandwritingClient
import com.yuyan.imemodule.handwriting.HandwritingState
import com.yuyan.imemodule.handwriting.InkStrokes
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实数据库和跨进程手写识别验证工具链与原生依赖升级。 */
@RunWith(AndroidJUnit4::class)
class RuntimeCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val states = LinkedBlockingQueue<HandwritingState>()
    private val listener: (HandwritingState) -> Unit = { states.offer(it) }
    private var acquired = false

    @Before
    fun verifyPageSize() {
        if (InstrumentationRegistry.getArguments().getString("require16Kb") == "true") {
            assertEquals("A 16 KB runtime is required", 16384L, Os.sysconf(OsConstants._SC_PAGESIZE))
        }
    }

    @After
    fun releaseHandwriting() {
        instrumentation.runOnMainSync {
            HandwritingClient.removeListener(listener)
            if (acquired) HandwritingClient.release()
            acquired = false
        }
    }

    @Test
    fun roomBulkInsertKeepsReplacementSemantics() {
        val database = Room.inMemoryDatabaseBuilder(context, DataBaseKT::class.java).build()
        try {
            val dao = database.clipboardDao()
            dao.insertAll(listOf(Clipboard("first", time = 1), Clipboard("second", time = 2)))
            assertEquals(2, dao.getCount())
            dao.insertAll(listOf(Clipboard("first", isKeep = 1, time = 3)))
            assertEquals(2, dao.getCount())
            assertEquals("first", dao.getAll().first().content)
            assertEquals(1, dao.getAll().first().isKeep)
        } finally {
            database.close()
        }
    }

    @Test
    fun handwritingRecognizesAfterProcessRestart() {
        instrumentation.runOnMainSync {
            HandwritingClient.addListener(listener)
            HandwritingClient.acquire(context)
            acquired = true
        }
        val initial = awaitState(30_000) {
            it == HandwritingState.Downloaded || it == HandwritingState.NotDownloaded
        }
        val originalPid = handwritingPid()
        assertTrue("Handwriting must use a separate process", originalPid > 0 && originalPid != Process.myPid())
        if (initial == HandwritingState.NotDownloaded) {
            assumeTrue(
                "Install the model first or pass -e downloadHandwritingModel true",
                InstrumentationRegistry.getArguments().getString("downloadHandwritingModel") == "true"
            )
            instrumentation.runOnMainSync { HandwritingClient.download() }
            awaitState(600_000) { it == HandwritingState.Downloaded }
        }
        assertHorizontalStrokeRecognized()
        instrumentation.runOnMainSync {
            HandwritingClient.release()
            acquired = false
        }
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (handwritingPid() > 0 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
        }
        assertEquals("Handwriting process must stop after release", -1, handwritingPid())
        states.clear()
        instrumentation.runOnMainSync {
            HandwritingClient.acquire(context)
            acquired = true
        }
        awaitState(30_000) { it == HandwritingState.Downloaded }
        assertNotEquals("Handwriting must start a new process", originalPid, handwritingPid())
        assertHorizontalStrokeRecognized()
    }

    private fun handwritingPid(): Int = context.getSystemService(ActivityManager::class.java)
        .runningAppProcesses.orEmpty()
        .firstOrNull { it.processName == "${context.packageName}:hw" }?.pid ?: -1

    private fun awaitState(timeout: Long, predicate: (HandwritingState) -> Boolean): HandwritingState {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            val state = states.poll(remaining, TimeUnit.MILLISECONDS) ?: break
            if (state is HandwritingState.Failed) fail("Handwriting failed: ${state.message}")
            if (predicate(state)) return state
        }
        throw AssertionError("Handwriting state timed out: ${HandwritingClient.state}")
    }

    private fun assertHorizontalStrokeRecognized() {
        val started = SystemClock.uptimeMillis()
        val ink = InkStrokes().apply {
            repeat(25) { index -> addPoint(30f + index * 10f, 120f, started + index * 8) }
            endStroke()
        }
        val results = LinkedBlockingQueue<List<String>>()
        instrumentation.runOnMainSync {
            HandwritingClient.recognize(ink) { results.offer(it.toList()) }
        }
        val candidates = results.poll(30, TimeUnit.SECONDS)
        assertNotNull("Handwriting recognition timed out", candidates)
        assertTrue("Expected Chinese horizontal stroke, got $candidates", candidates!!.contains("一"))
    }
}
