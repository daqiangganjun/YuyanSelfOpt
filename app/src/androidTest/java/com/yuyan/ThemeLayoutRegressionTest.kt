package com.yuyan

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuyan.imemodule.data.theme.FocalBackgroundDrawable
import com.yuyan.imemodule.data.theme.ThemeControls
import com.yuyan.imemodule.data.theme.ThemeFilesManager
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemePreset
import com.yuyan.imemodule.data.transfer.TransferCoordinator
import com.yuyan.inputmethod.DictionaryStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeLayoutRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun tallerKeyboardRevealsMorePhotoWithoutStretching() {
        val bitmap = Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888)
        try {
            val drawable = FocalBackgroundDrawable(bitmap, 0.5f, 0.4f)
            fun visible(height: Float): RectF {
                val destination = RectF(0f, 0f, 500f, height)
                val matrix = drawable.imageMatrix(destination)
                val values = FloatArray(9).also(matrix::getValues)
                assertEquals("Photo proportions must remain unchanged", values[Matrix.MSCALE_X], values[Matrix.MSCALE_Y], 0.0001f)
                val inverse = Matrix()
                assertTrue(matrix.invert(inverse))
                return RectF(destination).also(inverse::mapRect)
            }
            val short = visible(300f)
            val tall = visible(500f)
            assertEquals(short.width(), tall.width(), 0.01f)
            assertEquals(short.centerY(), tall.centerY(), 0.01f)
            assertTrue(tall.top < short.top && tall.bottom > short.bottom)
            for (rotation in listOf(0, 90, 180, 270)) for (flip in listOf(false, true)) {
                val bounds = RectF(0f, 0f, 1000f, 2000f)
                FocalBackgroundDrawable(bitmap, 0f, 1f, rotation, flip).imageMatrix(RectF(0f, 0f, 600f, 400f)).mapRect(bounds)
                assertTrue("Clamped photo must cover every viewport edge", bounds.left <= 0.01f && bounds.top <= 0.01f && bounds.right >= 599.99f && bounds.bottom >= 399.99f)
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun samePhotoInBothSlotsStillTogglesNightModeAndNotifiesViews() {
        instrumentation.runOnMainSync {
            val prefs = ThemeManager.prefs
            val light = prefs.lightModeTheme.getValue()
            val dark = prefs.darkModeTheme.getValue()
            val normal = prefs.normalModeTheme.getValue()
            val follow = prefs.followSystemDayNightTheme.getValue()
            val manual = prefs.manualNightMode
            val (name, cropped, source) = ThemeFilesManager.newCustomBackgroundImages()
            val photo = ThemePreset.TransparentDark.deriveCustomBackground(name, cropped.path, source.path)
            var notifications = 0
            val listener = ThemeManager.OnThemeChangeListener { notifications++ }
            try {
                ThemeManager.saveTheme(photo)
                prefs.followSystemDayNightTheme.setValue(true)
                prefs.lightModeTheme.setValue(photo)
                prefs.darkModeTheme.setValue(photo)
                ThemeManager.init(Configuration(context.resources.configuration).apply { uiMode = Configuration.UI_MODE_NIGHT_NO })
                ThemeManager.addOnChangedListener(listener)
                assertFalse("Photo text palette must not determine system mode", ThemeManager.isNightMode)
                val parent = android.widget.FrameLayout(android.view.ContextThemeWrapper(context, com.yuyan.imemodule.R.style.Theme_AppTheme))
                val adapter = com.yuyan.imemodule.adapter.MenuAdapter(parent.context, mutableListOf(
                    com.yuyan.imemodule.data.menuSkbFunsPreset.getValue(com.yuyan.imemodule.prefs.behavior.SkbMenuMode.DarkTheme)))
                val holder = adapter.onCreateViewHolder(parent, 0)
                adapter.onBindViewHolder(holder, 0)
                assertFalse(holder.itemView.isSelected)
                repeat(4) { index ->
                    val before = notifications
                    ThemeManager.toggleNightMode()
                    assertEquals(index % 2 == 0, ThemeManager.isNightMode)
                    assertEquals(photo.name, ThemeManager.activeTheme.name)
                    assertTrue("Identical themes must still refresh selected buttons", notifications > before)
                    adapter.onBindViewHolder(holder, 0)
                    assertEquals("Rendered menu selection must follow mode", ThemeManager.isNightMode, holder.itemView.isSelected)
                    assertNotNull("Photo menu label must have a contrast surface", holder.entranceNameTextView!!.background)
                }
                ThemeManager.toggleNightMode()
                ThemeManager.onSystemDarkModeChange(false)
                assertTrue("Unchanged system mode must preserve manual toggle", ThemeManager.isNightMode)
                ThemeManager.onSystemDarkModeChange(true)
                ThemeManager.onSystemDarkModeChange(false)
                assertFalse(ThemeManager.isNightMode)
                prefs.followSystemDayNightTheme.setValue(false)
                ThemeManager.setNormalModeTheme(photo)
                ThemeManager.toggleNightMode()
                val mode = ThemeManager.isNightMode
                ThemeManager.init(context.resources.configuration)
                assertEquals("Manual mode must survive manager initialization", mode, ThemeManager.isNightMode)
            } finally {
                ThemeManager.removeOnChangedListener(listener)
                prefs.lightModeTheme.setValue(light)
                prefs.darkModeTheme.setValue(dark)
                ThemeManager.setNormalModeTheme(normal)
                prefs.manualNightMode = manual
                prefs.followSystemDayNightTheme.setValue(follow)
                ThemeManager.deleteTheme(name)
                ThemeManager.init(context.resources.configuration)
            }
        }
    }

    @Test
    fun photoButtonsFollowKeyMaskAndBubbleSamplesCurrentViewport() {
        val original = ThemePreset.TransparentDark.deriveCustomBackground("contrast", "missing", "missing")
        for (strength in listOf(0, 30, 100)) {
            val theme = original.withKeyBackgroundOpacity(strength)
            assertEquals(theme.keyBackgroundColor, ThemeControls.surface(theme, false))
            assertEquals(theme.accentKeyBackgroundColor, ThemeControls.surface(theme, true))
            assertEquals(theme.keyTextColor, ThemeControls.foreground(theme, false))
        }
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()
            paint.color = Color.RED; canvas.drawRect(0f, 0f, 200f, 200f, paint)
            paint.color = Color.BLUE; canvas.drawRect(0f, 200f, 200f, 400f, paint)
            val view = android.view.View(context).apply { background = FocalBackgroundDrawable(bitmap, 0.5f, 0.7f) }
            view.layout(0, 0, 200, 100)
            val small = com.yuyan.imemodule.data.theme.ComposingBackgroundSampler.sample(view, Color.WHITE, Color.BLACK)
            view.layout(0, 0, 200, 400)
            val tall = com.yuyan.imemodule.data.theme.ComposingBackgroundSampler.sample(view, Color.WHITE, Color.BLACK)
            assertNotEquals("Sampling must track the actual image crop after resizing", small, tall)
            for (color in listOf(small, tall)) assertTrue(ColorUtils.calculateContrast(Color.WHITE, color) >= 4.5)
            bitmap.recycle()
        }
    }
    @Test
    fun cropEditorKeepsToolbarVisibleAndRestoresPhotoOrientation() {
        val source = java.io.File(context.cacheDir, "focal-crop-regression.png")
        val bitmap = Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        repeat(10) { row ->
            paint.color = Color.HSVToColor(floatArrayOf(row * 36f, 0.5f, 0.8f))
            canvas.drawRect(0f, row * 100f, 600f, (row + 1) * 100f, paint)
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val options = com.yuyan.imemodule.libs.cropper.CropImageOptions(
            activityTitle = "选择背景中心点", cropMenuCropButtonTitle = "确定中心",
            initialRotation = 90, flipHorizontally = true, autoZoomEnabled = false,
            fixAspectRatio = true, aspectRatioX = 600, aspectRatioY = 350,
            toolbarColor = context.getColor(com.yuyan.imemodule.R.color.settings_background),
            activityMenuIconColor = context.getColor(com.yuyan.imemodule.R.color.settings_text),
            activityMenuTextColor = context.getColor(com.yuyan.imemodule.R.color.settings_text),
            toolbarTitleColor = context.getColor(com.yuyan.imemodule.R.color.settings_text),
            toolbarBackButtonColor = context.getColor(com.yuyan.imemodule.R.color.settings_text),
        )
        val intent = com.yuyan.imemodule.libs.cropper.CropImageContract().createIntent(context,
            com.yuyan.imemodule.libs.cropper.CropImageContractOptions(android.net.Uri.fromFile(source), options))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            val crop = activity.findViewById<com.yuyan.imemodule.libs.cropper.CropImageView>(com.yuyan.imemodule.R.id.cropImageView)
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            var ready = false
            while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = crop.cropRect != null && crop.rotatedDegrees == 90 }
                if (!ready) Thread.sleep(100)
            }
            assertTrue("Crop editor must load the source and saved rotation", ready)
            instrumentation.runOnMainSync {
                val toolbar = activity.findViewById<android.view.View>(com.yuyan.imemodule.R.id.crop_toolbar)
                assertTrue(toolbar.isShown && toolbar.height > 0)
                assertTrue("Crop view must start below its toolbar", crop.top >= toolbar.bottom)
                val position = IntArray(2).also(toolbar::getLocationOnScreen)
                val insets = toolbar.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
                assertTrue("Toolbar must clear the status bar", position[1] >= insets.top)
                assertTrue("Saved flip must survive asynchronous image loading", crop.flippedHorizontally)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                context.openFileOutput("tmp_focal_crop.png", android.content.Context.MODE_PRIVATE).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            source.delete()
        }
    }

    @Test
    fun missingDictionarySourceDoesNotQueueNetworkWork() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val previous = preferences.getString("transfer_dictionary_index", null)
        val previousRule = com.yuyan.inputmethod.dictionary.DictionaryRules.settings(context)
        com.yuyan.inputmethod.dictionary.DictionaryRules.configure(context, previousRule.copy(mode = "package", packageIndex = ""))
        val state = context.getSharedPreferences("transfer_state", android.content.Context.MODE_PRIVATE)
        val beforeAttempt = state.getLong("update_attempt", 0)
        val beforeRequest = state.getBoolean("update_requested", false)
        val migrated = state.getBoolean("dictionary_source_migrated", false)
        try {
            preferences.edit().putString("transfer_dictionary_index", "").commit()
            TransferCoordinator.requestUpdate(context, ice = true)
            assertEquals(beforeAttempt, state.getLong("update_attempt", 0))
            assertEquals(beforeRequest, state.getBoolean("update_requested", false))
            assertTrue(TransferCoordinator.lastStatus(context).contains("尚未配置"))
            state.edit().putBoolean("dictionary_source_migrated", false).commit()
            preferences.edit().putString("transfer_dictionary_index", "https://github.com/daqiangganjun/YuyanSelfOpt/releases/download/dictionaries-v1/index.json").commit()
            assertEquals("", DictionaryStore.configuredIndex(context))
            preferences.edit().putString("transfer_dictionary_index", "https://github.com/daqiangganjun/YuyanSelfOpt/releases/download/dictionaries-v1/index.json").commit()
            assertTrue("Explicitly configured sources must survive after migration", DictionaryStore.configuredIndex(context).isNotBlank())
            preferences.edit().putString("transfer_dictionary_index", "https://example.org/index.json").commit()
            assertEquals("https://example.org/index.json", DictionaryStore.configuredIndex(context))
        } finally {
            com.yuyan.inputmethod.dictionary.DictionaryRules.configure(context, previousRule)
            preferences.edit().putString("transfer_dictionary_index", previous).commit()
            state.edit().putBoolean("dictionary_source_migrated", migrated).commit()
        }
    }
}
