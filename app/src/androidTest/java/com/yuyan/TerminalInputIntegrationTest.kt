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
        }
        settleKeyboard()
        tapKeyboardKey(input, KeyEvent.KEYCODE_ENTER)
        awaitCondition("Alt+Enter must be emitted as a modified Enter key") {
            events.any { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_ENTER && it.isAltPressed }
        }
        instrumentation.runOnMainSync {
            assertEquals(TerminalModifiers.State.Off, input.terminalInput.modifiers.alt)
            events.clear()
            input.processKeyUp(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_ALT_ON))
        }
        awaitCondition("Hardware Alt+Enter must also reach the target in Chinese mode") {
            events.any { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_ENTER && it.isAltPressed }
        }
        instrumentation.runOnMainSync {
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
            assertEquals(listOf("ESC", "TAB", "CTRL", "ALT", "PGUP", "PGDN", "收起",
                "SHIFT", "DEL", "F(n)", "^Z", "^C", "^L", "退出"), labels)
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
            descendants(input.auxiliaryPanel.terminalKeys).filterIsInstance<TextView>().firstOrNull { it.text.toString() == "更多" }?.performClick()
        }
        awaitCondition("The numbered function-key button must become visible") {
            descendants(input.auxiliaryPanel.terminalKeys).any { it.tag == "terminal_function_key" && it.isShown }
        }
    }

    @Test
    fun composingBubbleKeepsTopCornersAndAcceptsRealCaretTaps() = withKeyboard { input, editor ->
        val env = com.yuyan.imemodule.singleton.EnvironmentSingleton.instance
        val floating = env.keyboardModeFloat
        val theme = com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
        try {
            for (mode in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    env.keyboardModeFloat = mode
                    env.initData()
                    InputModeSwitcher.switchModeForSetting(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to CustomConstant.SCHEMA_ZH_QWERTY)
                    input.updateTheme()
                    input.resetToIdleState()
                    editor.text.clear()
                    "nihao".forEach { input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_A + (it - 'a'))) }
                }
                settleKeyboard()
                val bubble = descendants(input).filterIsInstance<TextView>().first { it.isShown && it.text.toString().contains("ni") && it.text.toString().contains("hao") }
                val location = IntArray(2)
                var x = 0f
                var y = 0f
                instrumentation.runOnMainSync {
                    val background = bubble.background as android.graphics.drawable.GradientDrawable
                    val corners = requireNotNull(background.cornerRadii)
                    assertTrue(corners[0] > 0)
                    assertEquals(if (mode) corners[0] else 0f, corners[4], 0.001f)
                    assertEquals(theme.keyTextColor, bubble.currentTextColor)
                    bubble.getLocationOnScreen(location)
                    x = location[0] + bubble.paddingLeft + bubble.layout.getPrimaryHorizontal(2)
                    y = location[1] + bubble.height / 2f
                }
                tapScreen(x, y)
                awaitCondition("A real screen tap must enter composition editing") { com.yuyan.imemodule.service.DecodingInfo.caretInComposition >= 0 }
                instrumentation.runOnMainSync {
                    input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_M))
                    assertEquals("nimhao", com.yuyan.inputmethod.core.Rime.getRawInput())
                    assertTrue(editor.text.isEmpty())
                    assertTrue("Composing editing must retain host focus", editor.hasFocus())
                    assertFalse(bubble.isFocused)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                input.resetToIdleState()
                env.keyboardModeFloat = floating
                env.initData()
                input.updateTheme()
            }
        }
    }

    @Test
    fun terminalExpansionPersistsAndEditorEnterCarriesAlt() = withKeyboard { input, editor ->
        val events = mutableListOf<KeyEvent>()
        instrumentation.runOnMainSync {
            if (!InputModeSwitcher.isTerminalMode) input.toggleTerminalMode()
            editor.setOnKeyListener { _, _, event -> events.add(KeyEvent(event)); true }
            val first = (input.auxiliaryPanel.terminalKeys.getChildAt(0) as ViewGroup)
            assertEquals("ESC", (first.getChildAt(0) as TextView).text.toString())
            assertEquals("TAB", (first.getChildAt(1) as TextView).text.toString())
        }
        openTerminalFunctions(input)
        instrumentation.runOnMainSync {
            val labels = descendants(input.auxiliaryPanel.terminalKeys).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()
            assertEquals(listOf("SHIFT", "DEL", "F(n)", "^Z", "^C", "^L", "退出"), labels.takeLast(7))
            input.finishAuxiliaryInput()
            input.auxiliaryPanel.onStartInput(false)
            assertTrue(descendants(input.auxiliaryPanel.terminalKeys).any { it.tag == "terminal_function_key" && it.isShown })
            input.onSettingsMenuClick(SkbMenuMode.TextEdit)
        }
        settleKeyboard()
        instrumentation.runOnMainSync {
            val keys = requireNotNull(findKeyboard(input)?.getSoftKeyboard())
            assertNotNull(keys.getKeyByCode(KeyEvent.KEYCODE_ENTER))
            assertNull(keys.getKeyByCode(KeyEvent.KEYCODE_DEL))
            input.terminalInput.modifiers.toggle(KeyEvent.KEYCODE_ALT_LEFT)
        }
        tapKeyboardKey(input, KeyEvent.KEYCODE_ENTER)
        awaitCondition("Editor Enter must retain Alt") { events.any { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_ENTER && it.isAltPressed } }
        instrumentation.runOnMainSync {
            input.toggleTerminalMode()
            val keys = requireNotNull(findKeyboard(input)?.getSoftKeyboard())
            assertNotNull(keys.getKeyByCode(KeyEvent.KEYCODE_DEL))
            editor.setOnKeyListener(null)
        }
    }

    @Test
    fun reorderedMenuCanCloseReopenAndDragAgain() = withKeyboard { input, _ ->
        instrumentation.runOnMainSync { input.onSettingsMenuClick(SkbMenuMode.SettingsMenu) }
        settleKeyboard()
        repeat(2) {
            val container = KeyboardManager.instance.currentContainer as com.yuyan.imemodule.keyboard.container.SettingsContainer
            val beforeOrder = container.funItems.map { it.skbMenuMode }
            instrumentation.runOnMainSync { container.enableDragItem(true) }
            settleKeyboard()
            val recycler = descendants(container).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first()
            saveUiSnapshot("tmp_menu_drag_before.png")
            val origin = IntArray(2)
            val target = IntArray(2)
            instrumentation.runOnMainSync {
                val first = recycler.getChildAt(0).findViewById<View>(com.yuyan.imemodule.R.id.entrance_image)
                val second = recycler.getChildAt(1).findViewById<View>(com.yuyan.imemodule.R.id.entrance_image)
                first.getLocationOnScreen(origin); second.getLocationOnScreen(target)
                origin[0] += first.width / 2; origin[1] += first.height / 2
                target[0] += second.width / 2; target[1] += second.height / 2
                target[0] += (12 * context.resources.displayMetrics.density).toInt()
            }
            val time = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                instrumentation.sendPointerSync(event); event.recycle()
            }
            send(MotionEvent.ACTION_DOWN, origin[0].toFloat(), origin[1].toFloat())
            Thread.sleep(150)
            for (step in 1..12) {
                send(MotionEvent.ACTION_MOVE, origin[0] + (target[0] - origin[0]) * step / 12f, origin[1] + (target[1] - origin[1]) * step / 12f)
                Thread.sleep(40)
            }
            Thread.sleep(150)
            send(MotionEvent.ACTION_UP, target[0].toFloat(), target[1].toFloat())
            settleKeyboard()
            saveUiSnapshot("tmp_menu_drag_after.png")
            instrumentation.runOnMainSync {
                container.enableDragItem(false)
                assertNotEquals("The gesture must actually reorder items", beforeOrder, container.funItems.map { it.skbMenuMode })
                input.onSettingsMenuClick(SkbMenuMode.SettingsMenu)
                input.onSettingsMenuClick(SkbMenuMode.SettingsMenu)
            }
            settleKeyboard()
            instrumentation.runOnMainSync {
                val reopened = KeyboardManager.instance.currentContainer as com.yuyan.imemodule.keyboard.container.SettingsContainer
                assertTrue(reopened.isShown)
                assertEquals(reopened.funItems.map { it.skbMenuMode.name }, com.yuyan.imemodule.database.DataBaseKT.instance.skbFunDao().getAllMenu().map { it.name })
            }
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
    fun numberSidebarAlignsWithItsKeySurfaceAfterResizing() = withKeyboard { input, _ ->
        val manager = KeyboardManager.instance
        val env = com.yuyan.imemodule.singleton.EnvironmentSingleton.instance
        val originalHeight = env.keyBoardHeightRatio
        val originalType = requireNotNull(manager.currentKeyboardType)
        try {
            for (height in listOf(0.3f, 0.22f)) {
                instrumentation.runOnMainSync {
                    env.keyBoardHeightRatio = height
                    env.initData()
                    com.yuyan.imemodule.utils.KeyboardLoaderUtil.instance.clearKeyboardMap()
                    manager.clearKeyboard()
                    manager.switchKeyboard(KeyboardManager.KeyboardType.NUMBER)
                }
                settleKeyboard()
                instrumentation.runOnMainSync {
                    val container = requireNotNull(manager.currentContainer)
                    val keyboard = requireNotNull(findKeyboard(input))
                    val softKeyboard = requireNotNull(keyboard.getSoftKeyboard())
                    val key = requireNotNull(softKeyboard.getKeyByCode(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL))
                    val symbols = container.findViewById<View>(com.yuyan.imemodule.R.id.rv_prefix)
                    assertEquals("The list must align with the visible key surface", key.mLeft + softKeyboard.keyXMargin, symbols.left)
                    assertEquals(key.mRight - softKeyboard.keyXMargin, symbols.right)
                    assertEquals(key.mTop + softKeyboard.keyYMargin, symbols.top)
                    assertEquals(key.mBottom - softKeyboard.keyYMargin, symbols.bottom)
                    assertEquals("Symbol area must share the key's horizontal center", (key.mLeft + key.mRight) / 2f, (symbols.left + symbols.right) / 2f, 0.5f)
                }
                saveUiSnapshot("tmp_number_symbols_centered.png")
            }
        } finally {
            instrumentation.runOnMainSync {
                env.keyBoardHeightRatio = originalHeight
                env.initData()
                com.yuyan.imemodule.utils.KeyboardLoaderUtil.instance.clearKeyboardMap()
                manager.clearKeyboard()
                manager.switchKeyboard(originalType)
            }
        }
    }

    @Test
    fun clipboardSearchKeepsQueriesLocalAndPastesTheFilteredRow() = withKeyboard { input, editor ->
        val database = com.yuyan.imemodule.database.DataBaseKT.instance
        val prefix = "clip" + java.util.UUID.randomUUID().toString().filter { it in 'a'..'f' }.take(6)
        val first = "${prefix}Alpha 剪贴板检索"
        val second = "${prefix}Beta 另一条记录"
        val chinese = "${prefix}你好检索"
        val query = prefix + "alpha"
        try {
            database.clipboardDao().insert(com.yuyan.imemodule.database.entry.Clipboard(first))
            database.clipboardDao().insert(com.yuyan.imemodule.database.entry.Clipboard(second))
            database.clipboardDao().insert(com.yuyan.imemodule.database.entry.Clipboard(chinese))
            instrumentation.runOnMainSync {
                if (InputModeSwitcher.isTerminalMode) input.toggleTerminalMode()
                InputModeSwitcher.switchModeForSetting(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to CustomConstant.SCHEMA_ZH_QWERTY)
                input.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_LANG))
                input.resetToIdleState()
                editor.setText("host:")
                editor.setSelection(editor.length())
            }
            settleKeyboard()
            instrumentation.runOnMainSync { input.onSettingsMenuClick(SkbMenuMode.ClipBoard) }
            settleKeyboard()
            instrumentation.runOnMainSync {
                val toolbar = com.yuyan.imemodule.data.candidateToolbarItems(emptyList(), SkbMenuMode.ClipBoard).map { it.skbMenuMode }
                assertEquals("Search must sit left of delete in the reversed toolbar",
                    listOf(SkbMenuMode.ClearClipBoard, SkbMenuMode.ClipboardSearch), toolbar.takeLast(2))
                assertFalse("Without a query the filter bar must not take space",
                    descendants(input).any { it.tag == "clipboard_search_label" && it.isShown })
                assertEquals("The floating enter key must follow its switch",
                    AppPrefs.getInstance().clipboard.clipboardFloatingEnter.getValue(),
                    descendants(input).any { it.tag == "clipboard_floating_enter" && it.isShown })
                input.onSettingsMenuClick(SkbMenuMode.ClipboardSearch)
                assertTrue(input.isClipboardSearchActive)
                val source = descendants(input).filterIsInstance<android.widget.EditText>().first { it.tag == "clipboard_search_query" }
                assertTrue("The search box must take local focus so the caret is drawn", source.isFocused)
            }
            settleKeyboard()
            instrumentation.runOnMainSync {
                assertTrue("Search must survive switching back to the typing keyboard", input.isClipboardSearchActive)
                if (!InputModeSwitcher.isEnglish) input.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_LANG))
            }
            awaitCondition("Search requires the English typing mode", { diagnostics(input, editor) }) { EngineRuntime.isReady && InputModeSwitcher.isEnglish }
            query.forEach { char -> tapKeyboardKey(input, KeyEvent.KEYCODE_A + (char - 'a')) }
            instrumentation.runOnMainSync { context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).restartInput(editor) }
            settleKeyboard()
            instrumentation.runOnMainSync {
                assertTrue("Input restart must retain local search", input.isClipboardSearchActive)
                assertTrue("Input restart must retain the selected language", InputModeSwitcher.isEnglish)
                val source = descendants(input).filterIsInstance<android.widget.EditText>().first { it.tag == "clipboard_search_query" }
                assertEquals("Search route active=${input.isClipboardSearchActive}, host=${editor.text}, local=${input.localInputConnection}, english=${InputModeSwitcher.isEnglish}, ready=${EngineRuntime.isReady}, raw=${com.yuyan.inputmethod.core.Rime.getRawInput()}", query, source.text.toString())
                assertEquals("Search input must not reach the host editor", "host:", editor.text.toString())
                descendants(input).first { it.tag == "clipboard_search_submit" }.performClick()
                assertFalse(input.isClipboardSearchActive)
            }
            settleKeyboard()
            val container = requireNotNull(KeyboardManager.instance.currentContainer as? com.yuyan.imemodule.keyboard.container.ClipBoardContainer)
            saveUiSnapshot("tmp_clipboard_search.png")
            instrumentation.runOnMainSync {
                val list = descendants(container).filterIsInstance<com.yuyan.imemodule.libs.recyclerview.SwipeRecyclerView>().single()
                assertEquals(1, list.adapter!!.itemCount)
                val text = descendants(list).filterIsInstance<TextView>().first { it.id == com.yuyan.imemodule.R.id.clipboard_adapter_content }
                assertEquals(first, text.text.toString())
                assertEquals(android.view.Gravity.LEFT, android.view.Gravity.getAbsoluteGravity(text.gravity, View.LAYOUT_DIRECTION_LTR) and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
                assertTrue("The filtered row must be clickable", text.parent.let { (it as View).performClick() } || list.findViewHolderForAdapterPosition(0)!!.itemView.performClick())
            }
            awaitCondition("Pasting must select the matching row", { editor.text.toString() }) { editor.text.toString() == "host:$first" }
            instrumentation.runOnMainSync { input.onSettingsMenuClick(SkbMenuMode.ClipBoard) }
            settleKeyboard()
            instrumentation.runOnMainSync {
                val current = KeyboardManager.instance.currentContainer as com.yuyan.imemodule.keyboard.container.ClipBoardContainer
                current.showClipBoardView(SkbMenuMode.ClipBoard, prefix + "nomatch")
                val list = descendants(current).filterIsInstance<com.yuyan.imemodule.libs.recyclerview.SwipeRecyclerView>().single()
                assertEquals(0, list.adapter!!.itemCount)
                assertTrue(descendants(current).filterIsInstance<TextView>().any { it.text.toString() == "没有匹配的内容" && it.isShown })
                assertTrue("A query shows the filter bar", descendants(current).any { it.tag == "clipboard_search_label" && it.isShown })
                descendants(current).first { it.tag == "clipboard_search_clear" }.performClick()
                assertTrue(list.adapter!!.itemCount >= 2)
                input.onSettingsMenuClick(SkbMenuMode.ClipboardSearch)
            }
            settleKeyboard()
            tapKeyboardKey(input, KeyEvent.KEYCODE_A)
            instrumentation.runOnMainSync {
                descendants(input).first { it.tag == "clipboard_search_cancel" }.performClick()
                assertFalse(input.isClipboardSearchActive)
                assertEquals("Cancelling search must not modify the host", "host:$first", editor.text.toString())
                input.onSettingsMenuClick(SkbMenuMode.ClipboardSearch)
                if (InputModeSwitcher.isEnglish) input.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_LANG))
            }
            settleKeyboard()
            awaitCondition("Chinese search must retain its input mode") { EngineRuntime.isReady && InputModeSwitcher.isChinese }
            "nihao".forEach { tapKeyboardKey(input, KeyEvent.KEYCODE_A + (it - 'a')) }
            instrumentation.runOnMainSync {
                val candidate = com.yuyan.inputmethod.RimeEngine.showCandidates.indexOfFirst { it.text == "你好" }
                assertTrue("Chinese search must expose matching candidates", candidate >= 0)
                input.chooseAndUpdate(candidate)
                val source = descendants(input).filterIsInstance<android.widget.EditText>().first { it.tag == "clipboard_search_query" }
                assertEquals("你好", source.text.toString())
                assertEquals("host:$first", editor.text.toString())
                descendants(input).first { it.tag == "clipboard_search_submit" }.performClick()
            }
            settleKeyboard()
            instrumentation.runOnMainSync {
                val current = KeyboardManager.instance.currentContainer as com.yuyan.imemodule.keyboard.container.ClipBoardContainer
                assertTrue("Chinese search must show the matching clipboard record", descendants(current).filterIsInstance<TextView>().any { it.id == com.yuyan.imemodule.R.id.clipboard_adapter_content && it.text.toString() == chinese })
            }
        } finally {
            database.clipboardDao().deleteByContent(first)
            database.clipboardDao().deleteByContent(second)
            database.clipboardDao().deleteByContent(chinese)
        }
    }

    @Test
    fun repeatedEnglishShiftPreservesEveryTypedCapital() = withKeyboard { input, editor ->
        val prefs = AppPrefs.getInstance().input
        val previousCompletion = prefs.abcSearchEnglishCell.getValue()
        val previousSpace = prefs.abcSpaceAuto.getValue()
        try {
            for (completion in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    prefs.abcSearchEnglishCell.setValue(completion)
                    prefs.abcSpaceAuto.setValue(false)
                    if (InputModeSwitcher.isTerminalMode) input.toggleTerminalMode()
                    InputModeSwitcher.switchModeForSetting(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to CustomConstant.SCHEMA_ZH_QWERTY)
                    input.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_LANG))
                    input.resetToIdleState()
                    editor.text.clear()
                }
                settleKeyboard()
                for ((letter, expected) in listOf(KeyEvent.KEYCODE_A to "A", KeyEvent.KEYCODE_B to "AB")) {
                    tapKeyboardKey(input, KeyEvent.KEYCODE_SHIFT_LEFT)
                    tapKeyboardKey(input, letter)
                    awaitCondition("A repeated one-shot shift must preserve $expected with completion=$completion") { editor.text.toString() == expected }
                    assertTrue("Typing must consume the one-shot shift", InputModeSwitcher.isLower)
                }
                tapKeyboardKey(input, KeyEvent.KEYCODE_C)
                awaitCondition("Typing after a one-shot capital must return to lower case") { editor.text.toString() == "ABc" }
                if (completion) {
                    instrumentation.runOnMainSync {
                        val candidate = com.yuyan.inputmethod.RimeEngine.showCandidates.indexOfFirst { it.text == "ABc" }
                        assertTrue("The literal candidate must retain mixed case", candidate >= 0)
                        input.chooseAndUpdate(candidate)
                    }
                    awaitCondition("Selecting the candidate must retain typed capitals") { editor.text.toString() == "ABc" }
                }
                instrumentation.runOnMainSync {
                    input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_SHIFT_LEFT))
                    input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_SHIFT_LEFT))
                    assertEquals("Two consecutive shift taps must still enable caps lock", KeyEvent.META_CAPS_LOCK_ON, InputModeSwitcher.mToggleStates.modifiers)
                }
                tapKeyboardKey(input, KeyEvent.KEYCODE_D)
                assertEquals(KeyEvent.META_CAPS_LOCK_ON, InputModeSwitcher.mToggleStates.modifiers)
                tapKeyboardKey(input, KeyEvent.KEYCODE_SHIFT_LEFT)
                assertTrue("One tap must unlock caps lock", InputModeSwitcher.isLower)
                tapKeyboardKey(input, KeyEvent.KEYCODE_E)
                awaitCondition("Caps lock must preserve the existing prefix") { editor.text.toString() == "ABcDe" }
            }
        } finally {
            instrumentation.runOnMainSync {
                prefs.abcSearchEnglishCell.setValue(previousCompletion)
                prefs.abcSpaceAuto.setValue(previousSpace)
                input.resetToIdleState()
            }
        }
    }

    @Test
    fun keyboardPositionTracksSinglePixelDrags() = withKeyboard { input, _ ->
        val env = com.yuyan.imemodule.singleton.EnvironmentSingleton.instance
        val prefs = AppPrefs.getInstance().internal
        val manager = KeyboardManager.instance
        val previousFloating = env.keyboardModeFloat
        val previousType = requireNotNull(manager.currentKeyboardType)
        val paddingKeys = listOf(prefs.keyboardBottomPadding, prefs.keyboardRightPadding,
            prefs.keyboardBottomPaddingFloat, prefs.keyboardRightPaddingFloat,
            prefs.keyboardBottomPaddingLandscapeFloat, prefs.keyboardRightPaddingLandscapeFloat)
        val previousPadding = paddingKeys.associateWith { it.getValue() }
        try {
            for (floating in listOf(false, true)) {
                val bottom = if (!floating) prefs.keyboardBottomPadding else if (env.isLandscape)
                    prefs.keyboardBottomPaddingLandscapeFloat else prefs.keyboardBottomPaddingFloat
                instrumentation.runOnMainSync {
                    bottom.setValue(40)
                    env.keyboardModeFloat = floating
                    env.initData()
                    manager.clearKeyboard()
                    manager.switchKeyboard(KeyboardManager.KeyboardType.QWERTYABC)
                }
                settleKeyboard()
                val container = requireNotNull(manager.currentContainer)
                instrumentation.runOnMainSync { container.setKeyboardHeight() }
                settleKeyboard()
                val handle = container.findViewById<View>(com.yuyan.imemodule.R.id.iv_keyboard_move)
                for (distance in listOf(-1f, -2f, 1f, 2f)) {
                    val expected = bottom.getValue() - distance.toInt()
                    dragResizeHandle(handle, distance)
                    settleKeyboard()
                    instrumentation.runOnMainSync {
                        assertEquals("A small drag must persist pixel-level movement", expected, bottom.getValue())
                        assertEquals(expected, if (floating) input.paddingBottom else input.mSkbRoot.paddingBottom)
                    }
                }
                dragResizeHandle(handle, 1000f)
                assertEquals("Dragging down must stop at the lower edge", 0, bottom.getValue())
                dragResizeHandle(handle, -1f)
                assertEquals("The keyboard must be movable after reaching the edge", 1, bottom.getValue())
                instrumentation.runOnMainSync { container.findViewById<View>(com.yuyan.imemodule.R.id.ll_keyboard_height_sure).performClick() }
                settleKeyboard()
                if (floating) {
                    val bar = input.mSkbRoot.findViewById<ViewGroup>(com.yuyan.imemodule.R.id.iv_keyboard_holder).getChildAt(0)
                    dragResizeHandle(bar, -1f)
                    assertEquals("The floating drag bar must use the same fine movement", 2, bottom.getValue())
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                previousPadding.forEach { (key, value) -> key.setValue(value) }
                env.keyboardModeFloat = previousFloating
                env.initData()
                manager.clearKeyboard()
                manager.switchKeyboard(previousType)
            }
        }
    }

    @Test
    fun keyboardResizeKeepsKeysAttachedAndAdjustmentControlsUsable() = withKeyboard { input, editor ->
        val env = com.yuyan.imemodule.singleton.EnvironmentSingleton.instance
        val manager = KeyboardManager.instance
        val savedHeight = env.keyBoardHeightRatio
        val savedFloating = env.keyboardModeFloat
        val savedBorder = com.yuyan.imemodule.data.theme.ThemeManager.prefs.keyBorder.getValue()
        val savedMargin = com.yuyan.imemodule.data.theme.ThemeManager.prefs.keyYMargin.getValue()
        val initialType = requireNotNull(manager.currentKeyboardType)
        try {
            for (floating in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    com.yuyan.imemodule.data.theme.ThemeManager.prefs.keyBorder.setValue(true)
                    com.yuyan.imemodule.data.theme.ThemeManager.prefs.keyYMargin.setValue(100)
                    env.keyboardModeFloat = floating
                    env.keyBoardHeightRatio = 0.3f
                    env.initData()
                    com.yuyan.imemodule.utils.KeyboardLoaderUtil.instance.clearKeyboardMap()
                    manager.clearKeyboard()
                }
                for (type in listOf(KeyboardManager.KeyboardType.QWERTYABC, KeyboardManager.KeyboardType.T9, KeyboardManager.KeyboardType.NUMBER)) {
                    instrumentation.runOnMainSync { manager.switchKeyboard(type) }
                    settleKeyboard()
                    val container = requireNotNull(manager.currentContainer)
                    var handle: View? = null
                    instrumentation.runOnMainSync {
                        container.setKeyboardHeight()
                        container.setKeyboardHeight()
                        assertEquals("Repeated adjustment entry must reuse its overlay", 1,
                            descendants(container).count { it.id == com.yuyan.imemodule.R.id.iv_keyboard_height_Top })
                        handle = container.findViewById(com.yuyan.imemodule.R.id.iv_keyboard_height_Top)
                    }
                    settleKeyboard()
                    val keyboard = requireNotNull(findKeyboard(input))
                    val beforeHeight = keyboard.height
                    val rootLayoutParamsType = input.rootView.layoutParams.javaClass
                    dragResizeHandle(requireNotNull(handle), -90f * context.resources.displayMetrics.density)
                    settleKeyboard()
                    instrumentation.runOnMainSync {
                        assertSame("Resizing must retain the attached keyboard container", container, manager.currentContainer)
                        assertTrue("Resizing must keep keys visible", keyboard.isShown && keyboard.isAttachedToWindow)
                        assertTrue("Dragging upward must increase the key area", keyboard.height > beforeHeight)
                        assertEquals(env.skbHeight, keyboard.height)
                        assertTrue("Resize controls must remain above the keyboard", handle!!.isShown)
                        assertEquals("Resizing must not replace the IME window layout parameters", rootLayoutParamsType, input.rootView.layoutParams.javaClass)
                        val bar = descendants(input).filterIsInstance<com.yuyan.imemodule.view.CandidatesBar>().first()
                        val barPosition = IntArray(2).also(bar::getLocationOnScreen)
                        val keyPosition = IntArray(2).also(keyboard::getLocationOnScreen)
                        assertTrue("Toolbar must end above the first key row", barPosition[1] + bar.height <= keyPosition[1])
                        descendants(bar).filterIsInstance<android.widget.ImageView>().filter { it.isShown }.forEach { icon ->
                            val position = IntArray(2).also(icon::getLocationOnScreen)
                            assertTrue("Toolbar icons must fit without vertical clipping", position[1] >= barPosition[1] && position[1] + icon.height <= barPosition[1] + bar.height)
                        }
                    }
                    val expandedHeight = keyboard.height
                    dragResizeHandle(requireNotNull(handle), 60f * context.resources.displayMetrics.density)
                    settleKeyboard()
                    instrumentation.runOnMainSync {
                        assertSame(container, manager.currentContainer)
                        assertEquals(env.skbHeight, keyboard.height)
                        assertTrue("Dragging downward must reduce the key area", keyboard.height < expandedHeight)
                        val bitmap = android.graphics.Bitmap.createBitmap(keyboard.width, keyboard.height, android.graphics.Bitmap.Config.ARGB_8888)
                        try {
                            keyboard.draw(android.graphics.Canvas(bitmap))
                            val space = requireNotNull(keyboard.getSoftKeyboard()?.getKeyByCode(if (type == KeyboardManager.KeyboardType.NUMBER) KeyEvent.KEYCODE_0 else KeyEvent.KEYCODE_SPACE))
                            assertTrue("Resized keys must retain usable hit bounds", space.width() > 0 && space.height() > 0)
                            assertTrue("Resized keys must still render", bitmap.getPixel((space.mLeft + space.mRight) / 2, (space.mTop + space.mBottom) / 2) != 0)
                        } finally { bitmap.recycle() }
                        container.findViewById<View>(com.yuyan.imemodule.R.id.ll_keyboard_height_reset).performClick()
                    }
                    settleKeyboard()
                    instrumentation.runOnMainSync {
                        assertSame("Reset must not detach the current keyboard", container, manager.currentContainer)
                        assertEquals(env.skbHeight, keyboard.height)
                        container.findViewById<View>(com.yuyan.imemodule.R.id.ll_keyboard_height_sure).performClick()
                        assertNull(container.findViewById<View>(com.yuyan.imemodule.R.id.iv_keyboard_height_Top))
                    }
                    if (!floating && type == KeyboardManager.KeyboardType.QWERTYABC) saveUiSnapshot("tmp_keyboard_resize.png")
                }
            }
            instrumentation.runOnMainSync { manager.switchKeyboard(KeyboardManager.KeyboardType.QWERTYABC); editor.text.clear() }
            settleKeyboard()
            tapKeyboardKey(input, KeyEvent.KEYCODE_SPACE)
            awaitCondition("Typing must still reach the editor after resizing") { editor.text.toString() == " " }
        } finally {
            instrumentation.runOnMainSync {
                env.keyboardModeFloat = savedFloating
                com.yuyan.imemodule.data.theme.ThemeManager.prefs.keyBorder.setValue(savedBorder)
                com.yuyan.imemodule.data.theme.ThemeManager.prefs.keyYMargin.setValue(savedMargin)
                env.keyBoardHeightRatio = savedHeight
                env.initData()
                com.yuyan.imemodule.utils.KeyboardLoaderUtil.instance.clearKeyboardMap()
                manager.clearKeyboard()
                manager.switchKeyboard(initialType)
            }
        }
    }

    private fun dragResizeHandle(handle: View, deltaY: Float) {
        val location = IntArray(2)
        var downTime = 0L
        instrumentation.runOnMainSync { handle.getLocationOnScreen(location); downTime = SystemClock.uptimeMillis() }
        val screenX = location[0] + handle.width / 2f
        val screenY = location[1] + handle.height / 2f
        for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0f,
            MotionEvent.ACTION_MOVE to deltaY / 3, MotionEvent.ACTION_MOVE to deltaY * 2 / 3,
            MotionEvent.ACTION_MOVE to deltaY, MotionEvent.ACTION_UP to deltaY)) {
            instrumentation.runOnMainSync {
                handle.getLocationOnScreen(location)
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    screenX, screenY + offset, 0)
                event.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
                handle.dispatchTouchEvent(event)
                event.recycle()
            }
            instrumentation.waitForIdleSync()
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
    fun flowerSourceSupportsCaretTapsInsertionAndBlinkWithoutStealingHostFocus() = withKeyboard { input, editor ->
        instrumentation.runOnMainSync {
            AppPrefs.getInstance().internal.lastFlowerTypefaceMode.setValue("FlowerVine")
            input.toggleFlowerTypeface()
            input.responseKeyEvent(SoftKey(label = "abcdef"))
        }
        settleKeyboard()
        val source = flowerSource(input)
        fun tapOffset(offset: Int) {
            val point = FloatArray(2)
            instrumentation.runOnMainSync {
                val location = IntArray(2)
                source.getLocationOnScreen(location)
                val line = source.layout.getLineForOffset(offset)
                point[0] = location[0] + source.totalPaddingLeft + source.layout.getPrimaryHorizontal(offset) - source.scrollX
                point[1] = location[1] + source.totalPaddingTop + (source.layout.getLineTop(line) + source.layout.getLineBottom(line)) / 2f - source.scrollY
            }
            tapScreen(point[0], point[1])
            awaitCondition("Tapping the source must move its local caret to $offset") { source.selectionStart == offset && source.selectionEnd == offset }
        }
        tapOffset(2)
        instrumentation.runOnMainSync {
            assertTrue("The host must retain window focus", editor.hasWindowFocus())
            assertFalse("The IME source must not become a second system input target", source.hasWindowFocus())
            input.responseKeyEvent(SoftKey(label = "X"))
            assertEquals("abXcdef", source.text.toString())
            input.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_DEL))
            assertEquals("abcdef", source.text.toString())
            val buttons = descendants(input.auxiliaryPanel).filterIsInstance<TextView>().filter { it.tag == "flower_provider" || it.tag == "flower_action" }.toList()
            assertEquals(2, buttons.size)
            assertEquals("Style and confirmation must use the same font size", buttons[0].textSize, buttons[1].textSize, 0.01f)
            assertTrue("The single-line panel should stay close to toolbar height", input.auxiliaryPanel.height <= input.mSkbCandidatesBarView.height + context.resources.displayMetrics.density * 6)
            source.setText("abcd\nwxyz")
            source.setSelection(source.length())
        }
        settleKeyboard()
        tapOffset(6)
        instrumentation.runOnMainSync {
            input.responseKeyEvent(SoftKey(label = "X"))
            assertEquals("abcd\nwXxyz", source.text.toString())
            source.setText("abcdef")
            source.setSelection(source.length())
        }
        settleKeyboard()
        val colors = mutableSetOf<Int>()
        repeat(8) {
            instrumentation.runOnMainSync {
                val bitmap = android.graphics.Bitmap.createBitmap(source.width, source.height, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    source.draw(android.graphics.Canvas(bitmap))
                    val x = (source.totalPaddingLeft + source.layout.getPrimaryHorizontal(source.selectionStart) - source.scrollX + 1).toInt().coerceIn(0, bitmap.width - 1)
                    val y = (source.totalPaddingTop + source.layout.getLineBottom(0) / 2 - source.scrollY).coerceIn(0, bitmap.height - 1)
                    colors.add(bitmap.getPixel(x, y))
                } finally { bitmap.recycle() }
            }
            Thread.sleep(150)
        }
        var caretDetails = ""
        instrumentation.runOnMainSync {
            caretDetails = "editing=${input.isTransformingText}; focused=${source.isFocused}; local=${(source as com.yuyan.imemodule.view.ImeSourceEditText).localEditing}; text=${source.text}; selection=${source.selectionStart}; shown=${source.isShown}; cursor=${source.isCursorVisible}; window=${source.hasWindowFocus()}; colors=$colors; size=${source.width}x${source.height}; scroll=${source.scrollX},${source.scrollY}"
        }
        assertTrue("The local caret must alternate visible and hidden phases; $caretDetails", colors.size > 1)
        saveUiSnapshot("tmp_flower_editor_compact.png")
        instrumentation.runOnMainSync { input.auxiliaryPanel.completeFlowerInput() }
        awaitCondition("Confirmation must retain the local editor for the next segment") { input.isTransformingText && source.text.isEmpty() && source.isFocused }
    }

    @Test
    fun symbolCategoryBarClipsCornersAndKeepsTheGridAboveIt() = withKeyboard { input, _ ->
        instrumentation.runOnMainSync { KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SYMBOL) }
        val container = KeyboardManager.instance.currentContainer as com.yuyan.imemodule.keyboard.container.SymbolContainer
        for (mode in listOf(com.yuyan.imemodule.prefs.behavior.SymbolMode.Symbol,
            com.yuyan.imemodule.prefs.behavior.SymbolMode.Emojicon, com.yuyan.imemodule.prefs.behavior.SymbolMode.Emoticon,
            com.yuyan.imemodule.prefs.behavior.SymbolMode.Symbol)) {
            instrumentation.runOnMainSync {
                if (mode == com.yuyan.imemodule.prefs.behavior.SymbolMode.Symbol) container.setSymbolsView() else container.setEmojisView(mode)
            }
            settleKeyboard()
            instrumentation.runOnMainSync {
                val tabs = container.findViewById<com.google.android.material.tabs.TabLayout>(com.yuyan.imemodule.R.id.tab_symbols_emoji_type)
                val pager = descendants(container).filterIsInstance<androidx.viewpager2.widget.ViewPager2>().first()
                val bar = container.findViewById<View>(com.yuyan.imemodule.R.id.ll_symbols_emoji_type_item)
                assertTrue("Category tabs must clip their round background", tabs.clipToOutline)
                assertTrue("Symbol grid must not extend behind the category bar", pager.bottom <= bar.top)
                assertTrue("Symbol grid must retain visible content", pager.height > 0)
                val bitmap = android.graphics.Bitmap.createBitmap(tabs.width, tabs.height, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    tabs.background.setBounds(0, 0, tabs.width, tabs.height)
                    tabs.background.draw(android.graphics.Canvas(bitmap))
                    assertEquals("Category background corners must be transparent", 0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
                } finally { bitmap.recycle() }
                if (tabs.tabCount > 1) {
                    tabs.getTabAt(1)!!.select()
                    assertEquals("Category selection must keep controlling the pager", 1, pager.currentItem)
                }
            }
        }
        saveUiSnapshot("tmp_symbol_categories_rounded.png")
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
        awaitCondition("Done must commit and retain the source focus", { "editing=${input.isTransformingText}; focus=${flowerSource(input).isFocused}; source=${flowerSource(input).text}; host=${editor.text}; composing=${BaseInputConnection.getComposingSpanStart(editor.text)}" }) {
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
        awaitCondition("Tapping the source must resume flower input", { "editing=${input.isTransformingText}; focus=${flowerSource(input).isFocused}; source=${flowerSource(input).text}; visible=${flowerSource(input).isShown}; size=${flowerSource(input).width}x${flowerSource(input).height}" }) { input.isTransformingText && flowerSource(input).isFocused }
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
            val historyFile = resultFile + ".history"
            val termuxPid = shell("pidof com.termux").toInt()
            fun shellChildren(): Set<Int> = shell("ps -A -o PID,PPID,NAME").lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size >= 3 && it[1].toIntOrNull() == termuxPid && it[2] in setOf("bash", "zsh", "fish", "sh", "login") }
                .mapNotNull { it[0].toIntOrNull() }.toSet()
            val originalShells = shellChildren()
            var testShell: Int? = null
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
                instrumentation.runOnMainSync { input.terminalInput.modifiers.clear() }
                nativeKey(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
                nativeKey(KeyEvent.KEYCODE_U, KeyEvent.META_CTRL_ON)
                nativeKey(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
                val deadline = SystemClock.uptimeMillis() + 5000
                while (testShell in shellChildren() && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
                settleKeyboard()
            }
            try {
                shell("am start -n com.termux/.app.TermuxActivity")
                awaitCondition("Termux must own the active input connection") {
                    service.currentInputEditorInfo?.packageName == "com.termux" && input.isShown
                }
                settleKeyboard()
                nativeKey(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
                val sessionDeadline = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < sessionDeadline) {
                    val added = shellChildren() - originalShells
                    if (added.size == 1) { testShell = added.single(); break }
                    Thread.sleep(100)
                }
                assertNotNull("A separate Termux shell must exist before sending test commands", testShell)
                createdSession = true
                settleKeyboard()
                instrumentation.runOnMainSync {
                    if (!InputModeSwitcher.isTerminalMode) input.toggleTerminalMode()
                }
                settleKeyboard()
                text("cd /data/data/com.termux/files/home; HISTFILE=/data/data/com.termux/files/home/$historyFile; history -c; printf '%s' ready > $resultFile")
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
                text(": > $resultFile")
                key(KeyEvent.KEYCODE_ENTER)
                text("printf '%s\\n' repeat >> $resultFile")
                tapKeyboardKey(input, KeyEvent.KEYCODE_ENTER)
                awaitResult("repeat")
                tapKeyboardKey(input, KeyEvent.KEYCODE_DPAD_UP)
                tapKeyboardKey(input, KeyEvent.KEYCODE_ENTER)
                awaitResult("repeat\nrepeat")
                text("stty -icanon -echo; dd bs=1 count=2 2>/dev/null | od -An -tx1 > $resultFile; stty icanon echo")
                key(KeyEvent.KEYCODE_ENTER)
                settleKeyboard()
                instrumentation.runOnMainSync { input.terminalInput.modifiers.toggle(KeyEvent.KEYCODE_ALT_LEFT) }
                tapKeyboardKey(input, KeyEvent.KEYCODE_ENTER)
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
                if (createdSession && testShell in shellChildren() && service.currentInputEditorInfo?.packageName == "com.termux") closeSession()
                shell("run-as com.termux rm -f files/home/$resultFile files/home/$historyFile")
                instrumentation.runOnMainSync {
                    if (previousClip != null) clipboard.setPrimaryClip(previousClip)
                    else if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                assertTrue("Original Termux shells must survive the test", shellChildren().containsAll(originalShells))
            }
        }
    }

    @Test
    fun handwritingPausePreviewsAndSelectionReplacesWithoutDuplication() = withHandwriting { input, editor, keyboard ->
        drawHandwritingStroke(keyboard, .12f, .3f, .65f, .3f)
        awaitCondition("Pausing must display the first handwriting candidate", {
            diagnostics(input, editor) + "; ink=" + keyboard.hasHandwritingInput +
                "; model=" + com.yuyan.imemodule.handwriting.HandwritingClient.state +
                "; candidates=" + com.yuyan.imemodule.service.DecodingInfo.candidates +
                "; views=" + descendants(input).filterIsInstance<TextView>().filter { it.isShown }.joinToString { it.text.toString() }
        }) { editor.text.isNotEmpty() }
        var selected = ""
        instrumentation.runOnMainSync {
            val candidates = com.yuyan.imemodule.service.DecodingInfo.candidates
            assertTrue("Handwriting candidates must remain available without prediction", candidates.isNotEmpty())
            assertEquals(candidates.first().text, editor.text.toString())
            assertTrue("The first candidate must remain replaceable", BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
            val index = if (candidates.size > 1) 1 else 0
            selected = candidates[index].text
            input.chooseAndUpdate(index)
        }
        awaitCondition("Choosing must replace the preview exactly once") {
            editor.text.toString() == selected && BaseInputConnection.getComposingSpanStart(editor.text) == -1
        }
        drawHandwritingStroke(keyboard, .12f, .3f, .65f, .3f)
        awaitCondition("A second character must append its own preview") {
            val first = com.yuyan.imemodule.service.DecodingInfo.getCandidate(0)?.text
            first != null && editor.text.toString() == selected + first
        }
        saveUiSnapshot("tmp_handwriting_preview.png")
    }

    @Test
    fun handwritingStrokeCanEndOverSpaceAndSpaceStillWorksAsAButton() = withHandwriting { input, editor, keyboard ->
        drawHandwritingStroke(keyboard, .4f, .12f, .5f, .91f)
        awaitCondition("Ending a stroke over a bottom key must still recognize and preview") { editor.text.isNotBlank() }
        var committed = ""
        instrumentation.runOnMainSync {
            committed = com.yuyan.imemodule.service.DecodingInfo.getCandidate(0)!!.text
            input.chooseAndUpdate(0)
        }
        awaitCondition("The crossing stroke must commit once") { editor.text.toString() == committed }
        tapKeyboardKey(input, KeyEvent.KEYCODE_SPACE)
        awaitCondition("A fresh tap on space must retain its button action") { editor.text.toString() == committed + " " }
    }

    @Test
    fun handwritingContinuationLocksBottomSidebarAndToolbarUntilPause() = withHandwriting { input, editor, keyboard ->
        instrumentation.runOnMainSync { AppPrefs.getInstance().handwriting.handWritingSpeed.setValue(1300) }
        drawHandwritingStroke(keyboard, .12f, .3f, .65f, .3f)
        val container = KeyboardManager.instance.currentContainer
        val controls = listOf(
            .08f to .9f, .25f to .9f, .5f to .9f, .74f to .9f, .92f to .9f,
            .92f to .64f, .92f to .12f, .92f to .4f, .2f to -.08f, .94f to -.08f,
        )
        controls.forEach { (x, y) ->
            sendHandwritingTouch(keyboard, MotionEvent.ACTION_DOWN, x, y)
            sendHandwritingTouch(keyboard, MotionEvent.ACTION_UP, x, y)
            instrumentation.runOnMainSync {
                assertSame("A continuation starting over a control must not switch panels", container, KeyboardManager.instance.currentContainer)
                assertTrue("Chinese handwriting mode must remain active", InputModeSwitcher.isChineseHandWriting)
                assertTrue("Every continuation must keep the current character open", keyboard.canContinueWriting(SystemClock.uptimeMillis()))
                assertEquals("Controls and candidates must not commit during continuous writing", "", editor.text.toString())
                assertTrue("The keyboard close control must be blocked", input.isShown)
            }
        }
        awaitCondition("The pause deadline must release the controls") { !keyboard.canContinueWriting(SystemClock.uptimeMillis()) }
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_DOWN, .08f, .9f)
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_UP, .08f, .9f)
        awaitCondition("The symbol button must work again after pausing") {
            KeyboardManager.instance.currentContainer is com.yuyan.imemodule.keyboard.container.SymbolContainer
        }
    }

    @Test
    fun handwritingSecondStrokeCanStartOnTheIndependentSymbolSidebar() = withHandwriting { input, editor, keyboard ->
        instrumentation.runOnMainSync { AppPrefs.getInstance().handwriting.handWritingSpeed.setValue(1300) }
        drawHandwritingStroke(keyboard, .12f, .3f, .65f, .3f)
        val container = KeyboardManager.instance.currentContainer
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_DOWN, .92f, .12f)
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_MOVE, .75f, .25f)
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_MOVE, .55f, .5f)
        instrumentation.runOnMainSync {
            assertSame(container, KeyboardManager.instance.currentContainer)
            assertEquals("Starting over the sidebar must not insert its punctuation", "", editor.text.toString())
            assertTrue(keyboard.canContinueWriting(SystemClock.uptimeMillis()))
        }
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_CANCEL, .55f, .5f)
        instrumentation.runOnMainSync {
            assertFalse("Cancellation must release the writing lock", keyboard.canContinueWriting(SystemClock.uptimeMillis()))
            assertFalse(keyboard.hasHandwritingInput)
            assertEquals("Cancelled continuation must not commit", "", editor.text.toString())
        }
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_DOWN, .92f, .12f)
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_UP, .92f, .12f)
        awaitCondition("The independent sidebar must accept taps after cancellation") { editor.text.isNotEmpty() }
    }

    @Test
    fun handwritingHeldStrokeAndCancelNeverSubmitPartialInk() = withHandwriting { input, editor, keyboard ->
        instrumentation.runOnMainSync { AppPrefs.getInstance().handwriting.handWritingSpeed.setValue(1300) }
        drawHandwritingStroke(keyboard, .12f, .25f, .65f, .25f)
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_DOWN, .12f, .45f)
        Thread.sleep(1450)
        instrumentation.runOnMainSync {
            assertEquals("An active stroke must cancel the previous pause timer", "", editor.text.toString())
        }
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_CANCEL, .12f, .45f)
        Thread.sleep(1500)
        instrumentation.runOnMainSync {
            assertFalse(keyboard.hasHandwritingInput)
            assertEquals("Cancelled ink must not be committed", "", editor.text.toString())
            assertTrue(com.yuyan.imemodule.service.DecodingInfo.isCandidatesEmpty)
            AppPrefs.getInstance().handwriting.handWritingSpeed.setValue(300)
        }
        drawHandwritingStroke(keyboard, .12f, .3f, .65f, .3f)
        awaitCondition("The keyboard must recover after cancellation") { editor.text.isNotEmpty() }
        tapKeyboardKey(input, KeyEvent.KEYCODE_DEL)
        awaitCondition("Backspace must retract the handwriting preview") { editor.text.isEmpty() && !keyboard.hasHandwritingInput }
        Thread.sleep(600)
        instrumentation.runOnMainSync { assertTrue("Deleted ink must not return", editor.text.isEmpty()) }
    }

    @Test
    fun handwritingFailureFeedbackIsVisibleButCannotBecomeInput() = withHandwriting { input, editor, _ ->
        listOf(com.yuyan.imemodule.handwriting.HandwritingResult.Empty,
            com.yuyan.imemodule.handwriting.HandwritingResult.Failed,
            com.yuyan.imemodule.handwriting.HandwritingResult.TimedOut,
            com.yuyan.imemodule.handwriting.HandwritingResult.Unavailable).forEach { failure ->
            instrumentation.runOnMainSync { input.showHandwritingResult(failure, false) }
            awaitCondition("Recognition failures must remain visible in the candidate bar") {
                descendants(input).filterIsInstance<android.widget.TextView>().any {
                    it.isShown && (it.text.contains("重新书写") || it.text.contains("模型未就绪"))
                }
            }
            instrumentation.runOnMainSync {
                assertTrue("Feedback must not enter the candidate data", com.yuyan.imemodule.service.DecodingInfo.isCandidatesEmpty)
                input.chooseAndUpdate(0)
                assertEquals("Feedback must not be selectable", "", editor.text.toString())
            }
        }
        instrumentation.runOnMainSync { input.cancelHandwritingInput() }
    }

    private fun withHandwriting(block: (InputView, EditText, com.yuyan.imemodule.keyboard.HandwritingKeyboard) -> Unit) = withKeyboard { input, editor ->
        val speed = AppPrefs.getInstance().handwriting.handWritingSpeed
        val prediction = AppPrefs.getInstance().input.chinesePrediction
        val oldSpeed = speed.getValue()
        val oldPrediction = prediction.getValue()
        try {
            instrumentation.runOnMainSync {
                speed.setValue(500)
                prediction.setValue(false)
                input.onWindowShown()
                InputModeSwitcher.saveInputMode(InputModeSwitcher.MODE_HANDWRITING_CHINESE)
                KeyboardManager.instance.switchKeyboard()
                input.cancelHandwritingInput()
                editor.text.clear()
            }
            awaitCondition("The handwriting model must be available for real touch regression") {
                com.yuyan.imemodule.handwriting.HandwritingClient.isReady
            }
            val keyboard = requireNotNull(KeyboardManager.instance.handwritingKeyboard)
            awaitCondition("The handwriting hierarchy must finish layout before coordinate-based input") {
                keyboard.isShown && keyboard.width > 0 && keyboard.height > 0 &&
                    !keyboard.isLayoutRequested && !(keyboard.parent as View).isLayoutRequested && !input.isLayoutRequested
            }
            instrumentation.waitForIdleSync()
            block(input, editor, keyboard)
        } finally {
            instrumentation.runOnMainSync {
                input.cancelHandwritingInput()
                speed.setValue(oldSpeed)
                prediction.setValue(oldPrediction)
                input.onWindowShown()
            }
        }
    }

    private fun drawHandwritingStroke(keyboard: View, startX: Float, startY: Float, endX: Float, endY: Float) {
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_DOWN, startX, startY)
        repeat(20) { index ->
            val fraction = (index + 1) / 20f
            sendHandwritingTouch(keyboard, MotionEvent.ACTION_MOVE,
                startX + (endX - startX) * fraction, startY + (endY - startY) * fraction)
            Thread.sleep(8)
        }
        sendHandwritingTouch(keyboard, MotionEvent.ACTION_UP, endX, endY)
    }

    private var handwritingDownTime = 0L
    private fun sendHandwritingTouch(keyboard: View, action: Int, x: Float, y: Float) {
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) handwritingDownTime = now
            var ancestor: View? = keyboard
            while (ancestor != null && ancestor !is InputView) ancestor = ancestor.parent as? View
            val input = requireNotNull(ancestor as? InputView) { "The keyboard must belong to the input view" }
            val keyboardLocation = IntArray(2)
            val inputLocation = IntArray(2)
            keyboard.getLocationOnScreen(keyboardLocation)
            input.getLocationOnScreen(inputLocation)
            val event = MotionEvent.obtain(handwritingDownTime, now, action,
                keyboard.width * x + keyboardLocation[0] - inputLocation[0],
                keyboard.height * y + keyboardLocation[1] - inputLocation[1], 0)
            try { assertTrue("The handwriting gesture must be consumed by the real input hierarchy", input.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
    }

    private fun withKeyboard(block: (InputView, EditText) -> Unit) {
        val previousIme = shell("settings get secure default_input_method").trim('\'', '"')
        val previousEnabled = shell("settings get secure enabled_input_methods").trim('\'', '"')
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val suggestionKey = AppPrefs.getInstance().clipboard.clipboardSuggestion.key
        val keys = listOf("input_default_method_mode", "input_method_pinyin_mode", "input_method_pinyin_mode_rime", "flower_typeface_mode", "last_flower_typeface_mode", "terminal_functions_expanded", suggestionKey)
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
            var editor = activity.findViewById<EditText>(android.R.id.edit)
            instrumentation.runOnMainSync {
                editor.requestFocus()
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            val deadline = SystemClock.uptimeMillis() + 60_000
            var ready = false
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<ImeTestActivity>().firstOrNull()
                    if (resumed != null && resumed !== activity) {
                        activity = resumed
                        editor = resumed.findViewById(android.R.id.edit)
                    }
                    if (editor.hasWindowFocus() && !editor.hasFocus()) editor.requestFocus()
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
            shell("settings put secure enabled_input_methods $previousEnabled")
            shell("settings put secure default_input_method $previousIme")
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
