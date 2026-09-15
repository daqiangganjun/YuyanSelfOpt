package com.yuyan

import android.os.SystemClock
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuyan.inputmethod.EngineRuntime
import com.yuyan.inputmethod.RimeEngine
import com.yuyan.inputmethod.SchemaRegistry
import com.yuyan.inputmethod.core.Rime
import com.yuyan.inputmethod.util.LX17PinYinUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 使用打包的真实词库验证 JNI 和升级后的输入语义，不替换引擎为模拟对象。 */
@RunWith(AndroidJUnit4::class)
class RimeIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun awaitReady() {
        val deadline = SystemClock.elapsedRealtime() + 240_000
        while (!EngineRuntime.isReady && SystemClock.elapsedRealtime() < deadline) {
            if (EngineRuntime.status.value?.phase == EngineRuntime.Phase.Failed) {
                fail("Engine failed: ${Rime.getLastError()}")
            }
            SystemClock.sleep(100)
        }
        assertTrue("Engine preparation timed out", EngineRuntime.isReady)
        instrumentation.waitForIdleSync()
    }

    @Before
    fun prepare() {
        instrumentation.runOnMainSync { EngineRuntime.initialize(context) }
        awaitReady()
        setPreference("rime_dictionary_family", "frost")
        setPreference("fuzzy_pinyin_enable", true)
        Rime.clearComposition()
    }

    private fun setPreference(key: String, value: Any) {
        instrumentation.runOnMainSync {
            val edit = PreferenceManager.getDefaultSharedPreferences(context).edit()
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is String -> edit.putString(key, value)
            }
            edit.commit()
            EngineRuntime.prepare(context)
        }
        awaitReady()
    }

    private fun words(schema: String, input: String): List<String> {
        assertTrue("Schema unavailable: $schema", Rime.selectSchema(schema))
        Rime.clearComposition()
        input.forEach { Rime.processKey(it.code, 0) }
        android.util.Log.i("RimeIntegrationTest", "$schema input=$input raw=${Rime.getRawInput()} preedit=${Rime.compositionText} candidates=${Rime.candidates.take(12).map { it.text }}")
        return Rime.candidates.map { it.text }
    }

    @Test
    fun fullPinyinAndMixedEnglish() {
        assertEquals("请进行", words("pinyin", "qingjinxing").first())
        assertTrue(words("pinyin", "hello").any { it.equals("hello", true) })
        assertTrue(words("pinyin", "ppt").any { it == "PPT" })
        assertTrue(words("english", "hello").any { it.equals("hello", true) })
    }

    @Test
    fun keyboardSchemesAndFuzzySpelling() {
        assertTrue(words("t9_pinyin", "MGGAM").contains("你好"))
        assertTrue(words("t9_pinyin", "WGDAMJD").contains("知道了"))
        assertTrue(words("double_pinyin_flypy", "zidcle").contains("知道了"))
        val lx17 = LX17PinYinUtils.pinyin2Key("ni") + LX17PinYinUtils.pinyin2Key("hao")
        assertTrue(words("double_pinyin_ls17", lx17).contains("你好"))
        for (schema in SchemaRegistry.chineseSchemas.filter { it.startsWith("double_pinyin") && !it.endsWith("ls17") }) {
            assertTrue("No candidates for $schema", words(schema, "ni").isNotEmpty())
        }
        assertTrue(words("stroke", "H").isNotEmpty())
        assertTrue("Stroke wildcard must match the second stroke", words("stroke", "HX").contains("二"))
    }

    @Test
    fun prefixReplacementAndGlobalCandidateSelection() {
        words("t9_pinyin", "MGGAM")
        assertTrue(Rime.replaceKey(0, 2, "ni'"))
        assertTrue(Rime.candidates.any { it.text == "你好" })
        assertTrue(Rime.replaceKey(0, 3, "MG"))
        assertTrue(Rime.candidates.any { it.text == "你好" })
        words("pinyin", "shi")
        val offset = Rime.candidates.size
        assertTrue("Expected more than one page", Rime.hasRight())
        Rime.processKey(Rime.getRimeKeycodeByName("Page_Down"), 0)
        val candidate = Rime.candidates.first().text
        assertTrue(Rime.selectCandidate(offset))
        assertEquals(candidate, Rime.getRimeCommit()?.commitText)
    }

    @Test
    fun emojiAndTraditionalOptionsSurviveSchemaSwitch() {
        setPreference("emoji_input_enable", true)
        words("pinyin", "kaixin")
        Rime.setOption("emoji", true)
        Rime.clearComposition()
        "kaixin".forEach { Rime.processKey(it.code, 0) }
        assertTrue("Emoji candidates missing", Rime.candidates.any { word ->
            word.text.codePoints().anyMatch { it in 0x1f000..0x1faff || it in 0x2600..0x27ff }
        })
        Rime.setOption("emoji", false)
        Rime.clearComposition()
        "kaixin".forEach { Rime.processKey(it.code, 0) }
        assertFalse(Rime.candidates.any { it.text.codePoints().anyMatch { cp -> cp in 0x1f000..0x1faff } })
        Rime.setOption("traditionalization", true)
        Rime.clearComposition()
        "taiwan".forEach { Rime.processKey(it.code, 0) }
        assertTrue(Rime.candidates.any { it.text == "臺灣" || it.text == "台灣" })
        setPreference("emoji_input_enable", false)
        setPreference("chinese_jian_fan_enable", true)
        words("english", "hello")
        assertTrue(words("pinyin", "taiwan").any { it == "臺灣" || it == "台灣" })
        assertFalse(words("pinyin", "kaixin").any { word -> word.codePoints().anyMatch { it in 0x1f000..0x1faff } })
        setPreference("chinese_jian_fan_enable", false)
        setPreference("emoji_input_enable", true)
    }

    @Test
    fun predictionUsesExplicitContextAndHandlesEmptyResults() {
        Rime.selectSchema("pinyin")
        assertTrue(Rime.getAssociateList("你").filterNotNull().isNotEmpty())
        assertTrue(Rime.getAssociateList("").isEmpty())
        assertTrue(Rime.getAssociateList("unmatched_context_928375").isEmpty())
        RimeEngine.predictAssociationWords("你")
        val expected = RimeEngine.showCandidates.first().text
        RimeEngine.selectAssociation(0)
        assertEquals(expected, RimeEngine.preCommitText)
    }

    @Test
    fun dictionarySwitchAndRestart() {
        setPreference("rime_dictionary_family", "ice")
        assertTrue(words("pinyin", "nihao").contains("你好"))
        assertTrue(words("t9_pinyin", "MGGAM").contains("你好"))
        assertTrue(words("double_pinyin_flypy", "nihc").contains("你好"))
        Rime.exitRime()
        assertNull(Rime.getRimeContext())
        assertTrue(Rime.getRimeAssociateList("").isNullOrEmpty())
        instrumentation.runOnMainSync { EngineRuntime.prepare(context, force = true) }
        awaitReady()
        assertTrue(words("pinyin", "nihao").contains("你好"))
    }

    @Test
    fun fuzzyChangesRedeployWithoutLosingTyping() {
        setPreference("fuzzy_pinyin_enable", false)
        val disabled = words("pinyin", "zidaole")
        setPreference("fuzzy_pinyin_enable", true)
        val enabled = words("pinyin", "zidaole")
        assertTrue(enabled.contains("知道了"))
        assertTrue(!disabled.contains("知道了") || enabled.indexOf("知道了") < disabled.indexOf("知道了"))
        assertTrue(words("t9_pinyin", "WGDAMJD").contains("知道了"))
    }

    private fun typeKeys(input: String) {
        input.forEach { char ->
            val meta = if (char.isUpperCase()) KeyEvent.META_CAPS_LOCK_ON else 0
            RimeEngine.onNormalKey(KeyEvent(0, 0, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_A + char.lowercaseChar().code - 'a'.code, 0, meta))
        }
    }

    @Test
    fun keypadPrefixSelectionAndUndo() {
        RimeEngine.selectSchema("t9_pinyin")
        RimeEngine.reset()
        typeKeys("MGGAM")
        assertEquals("ni'hao", RimeEngine.showComposition)
        val index = RimeEngine.getPrefixs().indexOf("ni")
        assertTrue("Expected the ni syllable in the sidebar", index >= 0)
        RimeEngine.selectPinyin(index)
        assertTrue(RimeEngine.showCandidates.any { it.text == "你好" })
        RimeEngine.onDeleteKey()
        assertEquals("MGGAM", Rime.getRawInput())
        assertTrue(RimeEngine.showCandidates.any { it.text == "你好" })
    }

    @Test
    fun confirmedWordSurvivesEditingUnselectedSuffix() {
        words("t9_pinyin", "MGGAMPGGJGD")
        val index = Rime.candidates.indexOfFirst { it.text == "你好" }
        assertTrue("Expected a partial sentence candidate", index >= 0)
        assertTrue(Rime.selectCandidate(index))
        assertNull("Partial selection must not commit", Rime.getRimeCommit())
        assertTrue(Rime.replaceKey(5, 3, "shi'"))
        assertTrue("The confirmed prefix was lost", Rime.compositionText.startsWith("你好"))
        val rest = Rime.candidates.indexOfFirst { it.text == "世界" }
        assertTrue("Expected remaining word", rest >= 0)
        assertTrue(Rime.selectCandidate(rest))
        assertEquals("你好世界", Rime.getRimeCommit()?.commitText)
    }

    @Test
    fun composingTextEditKeepsTheWholeInput() {
        RimeEngine.selectSchema("pinyin")
        RimeEngine.reset()
        typeKeys("nihao")
        assertTrue(RimeEngine.moveCaretTo(2))
        typeKeys("m")
        assertEquals("nimhao", Rime.getRawInput())
        RimeEngine.onDeleteKey()
        assertEquals("nihao", Rime.getRawInput())
        assertTrue(RimeEngine.showCandidates.any { it.text == "你好" })
        RimeEngine.selectSchema("double_pinyin_flypy")
        typeKeys("nihc")
        assertEquals("ni'hao", RimeEngine.showComposition)
    }

    @Test
    fun deploymentFailureIsReportedAndRecoverable() {
        val shared = java.io.File(context.cacheDir, "rime-invalid-shared").apply { mkdirs() }
        val user = java.io.File(context.cacheDir, "rime-invalid-user").apply { mkdirs() }
        java.io.File(user, "logs").mkdirs()
        java.io.File(shared, "default.yaml").writeText("config_version: '1'\nschema_list:\n  - schema: missing_schema\n")
        Rime.clearComposition()
        try {
            assertFalse(Rime.startupRime(context, shared.path, user.path, true))
            assertTrue("Expected a diagnostic error", Rime.getLastError().isNotBlank())
        } finally {
            instrumentation.runOnMainSync { EngineRuntime.prepare(context, force = true) }
            instrumentation.waitForIdleSync()
            awaitReady()
        }
        assertTrue(words("pinyin", "nihao").contains("你好"))
    }
}
