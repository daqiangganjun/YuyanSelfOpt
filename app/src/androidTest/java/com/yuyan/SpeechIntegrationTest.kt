package com.yuyan

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuyan.imemodule.speech.DoubaoProtocol
import com.yuyan.imemodule.speech.DoubaoSpeechProvider
import com.yuyan.imemodule.speech.SpeechCredentials
import com.yuyan.imemodule.speech.SpeechFailure
import com.yuyan.imemodule.speech.SpeechListener
import com.yuyan.imemodule.speech.SpeechResult
import com.yuyan.imemodule.speech.SpeechSettings
import com.yuyan.imemodule.speech.SpeechConnectionCheck
import com.yuyan.imemodule.speech.SpeechProvider
import com.yuyan.imemodule.speech.SpeechProviders
import com.yuyan.imemodule.speech.SpeechOption
import com.yuyan.imemodule.speech.SpeechSession
import com.yuyan.imemodule.keyboard.BaseKeyboardView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.prefs.AppPrefs
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun connectionCheckUsesCurrentProviderAndCancelsItsSession() = runBlocking {
        val settings = SpeechSettings(context)
        val previousProvider = settings.providerId
        val cancelled = AtomicInteger()
        val sent = AtomicInteger()
        val credentials = SpeechCredentials(context)
        val provider = object : SpeechProvider {
            override val id = "connection_check_test"
            override val displayName = "连接检查测试"
            override val options = emptyList<SpeechOption>()
            override val sampleRate = 8000
            var respond = true
            var failure: SpeechFailure? = null
            override fun validate(options: JSONObject) = Unit
            override fun createSession(apiKey: String, options: JSONObject, listener: SpeechListener): SpeechSession = object : SpeechSession {
                override fun start() {
                    assertEquals("local-check-test", apiKey)
                    failure?.let { listener.onFailure(it) } ?: listener.onReady()
                }
                override fun sendAudio(pcm: ByteArray): Boolean { sent.addAndGet(pcm.size); return true }
                override fun finish() { if (respond) listener.onResult(SpeechResult("语音识别测试", true)) }
                override fun cancel() { cancelled.incrementAndGet() }
            }
        }
        SpeechProviders.register(provider)
        settings.providerId = provider.id
        try {
            credentials.write(provider.id, "")
            val missing = SpeechConnectionCheck.run(context)
            assertFalse(missing.success)
            assertTrue(missing.message.contains("API Key"))
            assertEquals(0, cancelled.get())
            credentials.write(provider.id, "local-check-test")
            val result = SpeechConnectionCheck.run(context)
            assertTrue(result.message, result.success)
            assertEquals("语音识别测试", result.recognizedText)
            val originalSize = context.assets.open("speech/connection_check.pcm").use { it.readBytes().size }
            assertEquals(originalSize / 2, sent.get())
            assertEquals(1, cancelled.get())
            provider.failure = SpeechFailure(com.yuyan.imemodule.speech.SpeechFailureKind.SERVER, 45000001)
            val rejected = SpeechConnectionCheck.run(context)
            assertFalse(rejected.success)
            assertTrue(rejected.message.contains("45000001"))
            assertEquals(2, cancelled.get())
            provider.failure = null
            provider.respond = false
            val pending = async { SpeechConnectionCheck.run(context) }
            withTimeout(5000) {
                while (sent.get() == originalSize / 2) kotlinx.coroutines.delay(10)
            }
            pending.cancelAndJoin()
            assertEquals(3, cancelled.get())
        } finally {
            credentials.write(provider.id, "")
            settings.preferences.edit().putString("provider", previousProvider).commit()
        }
    }

    @Test
    fun liveBuiltInConnectionCheckRecognizesItsBundledSample() = runBlocking {
        val key = InstrumentationRegistry.getArguments().getString("speechApiKey")
        assumeTrue("Provide speechApiKey for the built-in live connection check", !key.isNullOrBlank())
        val settings = SpeechSettings(context)
        val previousProvider = settings.providerId
        val credentials = SpeechCredentials(context)
        val previousKey = credentials.read("doubao")
        try {
            settings.providerId = "doubao"
            credentials.write("doubao", key!!)
            val result = SpeechConnectionCheck.run(context)
            assertTrue(result.message, result.success)
            assertTrue("Bundled Chinese speech was not recognized", result.recognizedText.contains("语音识别") || result.recognizedText.contains("語音識別"))
        } finally {
            credentials.write("doubao", previousKey ?: "")
            settings.preferences.edit().putString("provider", previousProvider).commit()
        }
    }

    @Test
    fun protocolHandlesFinalResultsAndErrors() {
        val json = "{\"result\":{\"text\":\"speech test\"}}".toByteArray()
        val frame = ByteBuffer.allocate(12 + json.size).put(byteArrayOf(0x11, 0x93.toByte(), 0x10, 0))
            .putInt(-3).putInt(json.size).put(json).array()
        val response = DoubaoProtocol.decode(frame)
        assertTrue(response.isFinal)
        assertEquals("speech test", response.payload.getJSONObject("result").getString("text"))
        val failure = ByteBuffer.allocate(12).put(byteArrayOf(0x11, 0xf0.toByte(), 0, 0)).putInt(45000001).putInt(0).array()
        assertEquals(45000001, DoubaoProtocol.decode(failure).errorCode)
        val truncated = frame.copyOf(frame.size - 1)
        assertTrue(runCatching { DoubaoProtocol.decode(truncated) }.isFailure)
        val last = ByteBuffer.wrap(DoubaoProtocol.audio(byteArrayOf(0, 0), 7, true))
        assertEquals(0x23, last.get(1).toInt() and 255)
        assertEquals(-7, last.getInt(4))
    }

    @Test
    fun providerResolvesDependentOptionsAndPreservesExtensions() {
        val provider = DoubaoSpeechProvider()
        val options = JSONObject().put("enable_speaker_info", true).put("enable_poi_fc", true)
            .put("output_zh_variant", "tw").put("extra_request", JSONObject().put("custom_future_option", true))
        val request = provider.buildRequest(options)
        assertTrue(request.getBoolean("show_utterances"))
        assertTrue(request.getBoolean("enable_nonstream"))
        assertTrue(request.getBoolean("custom_future_option"))
        assertEquals("tw", request.getString("output_zh_variant"))
        assertFalse(request.has("extra_request"))
        assertFalse(request.has("resource_id"))
        provider.validate(options)
        assertTrue(runCatching { provider.validate(JSONObject().put("extra_request", JSONObject().put("result_type", "single"))) }.isFailure)
        assertEquals("volc.seedasr.sauc.duration", SpeechSettings(context).options(provider).getString("resource_id"))
    }

    @Test
    fun credentialsStayEncryptedOutsideBackup() {
        val credentials = SpeechCredentials(context)
        val id = "integration_test"
        val value = "local-test-credential"
        try {
            credentials.write(id, value)
            assertEquals(value, credentials.read(id))
            val file = File(context.noBackupFilesDir, "speech_credentials/$id.bin")
            assertTrue(file.exists())
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(value))
            val preferences = File(context.applicationInfo.dataDir, "shared_prefs/speech_settings.xml")
            assertFalse(preferences.exists() && preferences.readText().contains(value))
        } finally {
            credentials.write(id, "")
        }
        assertNull(credentials.read(id))
    }

    /** 密钥只通过运行时参数提供，音频放在目标应用的 externalFilesDir，均不进入 APK。 */
    @Test
    fun liveDoubaoRecognizesChineseAudio() {
        val args = InstrumentationRegistry.getArguments()
        val key = args.getString("speechApiKey")
        assumeTrue("Provide speechApiKey and speech-test.pcm for live recognition", !key.isNullOrBlank())
        val audio = File(context.getExternalFilesDir(null), "speech-test.pcm").readBytes()
        assertTrue("Audio must contain signed 16-bit PCM", audio.isNotEmpty() && audio.size % 2 == 0)
        val ready = CountDownLatch(1)
        val terminal = LinkedBlockingQueue<Any>()
        val provider = DoubaoSpeechProvider()
        val options = SpeechSettings(context).options(provider)
        if (args.getString("speechProfile") == "rich") {
            listOf("enable_speaker_info", "enable_lid", "enable_ddc", "show_speech_rate", "show_volume",
                "enable_emotion_detection", "enable_gender_detection", "enable_age_detection").forEach { options.put(it, true) }
            options.put("output_zh_variant", "tw")
        }
        args.getString("speechOptions")?.let { extra ->
            val overrides = JSONObject(extra)
            overrides.keys().forEach { options.put(it, overrides.get(it)) }
        }
        val session = provider.createSession(key!!, options, object : SpeechListener {
            override fun onReady() { ready.countDown() }
            override fun onResult(result: SpeechResult) { if (result.isFinal) terminal.offer(result) }
            override fun onFailure(failure: SpeechFailure) { terminal.offer(failure); ready.countDown() }
        })
        try {
            session.start()
            assertTrue("WebSocket handshake timed out", ready.await(20, TimeUnit.SECONDS))
            var offset = 0
            while (offset < audio.size && terminal.isEmpty()) {
                val end = minOf(audio.size, offset + 6400)
                assertTrue("Cannot send audio frame", session.sendAudio(audio.copyOfRange(offset, end)))
                offset = end
                Thread.sleep(200)
            }
            session.finish()
            val result = terminal.poll(25, TimeUnit.SECONDS)
            assertTrue("Expected a final result; received $result", result is SpeechResult)
            val text = (result as SpeechResult).text
            assertTrue("Chinese speech was not recognized", text.contains("语音识别") || text.contains("語音識別"))
            if (args.getString("speechProfile") == "rich") {
                assertTrue("Traditional output was not applied", text.contains("語音識別"))
                assertTrue("Speaker information was not returned", result.utterances.any { !it.speakerId.isNullOrBlank() })
            }
        } finally {
            session.cancel()
        }
    }

    @Test
    fun longPressRecordsAndCommitsOnlyTheCurrentSession() {
        assumeTrue("Pass speechUiTest=true to exercise the debug IME", InstrumentationRegistry.getArguments().getString("speechUiTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val previousIme = shell("settings get secure default_input_method")
        val settings = SpeechSettings(context)
        val previousProvider = settings.providerId
        val previousEnabled = settings.enabled
        val starts = AtomicInteger()
        val audioBytes = AtomicInteger()
        val provider = object : SpeechProvider {
            override val id = "integration_ui"
            override val displayName = "语音测试"
            override val options = emptyList<SpeechOption>()
            var callback: SpeechListener? = null
            override fun validate(options: JSONObject) = Unit
            override fun createSession(apiKey: String, options: JSONObject, listener: SpeechListener): SpeechSession {
                callback = listener
                return object : SpeechSession {
                    private var cancelled = false
                    override fun start() { starts.incrementAndGet(); listener.onReady() }
                    override fun sendAudio(pcm: ByteArray): Boolean { audioBytes.addAndGet(pcm.size); return !cancelled }
                    override fun finish() { if (!cancelled) listener.onResult(SpeechResult("语音输入测试", true)) }
                    override fun cancel() { cancelled = true }
                }
            }
        }
        SpeechProviders.register(provider)
        val credentials = SpeechCredentials(context)
        credentials.write(provider.id, "local-ui-test")
        settings.providerId = provider.id
        settings.enabled = true
        var activity: android.app.Activity? = null
        try {
            val component = "${context.packageName}/com.yuyan.imemodule.service.ImeService"
            shell("ime enable $component")
            shell("settings put secure default_input_method $component")
            activity = instrumentation.startActivitySync(Intent(context, ImeTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val editor = activity.findViewById<EditText>(android.R.id.edit)
            var keyboard: BaseKeyboardView? = null
            fun findKeyboard(view: View): BaseKeyboardView? {
                if (view is BaseKeyboardView && view.isShown && view.getSoftKeyboard()?.getKeyByCode(KeyEvent.KEYCODE_SPACE) != null) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) findKeyboard(view.getChildAt(index))?.let { return it }
                return null
            }
            repeat(50) {
                instrumentation.runOnMainSync {
                    (KeyboardManager.instance.currentContainer as? View)?.let { keyboard = findKeyboard(it) }
                }
                if (keyboard == null) Thread.sleep(100)
            }
            val keyView = requireNotNull(keyboard) { "Debug IME keyboard is not visible" }
            val space = keyView.getSoftKeyboard()!!.getKeyByCode(KeyEvent.KEYCODE_SPACE)!!
            val x = (space.mLeft + space.mRight) / 2f
            val y = (space.mTop + space.mBottom) / 2f
            var downTime = 0L
            fun touch(action: Int) {
                instrumentation.runOnMainSync {
                    if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                    keyView.onTouchEvent(event)
                    event.recycle()
                }
            }
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
            instrumentation.waitForIdleSync()
            assertEquals(0, starts.get())
            instrumentation.runOnMainSync { assertEquals(" ", editor.text.toString()); editor.text.clear() }
            touch(MotionEvent.ACTION_DOWN)
            Thread.sleep(AppPrefs.getInstance().keyboardSetting.longPressTimeout.getValue().toLong() + 1000)
            assertEquals(1, starts.get())
            assertTrue("AudioRecord must produce PCM while holding space", audioBytes.get() > 0)
            touch(MotionEvent.ACTION_UP)
            Thread.sleep(800)
            instrumentation.runOnMainSync { assertEquals("语音输入测试", editor.text.toString()) }
            touch(MotionEvent.ACTION_DOWN)
            Thread.sleep(AppPrefs.getInstance().keyboardSetting.longPressTimeout.getValue().toLong() + 500)
            touch(MotionEvent.ACTION_CANCEL)
            provider.callback?.onResult(SpeechResult("过期结果", true))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals("语音输入测试", editor.text.toString()) }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            settings.providerId = previousProvider
            settings.enabled = previousEnabled
            credentials.write(provider.id, "")
            shell("settings put secure default_input_method $previousIme")
        }
    }
}
