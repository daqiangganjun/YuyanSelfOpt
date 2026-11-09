package com.yuyan

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuyan.imemodule.ui.activity.SettingsActivity
import com.yuyan.imemodule.ui.fragment.CloudSyncSettingsFragment
import com.yuyan.imemodule.ui.fragment.OtherSettingsFragment
import com.yuyan.imemodule.ui.fragment.TransferSettingsFragment
import com.yuyan.inputmethod.dictionary.DictionaryRules
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsOrganizationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    private fun preferences(group: PreferenceGroup): List<Preference> = (0 until group.preferenceCount).flatMap {
        val preference = group.getPreference(it)
        listOf(preference) + if (preference is PreferenceGroup) preferences(preference) else emptyList()
    }
    private fun views(view: android.view.View): Sequence<android.view.View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    @Test fun feedbackSwitchesPreserveStrengthAndSlidersNeverDisableEffects() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val feedback = com.yuyan.imemodule.prefs.AppPrefs.getInstance().internal
        val keys = listOf(feedback.soundOnKeyPress.key, feedback.vibrationAmplitude.key, feedback.soundEnabled.key, feedback.vibrationEnabled.key)
        val previous = keys.associateWith { prefs.all[it] }
        val previousIme = shell("settings get secure default_input_method")
        val previousEnabled = shell("settings get secure enabled_input_methods")
        val component = context.packageName + "/com.yuyan.imemodule.service.ImeService"
        var activity: SettingsActivity? = null
        try {
            shell("ime enable $component"); shell("settings put secure default_input_method $component")
            prefs.edit().remove(feedback.soundEnabled.key).remove(feedback.vibrationEnabled.key)
                .putInt(feedback.soundOnKeyPress.key, 7).putInt(feedback.vibrationAmplitude.key, 1).commit()
            assertFalse(feedback.isSoundEnabled)
            assertFalse(feedback.isVibrationEnabled)
            activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            val host = activity.supportFragmentManager.findFragmentById(com.yuyan.imemodule.R.id.nav_host_fragment) as NavHostFragment
            instrumentation.runOnMainSync { host.navController.navigate(com.yuyan.imemodule.R.id.keyboardFeedbackFragment) }
            instrumentation.waitForIdleSync()
            fun controls() = views(host.childFragmentManager.primaryNavigationFragment!!.requireView()).toList()
            instrumentation.runOnMainSync {
                val switches = controls().filterIsInstance<com.google.android.material.materialswitch.MaterialSwitch>()
                val sliders = controls().filterIsInstance<com.google.android.material.slider.Slider>()
                assertEquals(2, sliders.size)
                assertTrue(switches.all { !it.isChecked })
                assertTrue(sliders.all { !it.isEnabled && it.value == 0f })
                assertEquals(2, controls().filterIsInstance<android.widget.TextView>().count { it.text.toString() == "跟随系统" })
                switches.forEach { it.isChecked = true }
                assertTrue(feedback.isSoundEnabled && feedback.isVibrationEnabled)
                assertEquals(0, feedback.soundOnKeyPress.getValue())
                assertEquals(0, feedback.vibrationAmplitude.getValue())
                sliders[0].value = 6f
                sliders[0].requestFocus()
                assertTrue(sliders[0].dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)))
                sliders[1].requestFocus()
                assertTrue(sliders[1].dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)))
                assertEquals(7f, sliders[0].value, 0f)
                assertEquals(1f, sliders[1].value, 0f)
                assertEquals(17, feedback.soundOnKeyPress.getValue())
                assertEquals(2, feedback.vibrationAmplitude.getValue())
                assertTrue(sliders.all { it.isEnabled })
                switches.forEach { it.isChecked = false }
                assertEquals(17, feedback.soundOnKeyPress.getValue())
                assertEquals(2, feedback.vibrationAmplitude.getValue())
                assertFalse(feedback.isSoundEnabled || feedback.isVibrationEnabled)
                val labels = controls().filterIsInstance<android.widget.TextView>().filter { it.text.toString().startsWith("音量 ") || it.text.toString() == "较弱" }
                assertEquals(2, labels.size)
                assertTrue(labels.all { !it.isEnabled && it.alpha < 1f })
                host.navController.navigate(com.yuyan.imemodule.R.id.keyboardFeedbackFragment)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val switches = controls().filterIsInstance<com.google.android.material.materialswitch.MaterialSwitch>()
                val sliders = controls().filterIsInstance<com.google.android.material.slider.Slider>()
                assertTrue(switches.all { !it.isChecked })
                assertTrue(sliders.all { !it.isEnabled })
                assertEquals(7f, sliders[0].value, 0f)
                assertEquals(1f, sliders[1].value, 0f)
                switches.forEach { it.isChecked = true }
                assertEquals(17, feedback.soundOnKeyPress.getValue())
                assertEquals(2, feedback.vibrationAmplitude.getValue())
                sliders[0].value = 1f
                sliders[0].requestFocus()
                sliders[0].dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
                assertEquals(0, feedback.soundOnKeyPress.getValue())
                assertTrue(feedback.isSoundEnabled)
                assertTrue(sliders[0].isEnabled && switches[0].isChecked)
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            prefs.edit().apply { previous.forEach { (key, value) -> when (value) {
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                else -> remove(key)
            } } }.commit()
            shell("settings put secure enabled_input_methods $previousEnabled")
            shell("settings put secure default_input_method $previousIme")
        }
    }

    @Test fun systemHapticsRespectTheSwitchAndDoNotOverrideSystemSettings() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val feedback = com.yuyan.imemodule.prefs.AppPrefs.getInstance().internal
        val keys = listOf(feedback.vibrationAmplitude.key, feedback.vibrationEnabled.key)
        val previous = keys.associateWith { prefs.all[it] }
        try {
            instrumentation.runOnMainSync {
                var requests = 0
                var requestedFlags = -1
                val view = object : android.view.View(context) {
                    override fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean {
                        requests++
                        requestedFlags = flags
                        return true
                    }
                }
                prefs.edit().putInt(feedback.vibrationAmplitude.key, 0).putBoolean(feedback.vibrationEnabled.key, false).commit()
                com.yuyan.imemodule.prefs.InputFeedbacks.hapticFeedback(view)
                assertEquals(0, requests)
                feedback.vibrationEnabled.setValue(true)
                com.yuyan.imemodule.prefs.InputFeedbacks.hapticFeedback(view)
                assertEquals(1, requests)
                assertEquals(0, requestedFlags)
            }
        } finally {
            prefs.edit().apply { previous.forEach { (key, value) -> when (value) {
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                else -> remove(key)
            } } }.commit()
        }
    }
    private fun clickDialogText(text: String) {
        instrumentation.waitForIdleSync()
        val node = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(text).first { it.text?.toString() == text }
        assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    @Test fun dictionaryAndCloudPagesExposeOnlyRelevantControls() {
        val previousIme = shell("settings get secure default_input_method")
        val previousEnabled = shell("settings get secure enabled_input_methods")
        val previousRules = DictionaryRules.settings(context)
        val component = context.packageName + "/com.yuyan.imemodule.service.ImeService"
        var activity: SettingsActivity? = null
        try {
            shell("ime enable $component"); shell("settings put secure default_input_method $component")
            DictionaryRules.configure(context, previousRules.copy(mode = "builtin"))
            activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            instrumentation.waitForIdleSync()
            val host = activity.supportFragmentManager.findFragmentById(com.yuyan.imemodule.R.id.nav_host_fragment) as NavHostFragment
            instrumentation.runOnMainSync { host.navController.navigate(com.yuyan.imemodule.R.id.transferSettingsFragment) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dictionary = host.childFragmentManager.primaryNavigationFragment as TransferSettingsFragment
                assertFalse(dictionary.findPreference<Preference>("transfer_rule_network_ui")!!.isVisible)
                assertFalse(dictionary.findPreference<Preference>("transfer_rule_editor_ui")!!.isVisible)
                val mode = dictionary.findPreference<androidx.preference.ListPreference>("transfer_rule_mode_ui")!!
                assertTrue(mode.callChangeListener("custom"))
                mode.value = "custom"
                assertTrue(dictionary.findPreference<Preference>("transfer_rule_editor_ui")!!.isVisible)
                assertFalse(dictionary.findPreference<Preference>("transfer_rule_network_ui")!!.isVisible)
                assertTrue(mode.callChangeListener("network"))
                mode.value = "network"
                assertTrue(dictionary.findPreference<Preference>("transfer_rule_network_ui")!!.isVisible)
                assertTrue(dictionary.findPreference<Preference>("transfer_rule_fetch_ui")!!.isVisible)
                assertFalse(dictionary.findPreference<Preference>("transfer_rule_editor_ui")!!.isVisible)
                assertFalse(preferences(dictionary.preferenceScreen).any { it.title.toString().contains("服务器地址") })
                host.navController.navigate(com.yuyan.imemodule.R.id.cloudSyncSettingsFragment)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val cloud = host.childFragmentManager.primaryNavigationFragment as CloudSyncSettingsFragment
                val controls = preferences(cloud.preferenceScreen)
                assertEquals(1, controls.count { it.title == "参与同步的数据" })
                assertFalse(controls.any { it is androidx.preference.SwitchPreferenceCompat })
                assertTrue(controls.any { it.title == "服务器地址" })
                assertFalse(controls.any { it.title == "当前词库" })
                controls.single { it.title == "参与同步的数据" }.onPreferenceClickListener!!.onPreferenceClick(controls.single { it.title == "参与同步的数据" })
            }
            instrumentation.waitForIdleSync()
            val scopeDialog = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(scopeDialog.findAccessibilityNodeInfosByText("词库更新规则").isNotEmpty())
            clickDialogText("取消")
            instrumentation.runOnMainSync {
                host.navController.navigate(com.yuyan.imemodule.R.id.otherSettingsFragment)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val backup = host.childFragmentManager.primaryNavigationFragment as OtherSettingsFragment
                val titles = preferences(backup.preferenceScreen).map { it.title.toString() }
                assertTrue("Dictionary entry must be separate", "词库" in titles)
                assertTrue("Cloud entry must be separate", "云同步" in titles)
                assertFalse("词库与同步" in titles)
                val export = preferences(backup.preferenceScreen).single { it.title == "导出用户数据" }
                export.onPreferenceClickListener!!.onPreferenceClick(export)
            }
            instrumentation.waitForIdleSync()
            val exportDialog = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(exportDialog.findAccessibilityNodeInfosByText("选择导出数据").isNotEmpty())
            assertTrue(exportDialog.findAccessibilityNodeInfosByText("基础词库").isNotEmpty())
            clickDialogText("取消")
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            DictionaryRules.configure(context, previousRules)
            shell("settings put secure enabled_input_methods $previousEnabled")
            shell("settings put secure default_input_method $previousIme")
        }
    }
}
