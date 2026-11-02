package com.yuyan

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.keyboard.BaseKeyboardView
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyPreset
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.terminal.TerminalInputDispatcher
import com.yuyan.imemodule.keyboard.terminal.TerminalModifiers
import com.yuyan.imemodule.keyboard.transform.TextTransformer
import com.yuyan.imemodule.keyboard.transform.TextTransformSession
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.inputmethod.EngineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalInputIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private class RecordingConnection : BaseInputConnection(View(InstrumentationRegistry.getInstrumentation().targetContext), true) {
        val events = mutableListOf<KeyEvent>()
        val commits = mutableListOf<String>()
        var menuActions = 0
        override fun sendKeyEvent(event: KeyEvent): Boolean { events.add(event); return true }
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            commits.add(text.toString())
            return super.commitText(text, newCursorPosition)
        }
        override fun performContextMenuAction(id: Int): Boolean { menuActions++; return false }
    }

    @Test
    fun directTextAndStickyModifiersProduceCompleteKeyPairs() {
        val target = RecordingConnection()
        val dispatcher = TerminalInputDispatcher({ target }, { EditorInfo().apply { packageName = "com.termux" } })
        "git ".forEach { dispatcher.sendText(it.toString()) }
        assertEquals(listOf("g", "i", "t", " "), target.commits)
        dispatcher.modifiers.toggle(KeyEvent.KEYCODE_CTRL_LEFT)
        dispatcher.sendText("c")
        val interrupt = target.events.first { it.keyCode == KeyEvent.KEYCODE_C && it.action == KeyEvent.ACTION_DOWN }
        assertTrue("Ctrl+C must carry a control modifier", interrupt.isCtrlPressed)
        assertEquals(TerminalModifiers.State.Off, dispatcher.modifiers.ctrl)
        assertEquals(KeyEvent.ACTION_UP, target.events.last().action)
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, target.events.last().keyCode)
        assertEquals(listOf("g", "i", "t", " "), target.commits)
        dispatcher.modifiers.toggle(KeyEvent.KEYCODE_ALT_LEFT, lock = true)
        dispatcher.sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
        dispatcher.sendText("b")
        assertEquals(TerminalModifiers.State.Locked, dispatcher.modifiers.alt)
        assertTrue(target.events.first { it.keyCode == KeyEvent.KEYCODE_DPAD_LEFT }.isAltPressed)
        dispatcher.modifiers.clear()
        assertEquals(0, dispatcher.modifiers.metaState)
        assertEquals(target.events.count { it.action == KeyEvent.ACTION_DOWN }, target.events.count { it.action == KeyEvent.ACTION_UP })
    }

    @Test
    fun nativeTermuxPasteAndTextFallbackHaveDifferentRoutes() {
        val target = RecordingConnection()
        var terminal = true
        val dispatcher = TerminalInputDispatcher({ target }, { EditorInfo().apply { packageName = if (terminal) "com.termux" else "com.example.editor" } })
        dispatcher.paste("first\nsecond")
        assertTrue(target.commits.isEmpty())
        assertEquals(0, target.menuActions)
        val paste = target.events.first { it.keyCode == KeyEvent.KEYCODE_V }
        assertTrue(paste.isCtrlPressed && paste.isAltPressed)
        dispatcher.paste("first\nsecond", nativePaste = false)
        assertEquals(listOf("first\nsecond"), target.commits)
        terminal = false
        dispatcher.paste("fallback")
        assertEquals(1, target.menuActions)
        assertEquals("fallback", target.commits.last())
    }

    private fun transformer(block: suspend (String) -> String) = object : TextTransformer {
        override val id = "integration"
        override val displayName = "测试转换"
        override val debounceMillis = 0L
        override suspend fun transform(source: String) = block(source)
    }

    @Test
    fun stickyModifiersApplyToSymbolsAndSpecialKeys() {
        val target = RecordingConnection()
        val dispatcher = TerminalInputDispatcher({ target }, { EditorInfo().apply { packageName = "com.termux" } })
        val symbols = listOf("[" to KeyEvent.KEYCODE_LEFT_BRACKET, "{" to KeyEvent.KEYCODE_LEFT_BRACKET,
            "|" to KeyEvent.KEYCODE_BACKSLASH, "?" to KeyEvent.KEYCODE_SLASH,
            "［" to KeyEvent.KEYCODE_LEFT_BRACKET, "。" to KeyEvent.KEYCODE_PERIOD)
        symbols.forEach { (symbol, expectedCode) ->
            target.events.clear()
            dispatcher.modifiers.toggle(KeyEvent.KEYCODE_CTRL_LEFT)
            dispatcher.sendText(symbol)
            val key = target.events.first { it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode) }
            assertEquals(expectedCode, key.keyCode)
            assertTrue("Symbols must preserve the sticky Ctrl modifier", key.isCtrlPressed)
            assertTrue(target.commits.isEmpty())
            assertEquals(TerminalModifiers.State.Off, dispatcher.modifiers.ctrl)
        }
        listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_LEFT).forEach { code ->
            target.events.clear()
            dispatcher.modifiers.toggle(KeyEvent.KEYCODE_ALT_LEFT)
            dispatcher.sendKey(code)
            assertTrue(target.events.first { it.action == KeyEvent.ACTION_DOWN && it.keyCode == code }.isAltPressed)
            assertEquals(TerminalModifiers.State.Off, dispatcher.modifiers.alt)
        }
        dispatcher.sendText("，")
        assertEquals("，", target.commits.last())
    }

    @Test
    fun stickySymbolsAndAltEnterUseTheRealInputViewRoutes() = withKeyboard { input, editor ->
        val events = mutableListOf<KeyEvent>()
        instrumentation.runOnMainSync {
            editor.setOnKeyListener { _, _, event -> events.add(KeyEvent(event)); true }
            input.toggleTerminalMode()
            input.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_LANG))
            input.terminalInput.modifiers.toggle(KeyEvent.KEYCODE_ALT_LEFT)
            input.responseLongKeyEvent(com.yuyan.imemodule.prefs.behavior.PopupMenuMode.Text to "|")
        }
        awaitCondition("An upward symbol must carry Alt in Chinese terminal mode") {
            events.any { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_BACKSLASH && it.isAltPressed && it.isShiftPressed }
        }
        instrumentation.runOnMainSync {
            assertEquals(TerminalModifiers.State.Off, input.terminalInput.modifiers.alt)
            events.clear()
            input.terminalInput.modifiers.toggle(KeyEvent.KEYCODE_CTRL_LEFT)
            input.responseKeyEvent(SoftKey(label = "["))
        }
        awaitCondition("A symbol-panel key must carry Ctrl and bypass paired insertion") {
            events.any { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_LEFT_BRACKET && it.isCtrlPressed }
        }
        instrumentation.runOnMainSync {
            events.clear()
            input.terminalInput.modifiers.toggle(KeyEvent.KEYCODE_CTRL_LEFT)
            input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_A))
        }
        awaitCondition("Chinese keyboard labels must not add a Shift modifier to Ctrl+A") {
            events.any { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_A && it.isCtrlPressed && !it.isShiftPressed }
        }
        instrumentation.runOnMainSync {
            events.clear()
            input.terminalInput.modifiers.toggle(KeyEvent.KEYCODE_ALT_LEFT)
            input.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_NUMBER))
            assertEquals(TerminalModifiers.State.Once, input.terminalInput.modifiers.alt)
            input.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_RETURN))
            assertEquals(TerminalModifiers.State.Once, input.terminalInput.modifiers.alt)
            input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_ENTER))
        }
        awaitCondition("Alt+Enter must be emitted as a modified Enter key") {
            events.any { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_ENTER && it.isAltPressed }
        }
        instrumentation.runOnMainSync {
            assertEquals(TerminalModifiers.State.Off, input.terminalInput.modifiers.alt)
            editor.setOnKeyListener(null)
        }
    }

    @Test
    fun numberedFunctionKeyUsesTemporaryNumberKeyboardAndRestoresItsOrigin() = withKeyboard { input, editor ->
        val events = mutableListOf<KeyEvent>()
        instrumentation.runOnMainSync {
            input.toggleTerminalMode()
            editor.setOnKeyListener { _, _, event -> events.add(KeyEvent(event)); true }
        }
        openTerminalFunctions(input)
        val button = descendants(input.auxiliaryPanel.terminalKeys).filterIsInstance<TextView>().first { it.tag == "terminal_function_key" }
        lateinit var original: com.yuyan.imemodule.keyboard.container.BaseContainer
        instrumentation.runOnMainSync {
            val labels = descendants(input.auxiliaryPanel.terminalKeys).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertEquals(listOf("TAB", "ESC"), labels.take(2))
            assertFalse(labels.any { it == "INS" || it.matches(Regex("F(?:[1-9]|1[0-2])")) })
            original = requireNotNull(KeyboardManager.instance.currentContainer)
            input.terminalInput.modifiers.toggle(KeyEvent.KEYCODE_ALT_LEFT)
        }
        tapView(button)
        instrumentation.runOnMainSync {
            assertTrue(button.isSelected)
            assertEquals(KeyboardManager.KeyboardType.NUMBER, KeyboardManager.instance.currentKeyboardType)
            assertTrue("Temporary numeric input must preserve the English mode", InputModeSwitcher.isEnglish)
        }
        tapKeyboardKey(input, KeyEvent.KEYCODE_1)
        instrumentation.runOnMainSync { assertEquals("F(1)", button.text.toString()) }
        tapKeyboardKey(input, KeyEvent.KEYCODE_2)
        instrumentation.runOnMainSync {
            assertEquals("F(12)", button.text.toString())
            assertTrue("Function-key digits must not reach the host", editor.text.isEmpty() && events.isEmpty())
            assertEquals(TerminalModifiers.State.Once, input.terminalInput.modifiers.alt)
        }
        saveUiSnapshot("tmp_function_key_number.png")
        tapView(button)
        awaitCondition("The selected F12 must carry the pending Alt modifier") {
            events.any { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_F12 && it.isAltPressed }
        }
        instrumentation.runOnMainSync {
            assertSame("Confirmation must restore the original keyboard", original, KeyboardManager.instance.currentContainer)
            assertEquals("F(n)", button.text.toString())
            assertFalse(button.isSelected)
            assertEquals(TerminalModifiers.State.Off, input.terminalInput.modifiers.alt)
            assertEquals(1, events.count { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_F12 })
            assertEquals(1, events.count { it.action == KeyEvent.ACTION_UP && it.keyCode == KeyEvent.KEYCODE_F12 })
            editor.setOnKeyListener(null)
        }
    }

    @Test
    fun numberedFunctionKeySupportsCorrectionCancellationAndChineseLayouts() = withKeyboard { input, editor ->
        val events = mutableListOf<KeyEvent>()
        instrumentation.runOnMainSync {
            input.toggleTerminalMode()
            input.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_LANG))
            editor.setOnKeyListener { _, _, event -> events.add(KeyEvent(event)); true }
        }
        openTerminalFunctions(input)
        val button = descendants(input.auxiliaryPanel.terminalKeys).filterIsInstance<TextView>().first { it.tag == "terminal_function_key" }
        val schema = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
        val original = KeyboardManager.instance.currentContainer
        instrumentation.runOnMainSync {
            button.performClick()
            input.responseKeyEvent(SoftKey(label = "13"))
            button.performClick()
            assertEquals("F(13)", button.text.toString())
            assertTrue(input.auxiliaryPanel.isChoosingFunctionKey)
            assertTrue("Invalid function-key numbers must not reach the host", events.isEmpty() && editor.text.isEmpty())
            input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_DEL))
            assertEquals("F(1)", button.text.toString())
            input.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_0))
            assertEquals("F(10)", button.text.toString())
            assertTrue(InputModeSwitcher.isChinese)
            button.performClick()
        }
        awaitCondition("Correction must send F10") { events.any { it.keyCode == KeyEvent.KEYCODE_F10 && it.action == KeyEvent.ACTION_DOWN } }
        instrumentation.runOnMainSync {
            assertSame(original, KeyboardManager.instance.currentContainer)
            assertEquals(schema, AppPrefs.getInstance().internal.pinyinModeRime.getValue())
            button.performClick()
            button.performClick()
            assertFalse(input.auxiliaryPanel.isChoosingFunctionKey)
            assertSame(original, KeyboardManager.instance.currentContainer)
            button.performClick()
            input.responseLongKeyEvent(com.yuyan.imemodule.prefs.behavior.PopupMenuMode.Text to "2")
            assertEquals("F(2)", button.text.toString())
            input.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_RETURN))
            assertFalse(input.auxiliaryPanel.isChoosingFunctionKey)
            assertSame(original, KeyboardManager.instance.currentContainer)
            input.onSettingsMenuClick(SkbMenuMode.TextEdit)
            val editing = KeyboardManager.instance.currentContainer
            button.performClick()
            button.performLongClick()
            assertSame(editing, KeyboardManager.instance.currentContainer)
            input.onSettingsMenuClick(SkbMenuMode.TextEdit)
            button.performClick()
            input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_3))
            input.finishAuxiliaryInput()
            assertFalse(input.auxiliaryPanel.isChoosingFunctionKey)
            assertEquals("F(n)", button.text.toString())
            assertSame(original, KeyboardManager.instance.currentContainer)
            editor.setOnKeyListener(null)
        }
    }

    private fun openTerminalFunctions(input: InputView) {
        instrumentation.runOnMainSync {
            descendants(input.auxiliaryPanel.terminalKeys).filterIsInstance<TextView>().first { it.text.toString() == "更多" }.performClick()
        }
        var bounds = android.graphics.Rect()
        awaitCondition("The terminal functions menu must open") {
            val item = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("代码符号与功能键")?.firstOrNull()
            item?.getBoundsInScreen(bounds)
            item != null && !bounds.isEmpty
        }
        tapScreen(bounds.exactCenterX(), bounds.exactCenterY())
        awaitCondition("The numbered function-key button must become visible") {
            descendants(input.auxiliaryPanel.terminalKeys).any { it.tag == "terminal_function_key" && it.isShown }
        }
    }

    private fun tapKeyboardKey(input: InputView, code: Int) {
        instrumentation.runOnMainSync {
            val keyboard = requireNotNull(findKeyboard(input))
            val key = requireNotNull(keyboard.getSoftKeyboard()?.getKeyByCode(code))
            val x = (key.mLeft + key.mRight) / 2f
            val y = (key.mTop + key.mBottom) / 2f
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
                keyboard.dispatchTouchEvent(event)
                event.recycle()
            }
        }
    }

    @Test
    fun composingPreviewReplacesAndCancelRestoresSelection() = runBlocking {
        val target = RecordingConnection()
        lateinit var session: TextTransformSession
        withContext(Dispatchers.Main) {
            target.commitText("original", 1)
            target.setSelection(0, 8)
            session = TextTransformSession(target, true, { true }) { _, _, _ -> }
            session.update("first", transformer { "<$it>" })
        }
        awaitCondition("The first composing preview must replace the selected text") { target.editable.toString() == "<first>" }
        withContext(Dispatchers.Main) {
            assertEquals("<first>", target.editable.toString())
            session.update("second", transformer { "<$it>" })
        }
        awaitCondition("The second composing preview must replace the previous preview") { target.editable.toString() == "<second>" }
        withContext(Dispatchers.Main) {
            assertEquals("<second>", target.editable.toString())
            session.finish(false)
            assertEquals("original", target.editable.toString())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(requireNotNull(target.editable)))
        }
    }

    @Test
    fun terminalPreviewWaitsForConfirmationAndChangedTargetsReceiveNothing() = runBlocking {
        val target = RecordingConnection()
        var current = true
        lateinit var session: TextTransformSession
        withContext(Dispatchers.Main) {
            session = TextTransformSession(target, false, { current }) { _, _, _ -> }
            session.update("command", transformer { "[$it]" })
        }
        awaitCondition("The terminal preview must finish before confirmation") { !session.isBusy }
        withContext(Dispatchers.Main) {
            assertTrue(target.commits.isEmpty())
            assertEquals("", target.editable.toString())
            session.finish(true)
            assertEquals(listOf("[command]"), target.commits)
            session = TextTransformSession(target, true, { current }) { _, _, _ -> }
            session.update("stale", transformer { delay(100); it })
            current = false
        }
        delay(200)
        withContext(Dispatchers.Main) {
            session.finish(true)
            assertEquals(listOf("[command]"), target.commits)
        }
    }

    @Test
    fun lateAsyncResultsCannotOverwriteNewerInput() = runBlocking {
        val target = RecordingConnection()
        lateinit var session: TextTransformSession
        val oldStarted = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val provider = transformer { source ->
            if (source == "old") withContext(NonCancellable) {
                oldStarted.complete(Unit)
                delay(250)
                oldFinished.complete(Unit)
            }
            "[$source]"
        }
        withContext(Dispatchers.Main) {
            session = TextTransformSession(target, true, { true }) { _, _, _ -> }
            session.update("old", provider)
        }
        withTimeout(10_000) { oldStarted.await() }
        withContext(Dispatchers.Main) { session.update("new", provider) }
        withTimeout(10_000) { oldFinished.await() }
        awaitCondition("The newer preview must remain after the old conversion finishes") { target.editable.toString() == "[new]" }
        withContext(Dispatchers.Main) {
            assertEquals("[new]", target.editable.toString())
            session.finish(true)
            assertEquals("[new]", target.editable.toString())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(requireNotNull(target.editable)))
        }
    }

    @Test
    fun realKeyboardPreservesChineseSchemesAndCommitsEnglishImmediately() = withKeyboard { input, editor ->
        val prefs = AppPrefs.getInstance().internal
        val schemas = listOf(
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN to CustomConstant.SCHEMA_ZH_T9,
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to CustomConstant.SCHEMA_ZH_QWERTY,
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to (CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + "flypy"),
            InputModeSwitcher.MASK_SKB_LAYOUT_LX17 to CustomConstant.SCHEMA_ZH_DOUBLE_LX17,
            InputModeSwitcher.MASK_SKB_LAYOUT_STROKE to CustomConstant.SCHEMA_ZH_STROKE,
            InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING to CustomConstant.SCHEMA_ZH_HANDWRITING,
        )
        schemas.forEach { (layout, schema) ->
            var defaultMode = 0
            instrumentation.runOnMainSync {
                InputModeSwitcher.switchModeForSetting(layout to schema)
                defaultMode = prefs.inputDefaultMode.getValue()
            }
            settleKeyboard()
            instrumentation.runOnMainSync {
                input.toggleTerminalMode()
                assertTrue(InputModeSwitcher.isTerminalMode && InputModeSwitcher.isEnglish)
                assertEquals(defaultMode, prefs.inputDefaultMode.getValue())
                assertEquals(layout or InputModeSwitcher.MASK_LANGUAGE_CN, prefs.inputMethodPinyinMode.getValue())
                assertEquals(schema, prefs.pinyinModeRime.getValue())
                assertTrue("The English layout must keep its language switch", findKeyboard(input)?.getSoftKeyboard()?.getKeyByCode(InputModeSwitcher.USER_KEYCODE_LANG) != null)
            }
            settleKeyboard()
            instrumentation.runOnMainSync {
                input.responseKeyEvent(SoftKey(code = InputModeSwitcher.USER_KEYCODE_LANG))
                assertTrue(InputModeSwitcher.isChinese)
                assertEquals(layout, InputModeSwitcher.skbLayout)
                assertEquals(schema, prefs.pinyinModeRime.getValue())
            }
            settleKeyboard()
            instrumentation.runOnMainSync {
                input.responseKeyEvent(SoftKey(code = InputModeSwitcher.USER_KEYCODE_LANG))
            }
            settleKeyboard()
            instrumentation.runOnMainSync {
                input.toggleTerminalMode()
                assertTrue(InputModeSwitcher.isChinese)
                assertEquals(layout, InputModeSwitcher.skbLayout)
                assertEquals(schema, prefs.pinyinModeRime.getValue())
            }
            settleKeyboard()
        }
        instrumentation.runOnMainSync {
            input.toggleTerminalMode()
            InputModeSwitcher.switchModeForSetting(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to CustomConstant.SCHEMA_ZH_QWERTY)
            input.toggleTerminalMode()
            assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, InputModeSwitcher.skbLayout)
            assertEquals(CustomConstant.SCHEMA_ZH_QWERTY, prefs.pinyinModeRime.getValue())
        }
        settleKeyboard()
        instrumentation.runOnMainSync {
            InputModeSwitcher.switchModeForSetting(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to CustomConstant.SCHEMA_ZH_QWERTY)
            editor.setText("")
        }
        settleKeyboard()
        instrumentation.runOnMainSync {
            input.toggleTerminalMode()
        }
        settleKeyboard()
        instrumentation.runOnMainSync {
            assertTrue("The host connection must accept direct text", input.terminalInput.sendText("binding_probe"))
        }
        awaitCondition("The active connection must target the debug editor", { diagnostics(input, editor) }) { editor.text.toString() == "binding_probe" }
        instrumentation.runOnMainSync { editor.setText("") }
        settleKeyboard()
        "abc".forEachIndexed { index, letter ->
            instrumentation.runOnMainSync {
                val keyboard = requireNotNull(findKeyboard(KeyboardManager.instance.currentContainer as View))
                val key = requireNotNull(keyboard.getSoftKeyboard()?.getKeyByCode(KeyEvent.KEYCODE_A + letter.code - 'a'.code))
                val x = (key.mLeft + key.mRight) / 2f
                val y = (key.mTop + key.mBottom) / 2f
                val now = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
                    keyboard.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            awaitCondition("Each English key must reach the host immediately", { diagnostics(input, editor) }) { editor.text.toString() == "abc".take(index + 1) }
            instrumentation.runOnMainSync {
                assertEquals("abc".take(index + 1), editor.text.toString())
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
            }
        }
        instrumentation.runOnMainSync {
            input.responseKeyEvent(SoftKey(code = InputModeSwitcher.USER_KEYCODE_LANG))
            input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_N))
            input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_I))
            input.chooseAndUpdate(0)
        }
        awaitCondition("Chinese text must reach the host") { editor.text.toString().drop(3).any { it.code in 0x4e00..0x9fff } }
        instrumentation.runOnMainSync {
            assertTrue("Chinese candidates must still commit Chinese text", editor.text.toString().drop(3).any { it.code in 0x4e00..0x9fff })
            assertEquals(CustomConstant.SCHEMA_ZH_QWERTY, prefs.pinyinModeRime.getValue())
            input.onSettingsMenuClick(SkbMenuMode.SwitchKeyboard)
            input.onSettingsMenuClick(SkbMenuMode.SwitchKeyboard)
            assertTrue(KeyboardManager.instance.isInputKeyboard)
            assertEquals("|", KeyPreset.qwertyKeyNumberPreset[KeyEvent.KEYCODE_L]!![1])
            assertEquals("|", KeyPreset.qwertyPYKeyNumberPreset[KeyEvent.KEYCODE_L]!![1])
        }
    }

    @Test
    fun flowerModeConfirmsSegmentsWithoutClosingItsSourceEditor() = withKeyboard { input, editor ->
        instrumentation.runOnMainSync {
            editor.setText("prefix ")
            editor.setSelection(editor.length())
        }
        settleKeyboard()
        instrumentation.runOnMainSync {
            AppPrefs.getInstance().internal.lastFlowerTypefaceMode.setValue("FlowerVine")
            input.onSettingsMenuClick(SkbMenuMode.FlowerTypeface)
            assertTrue(input.isTransformingText)
            assertEquals("取消", findKeyboard(input)?.getSoftKeyboard()?.getKeyByCode(KeyEvent.KEYCODE_ENTER)?.keyLabel)
            input.responseKeyEvent(SoftKey(label = "你好"))
        }
        awaitCondition("The host must display one transformed preview", { diagnostics(input, editor) }) { editor.text.toString().contains("ζั͡") }
        instrumentation.runOnMainSync {
            val source = flowerSource(input)
            assertEquals("你好", source.text.toString())
            assertEquals("完成", findKeyboard(input)?.getSoftKeyboard()?.getKeyByCode(KeyEvent.KEYCODE_ENTER)?.keyLabel)
            source.setSelection(0, source.length())
            input.responseKeyEvent(SoftKey(label = "世界"))
        }
        awaitCondition("Editing the source must replace its current preview") { editor.text.contains("世") && !editor.text.contains("你") }
        saveUiSnapshot("tmp_flower_preview.png")
        instrumentation.runOnMainSync { input.auxiliaryPanel.completeFlowerInput() }
        awaitCondition("Done must commit and retain the source focus") {
            input.isTransformingText && flowerSource(input).isFocused && flowerSource(input).text.isEmpty() &&
                BaseInputConnection.getComposingSpanStart(editor.text) == -1
        }
        saveUiSnapshot("tmp_flower_continue.png")
        instrumentation.runOnMainSync {
            assertEquals(com.yuyan.imemodule.data.flower.FlowerTypefaceMode.FlowerVine, CustomConstant.flowerTypeface)
            assertTrue(input.auxiliaryPanel.isShown)
            input.responseKeyEvent(SoftKey(label = "下一段"))
        }
        awaitCondition("A second segment must append a separate preview") { editor.text.count { it == 'ζ' } == 2 }
        instrumentation.runOnMainSync { input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_ENTER)) }
        awaitCondition("Keyboard Done must commit without adding a newline") {
            input.isTransformingText && flowerSource(input).text.isEmpty() && BaseInputConnection.getComposingSpanStart(editor.text) == -1
        }
        instrumentation.runOnMainSync {
            assertFalse(editor.text.contains("\n"))
            assertEquals("取消", findKeyboard(input)?.getSoftKeyboard()?.getKeyByCode(KeyEvent.KEYCODE_ENTER)?.keyLabel)
            input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_ENTER))
            assertFalse(input.isTransformingText)
            assertEquals(com.yuyan.imemodule.data.flower.FlowerTypefaceMode.Disabled, CustomConstant.flowerTypeface)
        }
    }

    @Test
    fun tappingTheHostUsesRawInputAndKeepsFlowerModeAvailable() = withKeyboard { input, editor ->
        instrumentation.runOnMainSync {
            editor.setText("")
            AppPrefs.getInstance().internal.lastFlowerTypefaceMode.setValue("FlowerVine")
        }
        settleKeyboard()
        instrumentation.runOnMainSync {
            input.toggleFlowerTypeface()
            input.responseKeyEvent(SoftKey(label = "hi"))
        }
        awaitCondition("The first flower preview must reach the host") { editor.text.contains("ζั͡") }
        tapEditorCaret(editor)
        awaitCondition("Tapping the existing host caret must enable raw input") { !input.isTransformingText }
        instrumentation.runOnMainSync {
            assertTrue(input.auxiliaryPanel.isShown)
            assertNotEquals(com.yuyan.imemodule.data.flower.FlowerTypefaceMode.Disabled, CustomConstant.flowerTypeface)
            input.responseKeyEvent(SoftKey(label = " RAW"))
        }
        awaitCondition("Raw host input must bypass flower conversion") { editor.text.toString().endsWith(" RAW") && editor.text.count { it == 'ζ' } == 1 }
        tapView(flowerSource(input))
        awaitCondition("Tapping the source must resume flower input") { input.isTransformingText && flowerSource(input).isFocused }
        instrumentation.runOnMainSync { input.responseKeyEvent(SoftKey(label = "next")) }
        awaitCondition("Resuming the source must create a new preview") { editor.text.count { it == 'ζ' } == 2 }
        instrumentation.runOnMainSync { input.auxiliaryPanel.completeFlowerInput() }
    }

    @Test
    fun toolbarControlsKeepClipboardPositionAndShowKeyboardSelectionState() = withKeyboard { input, _ ->
        val preset = com.yuyan.imemodule.data.menuSkbFunsPreset
        val baseline = listOf(SkbMenuMode.ClipBoard, SkbMenuMode.Emojicon, SkbMenuMode.TextEdit, SkbMenuMode.SwitchKeyboard).map { preset.getValue(it) }
        val clipboard = com.yuyan.imemodule.data.candidateToolbarItems(baseline, SkbMenuMode.ClipBoard)
        assertEquals(listOf(SkbMenuMode.ClearClipBoard, SkbMenuMode.LockClipBoard, SkbMenuMode.Phrases, SkbMenuMode.ClipBoard), clipboard.asReversed().map { it.skbMenuMode })
        var clipboardX: Int? = null
        instrumentation.runOnMainSync {
            input.resetToIdleState()
            input.updateCandidateBar()
        }
        settleKeyboard()
        instrumentation.runOnMainSync {
            menuItem(input, SkbMenuMode.ClipBoard)?.let { view ->
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                clipboardX = location[0]
            }
            input.onSettingsMenuClick(SkbMenuMode.SwitchKeyboard)
        }
        settleKeyboard()
        instrumentation.runOnMainSync {
            assertTrue("The keyboard switch must show its selected state", menuItem(input, SkbMenuMode.SwitchKeyboard)?.isSelected == true)
            val selector = KeyboardManager.instance.currentContainer as com.yuyan.imemodule.keyboard.container.SettingsContainer
            assertFalse(descendants(selector).filterIsInstance<TextView>().any { it.text.toString() == "终端模式" })
            input.onSettingsMenuClick(SkbMenuMode.SwitchKeyboard)
            input.onSettingsMenuClick(SkbMenuMode.ClipBoard)
        }
        settleKeyboard()
        instrumentation.runOnMainSync {
            val shown = descendants(input.mSkbCandidatesBarView).filter { it.isShown && it.tag is SkbMenuMode }.toList().sortedBy { view ->
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                location[0]
            }
            assertEquals(SkbMenuMode.ClearClipBoard, shown[0].tag)
            assertEquals(SkbMenuMode.LockClipBoard, shown[1].tag)
            clipboardX?.let { expected ->
                val location = IntArray(2)
                requireNotNull(menuItem(input, SkbMenuMode.ClipBoard)).getLocationOnScreen(location)
                assertEquals("The clipboard shortcut must retain its horizontal position", expected, location[0])
            }
            input.onSettingsMenuClick(SkbMenuMode.LockClipBoard)
            assertTrue(CustomConstant.lockClipBoardEnable)
            input.onSettingsMenuClick(SkbMenuMode.LockClipBoard)
            assertFalse(CustomConstant.lockClipBoardEnable)
            input.onSettingsMenuClick(SkbMenuMode.ClipBoard)
            input.onSettingsMenuClick(SkbMenuMode.SettingsMenu)
            val settings = KeyboardManager.instance.currentContainer as com.yuyan.imemodule.keyboard.container.SettingsContainer
            assertTrue(settings.funItems.any { it.skbMenuMode == SkbMenuMode.TerminalMode })
            assertFalse(settings.funItems.any { it.skbMenuMode.name == "TextTransform" })
        }
    }

    @Test
    fun flowerModeRemainsEnabledWhenTheHostRestartsItsConnection() = withKeyboard { input, editor ->
        instrumentation.runOnMainSync {
            AppPrefs.getInstance().internal.lastFlowerTypefaceMode.setValue("FlowerVine")
            input.toggleFlowerTypeface()
            input.responseKeyEvent(SoftKey(label = "first"))
        }
        awaitCondition("The first preview must arrive before restarting") { editor.text.contains("ζั͡") }
        instrumentation.runOnMainSync {
            editor.clearFocus()
            editor.requestFocus()
            editor.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).restartInput(editor)
        }
        settleKeyboard()
        awaitCondition("Restarting the host must retain flower mode and reactivate its source", {
            diagnostics(input, editor) + "; source=" + flowerSource(input).text + "; requested=" + input.wantsFlowerSourceInput
        }) {
            input.isTransformingText && input.auxiliaryPanel.isShown && flowerSource(input).text.isEmpty()
        }
        instrumentation.runOnMainSync { input.responseKeyEvent(SoftKey(label = "second")) }
        awaitCondition("The new connection must accept the next preview") { editor.text.toString().contains("s") && flowerSource(input).text.toString() == "second" }
        instrumentation.runOnMainSync { input.auxiliaryPanel.completeFlowerInput() }
    }

    private fun flowerSource(input: InputView) = descendants(input.auxiliaryPanel).filterIsInstance<EditText>().first()
    private fun menuItem(input: InputView, mode: SkbMenuMode) = descendants(input.mSkbCandidatesBarView).firstOrNull { it.isShown && it.tag == mode }
    private fun tapView(view: View) {
        val point = FloatArray(2)
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            point[0] = location[0] + view.width / 2f
            point[1] = location[1] + view.height / 2f
        }
        tapScreen(point[0], point[1])
    }
    private fun tapEditorCaret(editor: EditText) {
        val point = FloatArray(2)
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            editor.getLocationOnScreen(location)
            val layout = editor.layout
            val line = layout.getLineForOffset(editor.selectionEnd)
            point[0] = location[0] + editor.totalPaddingLeft + layout.getPrimaryHorizontal(editor.selectionEnd) + 2f
            point[1] = location[1] + editor.totalPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f - editor.scrollY
        }
        tapScreen(point[0], point[1])
    }
    private fun tapScreen(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
    }

    @Test
    fun realTermuxEditingPasteAndStickyInterrupt() {
        assumeTrue("Pass termuxUiTest=true to exercise an installed debuggable Termux", InstrumentationRegistry.getArguments().getString("termuxUiTest") == "true")
        assertTrue("A debuggable Termux is required for reading this test's result", shell("run-as com.termux id").contains("uid="))
        withKeyboard { input, _ ->
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val previousClip = clipboard.primaryClip
            val resultFile = "tmp_yuyan_terminal_${java.util.UUID.randomUUID().toString().replace("-", "")}.txt"
            var createdSession = false
            val service = InputView::class.java.getDeclaredField("service").apply { isAccessible = true }.get(input) as com.yuyan.imemodule.service.ImeService
            fun key(code: Int) {
                instrumentation.runOnMainSync { input.responseKeyEvent(SoftKey(code)) }
                Thread.sleep(200)
            }
            fun text(value: String) {
                var editing = false
                instrumentation.runOnMainSync {
                    editing = InputModeSwitcher.isTextEditSkb
                    if (editing) input.onSettingsMenuClick(SkbMenuMode.TextEdit)
                }
                if (editing) { settleKeyboard(); Thread.sleep(200) }
                instrumentation.runOnMainSync { input.responseKeyEvent(SoftKey(label = value)) }
                Thread.sleep(200)
                if (editing) {
                    instrumentation.runOnMainSync { input.onSettingsMenuClick(SkbMenuMode.TextEdit) }
                    settleKeyboard()
                    Thread.sleep(200)
                }
            }
            fun nativeKey(code: Int, meta: Int = 0) {
                instrumentation.runOnMainSync { input.terminalInput.sendKey(code, meta) }
                Thread.sleep(100)
            }
            fun readResult() = shell("run-as com.termux cat files/home/$resultFile")
            fun awaitResult(expected: String) {
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < deadline) {
                    if (readResult() == expected) return
                    Thread.sleep(100)
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    context.openFileOutput("tmp_terminal_regression_failure.png", android.content.Context.MODE_PRIVATE).use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                assertEquals("The real terminal must produce the expected result", expected, readResult())
            }
            fun closeSession() {
                nativeKey(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
                nativeKey(KeyEvent.KEYCODE_U, KeyEvent.META_CTRL_ON)
                nativeKey(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
                Thread.sleep(400)
                nativeKey(KeyEvent.KEYCODE_ENTER)
                settleKeyboard()
            }
            try {
                shell("am start -n com.termux/.app.TermuxActivity")
                awaitCondition("Termux must own the active input connection") {
                    service.currentInputEditorInfo?.packageName == "com.termux" && input.isShown
                }
                settleKeyboard()
                if (InstrumentationRegistry.getArguments().getString("termuxCloseTemporarySession") == "true") closeSession()
                nativeKey(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
                createdSession = true
                settleKeyboard()
                instrumentation.runOnMainSync {
                    if (!InputModeSwitcher.isTerminalMode) input.toggleTerminalMode()
                }
                settleKeyboard()
                text("printf '%s' ready > $resultFile")
                key(KeyEvent.KEYCODE_ENTER)
                awaitResult("ready")
                settleKeyboard()
                text("printf '%s' termnal")
                instrumentation.runOnMainSync { input.onSettingsMenuClick(SkbMenuMode.TextEdit) }
                settleKeyboard()
                repeat(3) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                settleKeyboard()
                text("i")
                settleKeyboard()
                repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                settleKeyboard()
                text("_")
                settleKeyboard()
                key(InputModeSwitcher.USER_KEYCODE_MOVE_START)
                key(InputModeSwitcher.USER_KEYCODE_MOVE_END)
                settleKeyboard()
                text(" > $resultFile")
                settleKeyboard()
                key(KeyEvent.KEYCODE_ENTER)
                awaitResult("termina_l")
                instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("Terminal regression", "paste_ok")) }
                text("printf '%s' ")
                settleKeyboard()
                key(InputModeSwitcher.USER_KEYCODE_PASTE)
                settleKeyboard()
                text(" >> $resultFile")
                settleKeyboard()
                key(KeyEvent.KEYCODE_ENTER)
                awaitResult("termina_lpaste_ok")
                text("sleep 60 && printf '%s' unexpected >> $resultFile")
                settleKeyboard()
                key(KeyEvent.KEYCODE_ENTER)
                settleKeyboard()
                instrumentation.runOnMainSync { input.terminalInput.modifiers.toggle(KeyEvent.KEYCODE_CTRL_LEFT) }
                key(KeyEvent.KEYCODE_C)
                instrumentation.runOnMainSync { assertEquals(TerminalModifiers.State.Off, input.terminalInput.modifiers.ctrl) }
                settleKeyboard()
                text("printf '%s' interrupt_ok >> $resultFile")
                settleKeyboard()
                key(KeyEvent.KEYCODE_ENTER)
                awaitResult("termina_lpaste_okinterrupt_ok")
                text("stty -icanon -echo; dd bs=1 count=2 2>/dev/null | od -An -tx1 > $resultFile; stty icanon echo")
                key(KeyEvent.KEYCODE_ENTER)
                settleKeyboard()
                instrumentation.runOnMainSync { input.terminalInput.modifiers.toggle(KeyEvent.KEYCODE_ALT_LEFT) }
                key(KeyEvent.KEYCODE_ENTER)
                val altDeadline = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < altDeadline && readResult() !in setOf("1b 0a", "1b 0d")) Thread.sleep(100)
                assertTrue("Alt+Enter must deliver Escape followed by a newline", readResult() in setOf("1b 0a", "1b 0d"))
                text("stty -icanon -echo; dd bs=1 count=5 2>/dev/null | od -An -tx1 > $resultFile; stty icanon echo")
                key(KeyEvent.KEYCODE_ENTER)
                settleKeyboard()
                openTerminalFunctions(input)
                val functionKey = descendants(input.auxiliaryPanel.terminalKeys).first { it.tag == "terminal_function_key" }
                val originalKeyboard = KeyboardManager.instance.currentContainer
                tapView(functionKey)
                tapKeyboardKey(input, KeyEvent.KEYCODE_1)
                tapKeyboardKey(input, KeyEvent.KEYCODE_2)
                tapView(functionKey)
                awaitResult("1b 5b 32 34 7e")
                instrumentation.runOnMainSync {
                    assertSame("Termux function-key input must restore its original keyboard", originalKeyboard, KeyboardManager.instance.currentContainer)
                }
            } finally {
                if (createdSession && service.currentInputEditorInfo?.packageName == "com.termux") closeSession()
                shell("run-as com.termux rm -f files/home/$resultFile")
                instrumentation.runOnMainSync {
                    if (previousClip != null) clipboard.setPrimaryClip(previousClip)
                    else if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    private fun withKeyboard(block: (InputView, EditText) -> Unit) {
        val previousIme = shell("settings get secure default_input_method")
        val previousEnabled = shell("settings get secure enabled_input_methods")
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val suggestionKey = AppPrefs.getInstance().clipboard.clipboardSuggestion.key
        val keys = listOf("input_default_method_mode", "input_method_pinyin_mode", "input_method_pinyin_mode_rime", "flower_typeface_mode", "last_flower_typeface_mode", suggestionKey)
        val previous = keys.associateWith { preferences.all[it] }
        val previousLock = CustomConstant.lockClipBoardEnable
        preferences.edit().putString("flower_typeface_mode", "Disabled").putBoolean(suggestionKey, false).commit()
        CustomConstant.lockClipBoardEnable = false
        var activity: android.app.Activity? = null
        var input: InputView? = null
        try {
            val component = "${context.packageName}/com.yuyan.imemodule.service.ImeService"
            shell("ime enable $component")
            shell("ime set $component")
            shell("settings put secure default_input_method $component")
            activity = instrumentation.startActivitySync(Intent(context, ImeTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            shell("am start -n ${context.packageName}/com.yuyan.ImeTestActivity --activity-single-top")
            val editor = activity.findViewById<EditText>(android.R.id.edit)
            instrumentation.runOnMainSync {
                editor.requestFocus()
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            val deadline = SystemClock.uptimeMillis() + 60_000
            var ready = false
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    var ancestor: View? = KeyboardManager.instance.currentContainer as? View
                    while (ancestor != null && ancestor !is InputView) ancestor = ancestor.parent as? View
                    input = ancestor as? InputView
                    ready = input?.isShown == true && editor.hasFocus() && editor.hasWindowFocus() &&
                        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).isActive(editor)
                }
                if (ready && EngineRuntime.isReady) break
                Thread.sleep(100)
            }
            val view = requireNotNull(input) { "Debug IME did not become visible" }
            var readinessDetails = ""
            if (!ready) instrumentation.runOnMainSync { readinessDetails = diagnostics(view, editor) }
            assertTrue("The debug host must have an active input connection; $readinessDetails", ready)
            assertTrue("The Chinese engine must be ready", EngineRuntime.isReady)
            instrumentation.runOnMainSync { if (InputModeSwitcher.isTerminalMode) view.toggleTerminalMode() }
            instrumentation.waitForIdleSync()
            block(view, editor)
        } finally {
            instrumentation.runOnMainSync {
                input?.finishAuxiliaryInput()
                CustomConstant.lockClipBoardEnable = previousLock
                if (InputModeSwitcher.isTerminalMode) input?.toggleTerminalMode()
                activity?.finish()
            }
            preferences.edit().apply {
                previous.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is Int -> putInt(key, value)
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }.commit()
            instrumentation.runOnMainSync {
                val mode = (previous["input_default_method_mode"] as? Int) ?: InputModeSwitcher.MODE_T9_CHINESE
                InputModeSwitcher.saveInputMode(mode)
            }
            shell("settings put secure enabled_input_methods '$previousEnabled'")
            shell("settings put secure default_input_method '$previousIme'")
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun saveUiSnapshot(name: String) {
        settleKeyboard()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            context.openFileOutput(name, android.content.Context.MODE_PRIVATE).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun awaitCondition(message: String, details: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var passed = false
            instrumentation.runOnMainSync { passed = condition() }
            if (passed) return
            Thread.sleep(50)
        }
        var detail = ""
        instrumentation.runOnMainSync { detail = details() }
        fail("$message; $detail")
    }

    private fun settleKeyboard() {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5000)
        Thread.sleep(250)
    }

    private fun diagnostics(input: InputView, editor: EditText): String {
        val service = InputView::class.java.getDeclaredField("service").apply { isAccessible = true }.get(input) as com.yuyan.imemodule.service.ImeService
        val sample = KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, InputModeSwitcher.mToggleStates.modifiers, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD)
        return "host=${editor.text}; attached=${editor.isAttachedToWindow}; focused=${editor.hasFocus()}; windowFocus=${editor.hasWindowFocus()}; activeEditor=${context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).isActive(editor)}; terminal=${InputModeSwitcher.isTerminalMode}; english=${InputModeSwitcher.isEnglish}; panel=${input.isTransformingText}; addPhrase=${input.isAddPhrases}; shown=${input.isShown}; editor=${service.currentInputEditorInfo?.packageName}/${service.currentInputEditorInfo?.fieldId}; connection=${service.currentInputConnection}; active=${service.activeInputConnection}; local=${input.localInputConnection}; sampleUnicode=${sample.unicodeChar}; sampleSystem=${sample.isSystem}"
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
    }

    private fun findKeyboard(view: View): BaseKeyboardView? = descendants(view).filterIsInstance<BaseKeyboardView>().firstOrNull { it.isShown }
}
