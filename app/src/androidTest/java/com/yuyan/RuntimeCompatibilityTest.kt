package com.yuyan

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.widget.CompoundButton
import android.widget.FrameLayout
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemePreset
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.keyboard.TextKeyboard
import com.yuyan.imemodule.view.preference.ImeSwitchPreference
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
import com.yuyan.imemodule.prefs.restore
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

    @Test
    @SuppressLint("RestrictedApi")
    fun settingsSwitchKeepsPersistenceAndDefaultRestorationInBothThemes() {
        instrumentation.runOnMainSync {
            for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val configuration = Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
                }
                val themed = ContextThemeWrapper(context.createConfigurationContext(configuration), com.yuyan.imemodule.R.style.Theme_AppTheme)
                themed.theme.applyStyle(com.yuyan.imemodule.R.style.PreferenceThemeOverlay_Modern, true)
                val manager = PreferenceManager(themed).apply { sharedPreferencesName = "ui_refresh_test" }
                val preferences = requireNotNull(manager.sharedPreferences)
                preferences.edit().clear().commit()
                try {
                    val screen = manager.createPreferenceScreen(themed)
                    val preference = ImeSwitchPreference(themed).apply {
                        key = "switch"
                        setDefaultValue(true)
                    }
                    screen.addPreference(preference)
                    val adapter = PreferenceGroupAdapter(screen)
                    val holder = adapter.onCreateViewHolder(FrameLayout(themed), adapter.getItemViewType(0))
                    adapter.onBindViewHolder(holder, 0)
                    val toggle = holder.itemView.findViewById<CompoundButton>(com.yuyan.imemodule.R.id.switchWidget)
                    assertNotNull("The preference must bind a compatible switch", toggle)
                    assertTrue(toggle.isChecked)
                    holder.itemView.performClick()
                    assertFalse("A row tap must persist the new value", preferences.getBoolean("switch", true))
                    preference.restore()
                    assertTrue("Restore must retain the original default", preferences.getBoolean("switch", false))
                } finally {
                    preferences.edit().clear().commit()
                }
            }
        }
    }

    @Test
    fun pressedKeysPreserveOpaqueSurfacesAndBorderlessEnterGeometry() {
        instrumentation.runOnMainSync {
            val prefs = ThemeManager.prefs
            val previousBorder = prefs.keyBorder.getValue()
            val previousRadius = prefs.keyRadius.getValue()
            val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            val keyboard = TextKeyboard(context)
            val key = object : SoftKey(KeyEvent.KEYCODE_A) {
                override val keyIcon: Drawable? get() = null
            }.apply { mRight = 200; mBottom = 200 }
            try {
                prefs.keyBorder.setValue(true)
                prefs.keyRadius.setValue(16)
                keyboard.setSoftKeyboard(SoftKeyboard(listOf(listOf(key))))
                keyboard.layout(0, 0, 200, 200)
                for (theme in listOf(ThemePreset.MaterialLight, ThemePreset.MaterialDark)) {
                    keyboard.setTheme(theme)
                    key.onPressed()
                    keyboard.invalidateKey()
                    bitmap.eraseColor(Color.TRANSPARENT)
                    keyboard.draw(Canvas(bitmap))
                    assertEquals("A pressed opaque key must not lose its base surface", 255, Color.alpha(bitmap.getPixel(100, 100)))
                    assertNotEquals("A press must visibly change the base color", theme.keyBackgroundColor, bitmap.getPixel(100, 100))
                }
                prefs.keyBorder.setValue(false)
                key.code = KeyEvent.KEYCODE_ENTER
                key.onReleased()
                keyboard.setTheme(ThemePreset.MaterialLight)
                bitmap.eraseColor(Color.TRANSPARENT)
                keyboard.draw(Canvas(bitmap))
                val corner = bitmap.getPixel(30, 30)
                key.onPressed()
                keyboard.invalidateKey()
                bitmap.eraseColor(Color.TRANSPARENT)
                keyboard.draw(Canvas(bitmap))
                assertEquals("Pressing borderless Enter must preserve its rounded outline", corner, bitmap.getPixel(30, 30))
                assertEquals(255, Color.alpha(bitmap.getPixel(100, 100)))
            } finally {
                keyboard.closing()
                bitmap.recycle()
                prefs.keyBorder.setValue(previousBorder)
                prefs.keyRadius.setValue(previousRadius)
            }
        }
    }

    @Before
    fun verifyPageSize() {
        if (InstrumentationRegistry.getArguments().getString("require16Kb") == "true") {
            assertEquals("A 16 KB runtime is required", 16384L, Os.sysconf(OsConstants._SC_PAGESIZE))
        }
    }

    @Test
    fun sidebarGlyphsStayVisuallyCenteredAtDifferentSizes() {
        instrumentation.runOnMainSync {
            val view = com.yuyan.imemodule.view.popup.AutoScaleTextView(context).apply {
                setTextColor(Color.BLACK)
                textSize = 24f
                setPadding(6, 6, 6, 6)
                scaleMode = com.yuyan.imemodule.view.popup.AutoScaleTextView.Mode.Proportional
                centerGlyphBounds = true
            }
            for ((width, height) in listOf(150 to 120, 70 to 50)) {
                for (symbol in listOf("+", "-", "=", "%", ".", "()", "12345")) {
                    view.text = symbol
                    view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        var left = width
                        var right = -1
                        var top = height
                        var bottom = -1
                        for (y in 0 until height) for (x in 0 until width) {
                            if (Color.alpha(bitmap.getPixel(x, y)) > 32) {
                                left = minOf(left, x); right = maxOf(right, x)
                                top = minOf(top, y); bottom = maxOf(bottom, y)
                            }
                        }
                        assertTrue("The symbol must render: $symbol", right >= left && bottom >= top)
                        assertEquals("The symbol must be horizontally centered: $symbol", width / 2f, (left + right + 1) / 2f, 1.5f)
                        assertEquals("The symbol must be vertically centered: $symbol", height / 2f, (top + bottom + 1) / 2f, 1.5f)
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    @Test
    fun keyMaskRoundTripPreservesLegacyThemeColorsAndPhotoBrightness() {
        val files = com.yuyan.imemodule.data.theme.ThemeFilesManager
        val (name, cropped, source) = files.newCustomBackgroundImages()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val original = ThemePreset.TransparentLight.deriveCustomBackground(name, cropped.path, source.path, brightness = 37)
        try {
            for (file in listOf(cropped, source)) file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            files.saveThemeFiles(original)
            val jsonFile = java.io.File(cropped.parentFile, "$name.json")
            val json = org.json.JSONObject(jsonFile.readText())
            assertFalse("The control must reuse existing color fields", json.has("keyBackgroundOpacity"))
            assertEquals("Opening an existing theme must preserve its distinct key alphas", original, files.listThemes().first { it.name == name })
            for (strength in listOf(0, 37, 100)) {
                val updated = original.withKeyBackgroundOpacity(strength)
                files.saveThemeFiles(updated)
                val restored = files.listThemes().first { it.name == name }
                assertEquals(strength, restored.keyBackgroundOpacity)
                assertEquals(Color.alpha(restored.keyBackgroundColor), Color.alpha(restored.functionKeyBackgroundColor))
                assertEquals(Color.alpha(restored.keyBackgroundColor), Color.alpha(restored.accentKeyBackgroundColor))
                assertEquals(original.keyBackgroundColor and 0xffffff, restored.keyBackgroundColor and 0xffffff)
                assertEquals(original.keyTextColor, restored.keyTextColor)
                assertEquals(original.accentKeyTextColor, restored.accentKeyTextColor)
                assertEquals(original.backgroundImage, restored.backgroundImage)
                assertEquals(original.keyboardColor, restored.keyboardColor)
                assertEquals(original.popupBackgroundColor, restored.popupBackgroundColor)
            }
        } finally {
            bitmap.recycle()
            files.deleteThemeFiles(original)
        }
    }

    @Test
    fun keyMaskSliderReusesPreviewAndSurvivesSaveAndKeyVariantChanges() {
        val files = com.yuyan.imemodule.data.theme.ThemeFilesManager
        val (name, cropped, source) = files.newCustomBackgroundImages()
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(110, 145, 175)) }
        val original = ThemePreset.TransparentLight.deriveCustomBackground(name, cropped.path, source.path, brightness = 47)
        var activity: com.yuyan.imemodule.ui.fragment.theme.CustomThemeActivity? = null
        val themeField = com.yuyan.imemodule.ui.fragment.theme.CustomThemeActivity::class.java.getDeclaredField("theme").apply { isAccessible = true }
        fun descendants(view: android.view.View): Sequence<android.view.View> = sequence {
            yield(view)
            if (view is android.view.ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }
        fun open(theme: com.yuyan.imemodule.data.theme.Theme.Custom): com.yuyan.imemodule.ui.fragment.theme.CustomThemeActivity {
            val intent = android.content.Intent(context, com.yuyan.imemodule.ui.fragment.theme.CustomThemeActivity::class.java)
                .putExtra(com.yuyan.imemodule.ui.fragment.theme.CustomThemeActivity.ORIGIN_THEME, theme)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val result = instrumentation.startActivitySync(intent) as com.yuyan.imemodule.ui.fragment.theme.CustomThemeActivity
            instrumentation.waitForIdleSync()
            return result
        }
        fun drag(slider: com.google.android.material.slider.Slider, percent: Float) {
            val x = slider.trackSidePadding + slider.trackWidth * percent / 100f
            val time = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(time, time + 20, action, x, slider.height / 2f, 0)
                slider.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        try {
            for (file in listOf(cropped, source)) file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            files.saveThemeFiles(original)
            activity = open(original)
            var edited = original
            instrumentation.runOnMainSync {
                val views = descendants(activity!!.window.decorView).toList()
                val slider = views.filterIsInstance<com.google.android.material.slider.Slider>().first { it.tag == "theme_key_mask" }
                val preview = views.filterIsInstance<com.yuyan.imemodule.keyboard.KeyboardPreviewView>().first()
                val keyboard = descendants(preview).filterIsInstance<TextKeyboard>().first()
                val background = preview.background
                val filter = (background as android.graphics.drawable.BitmapDrawable).colorFilter
                assertEquals("Opening the editor must preserve an existing theme", original, themeField.get(activity))
                drag(slider, 0f)
                assertEquals(0, (themeField.get(activity) as com.yuyan.imemodule.data.theme.Theme.Custom).keyBackgroundOpacity)
                assertSame("Dragging mask strength must reuse the keyboard view", keyboard, descendants(preview).filterIsInstance<TextKeyboard>().first())
                assertSame("Mask adjustment must not recreate the photo", background, preview.background)
                assertSame("Mask adjustment must not change photo brightness", filter, (preview.background as android.graphics.drawable.BitmapDrawable).colorFilter)
                drag(slider, 75f)
                edited = themeField.get(activity) as com.yuyan.imemodule.data.theme.Theme.Custom
                assertEquals(75, edited.keyBackgroundOpacity)
                assertEquals(original.keyTextColor, edited.keyTextColor)
                val switch = views.filterIsInstance<com.google.android.material.materialswitch.MaterialSwitch>().first()
                switch.isChecked = !switch.isChecked
                edited = themeField.get(activity) as com.yuyan.imemodule.data.theme.Theme.Custom
                assertEquals("Changing key text variants must retain mask strength", 75, edited.keyBackgroundOpacity)
                activity!!.onBackPressedDispatcher.onBackPressed()
            }
            instrumentation.waitForIdleSync()
            assertEquals("Cancelling must leave the saved theme unchanged", original, files.listThemes().first { it.name == name })
            files.saveThemeFiles(edited)
            activity = open(files.listThemes().first { it.name == name })
            instrumentation.runOnMainSync {
                val slider = descendants(activity!!.window.decorView).filterIsInstance<com.google.android.material.slider.Slider>().first { it.tag == "theme_key_mask" }
                assertEquals("Saved mask strength must be restored in the editor", 75f, slider.value, 0.01f)
                slider.requestRectangleOnScreen(android.graphics.Rect(0, 0, slider.width, slider.height), true)
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                context.openFileOutput("tmp_key_mask_editor.png", android.content.Context.MODE_PRIVATE).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            bitmap.recycle()
            files.deleteThemeFiles(original)
        }
    }

    @Test
    fun floatingLandscapeHeightUsesTheSamePreferenceForReadingAndWriting() {
        instrumentation.runOnMainSync {
            val env = com.yuyan.imemodule.singleton.EnvironmentSingleton.instance
            val prefs = com.yuyan.imemodule.prefs.AppPrefs.getInstance().internal
            val savedLandscape = env.isLandscape
            val savedFloat = prefs.keyboardModeFloatLandscape.getValue()
            val savedHeight = prefs.keyboardHeightRatio.getValue()
            val savedLandscapeHeight = prefs.keyboardHeightRatioLandscape.getValue()
            try {
                env.isLandscape = true
                prefs.keyboardModeFloatLandscape.setValue(true)
                env.keyBoardHeightRatio = 0.42f
                assertEquals("Floating landscape uses the portrait height scale", 0.42f, prefs.keyboardHeightRatio.getValue(), 0.0001f)
                assertEquals("Floating resizing must preserve the docked landscape height", savedLandscapeHeight, prefs.keyboardHeightRatioLandscape.getValue(), 0.0001f)
            } finally {
                prefs.keyboardModeFloatLandscape.setValue(savedFloat)
                prefs.keyboardHeightRatio.setValue(savedHeight)
                prefs.keyboardHeightRatioLandscape.setValue(savedLandscapeHeight)
                env.isLandscape = savedLandscape
                env.initData()
            }
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
