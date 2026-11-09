package com.yuyan

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuyan.imemodule.data.transfer.SyncScope
import com.yuyan.imemodule.data.transfer.SyncProtocol
import com.yuyan.imemodule.data.transfer.TransferFiles
import com.yuyan.inputmethod.DictionaryPackage
import com.yuyan.inputmethod.DictionaryStore
import com.yuyan.inputmethod.EngineRuntime
import com.yuyan.inputmethod.core.Rime
import com.yuyan.inputmethod.dictionary.*
import com.yuyan.inputmethod.util.FuzzyPinYinUtils
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MobileDictionaryIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun ready() {
        EngineRuntime.initialize(context)
        val deadline = SystemClock.uptimeMillis() + 180_000
        while (!EngineRuntime.isReady && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Baseline input engine must be ready", EngineRuntime.isReady)
    }

    @Test fun ruleImportAndCloudScopeAreIndependent() {
        val previous = DictionaryRules.records(context)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val enabled = SyncScope.DICTIONARY_RULES.enabled(context)
        try {
            val rule = DictionaryRules.builtins.first().copy(id = "test_frost", name = "测试规则")
            DictionaryRules.import(context, SyncProtocol.json.encodeToString(rule))
            DictionaryRules.configure(context, DictionaryRules.settings(context).copy(mode = "custom"))
            assertEquals(rule.id, DictionaryRules.selected(context, "frost").id)
            val records = DictionaryRules.records(context)
            records.forEach { (key, value) -> DictionaryRules.validateRecord(key, value) }
            prefs.edit().putBoolean(SyncScope.DICTIONARY_RULES.preferenceKey, true).commit()
            assertTrue(SyncScope.includes(context, "rule/test_frost"))
            prefs.edit().putBoolean(SyncScope.DICTIONARY_RULES.preferenceKey, false).commit()
            assertFalse(SyncScope.includes(context, "rule/test_frost"))
            assertTrue(runCatching { DictionaryRules.parse(SyncProtocol.json.encodeToString(rule.copy(repository = "../invalid"))) }.isFailure)
            DictionaryRules.restore(context, records)
            assertEquals(rule.id, DictionaryRules.selected(context, "frost").id)
        } finally {
            DictionaryRules.restore(context, previous)
            prefs.edit().putBoolean(SyncScope.DICTIONARY_RULES.preferenceKey, enabled).commit()
        }
    }

    @Test fun directIceSourceBuildsAndActivatesOnlyAfterTests() = sourceBuild("ice")
    @Test fun directFrostSourceBuildsAndActivatesOnlyAfterTests() = sourceBuild("frost")

    @Test fun disabledRuleScopeDoesNotApplyRemoteRules() {
        ready()
        val previous = DictionaryRules.records(context)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val enabled = SyncScope.DICTIONARY_RULES.enabled(context)
        val work = File(context.cacheDir, "rule-scope-validation").apply { mkdirs() }
        try {
            val snapshot = EngineRuntime.maintenance(context) { shared, user ->
                com.yuyan.imemodule.data.transfer.LocalDataSnapshots.capture(context, shared, user, work, forSync = true)
            }
            val remote = DictionaryRules.builtins.first().copy(id = "remote_rule", name = "远端规则", updatedAt = System.currentTimeMillis())
            val changed = snapshot.values + ("rule/${remote.id}" to SyncProtocol.json.encodeToString(remote))
            val incoming = snapshot.copy(values = changed)
            prefs.edit().putBoolean(SyncScope.DICTIONARY_RULES.preferenceKey, false).commit()
            com.yuyan.imemodule.data.transfer.BackupArchive.apply(context, incoming, forSync = true)
            assertEquals(previous, DictionaryRules.records(context))
            prefs.edit().putBoolean(SyncScope.DICTIONARY_RULES.preferenceKey, true).commit()
            com.yuyan.imemodule.data.transfer.BackupArchive.apply(context, incoming, forSync = true)
            assertTrue(DictionaryRules.all(context).any { it.id == remote.id })
        } finally {
            DictionaryRules.restore(context, previous)
            prefs.edit().putBoolean(SyncScope.DICTIONARY_RULES.preferenceKey, enabled).commit()
            work.deleteRecursively()
        }
    }

    @Test fun cancellingNativeBuildKeepsCurrentDictionaryUsable() {
        ready()
        val activity = instrumentation.startActivitySync(Intent(context, ImeTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val previous = DictionaryStore.active(context)
        previous.values.forEach { DictionaryStore.materialize(context, it) }
        try {
            var cancelled = false
            val result = runCatching {
                DictionaryBuildClient.run(context, DictionaryBuildRequest("frost", previous, fuzzyRules = FuzzyPinYinUtils.algebraRules())) { message ->
                    if (!cancelled && message.startsWith("正在构建")) {
                        cancelled = true
                        com.yuyan.imemodule.data.transfer.TransferCoordinator.cancelUpdate(context)
                    }
                }
            }
            assertTrue(cancelled)
            assertTrue(result.exceptionOrNull() is DictionaryBuildException)
            assertEquals(previous, DictionaryStore.active(context))
            assertTrue(EngineRuntime.isReady)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun sourceBuild(family: String) {
        assumeTrue("Live source testing requires explicit runner argument", InstrumentationRegistry.getArguments().getString("liveDictionarySource") == "true")
        ready()
        val activity = instrumentation.startActivitySync(Intent(context, ImeTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val previous = DictionaryStore.active(context)
        previous.values.forEach { DictionaryStore.materialize(context, it) }
        val rule = DictionaryRules.builtins.single { it.family == family }
        try {
            val request = DictionaryBuildRequest(family, previous, rule, FuzzyPinYinUtils.algebraRules(), "integration-source-progress")
            val totals = mutableListOf<Int>()
            val result = DictionaryBuildClient.run(context, request) {
                android.util.Log.i("DictionaryIntegration", it)
                Regex("文件总进度：(\\d+)%").find(it)?.groupValues?.get(1)?.toInt()?.let(totals::add)
                if (it.startsWith("文件总进度")) {
                    val file = Regex("当前文件：.* (\\d+)%").find(it)?.groupValues?.get(1)?.toInt()
                    assertTrue("Current file percentage must be valid", file != null && file in 0..100)
                }
            }
            assertTrue("Live source must report total transfer progress", totals.isNotEmpty())
            assertTrue("Total transfer progress must not regress", totals.zipWithNext().all { (a, b) -> b >= a })
            assertTrue(totals.all { it in 0..100 })
            assertEquals("ready", result.result.state)
            assertEquals(previous, DictionaryStore.active(context))
            assertTrue("Existing engine must remain usable throughout isolated build", EngineRuntime.isReady)
            val packages = result.result.packages
            assertEquals(listOf(family), packages.map { it.family })
            val validated = SyncProtocol.json.decodeFromString<Map<String, DictionaryPackage>>(File(result.work, "validated.json").readText())
            val other = if (family == "frost") "ice" else "frost"
            assertEquals("Unselected dictionary must retain its version", previous[other], validated[other])
            EngineRuntime.activatePackages(context, validated, result.work)
            assertTrue(EngineRuntime.isReady)
            assertEquals(packages.single().sha256, DictionaryStore.active(context).getValue(family).sha256)
            assertTrue(Rime.selectRimeSchema("selfopt_${family}_pinyin"))
            Rime.clearRimeComposition(); "nihao".forEach { Rime.processRimeKey(it.code, 0) }
            assertTrue(Rime.getRimeContext()!!.candidates.any { it.text == "你好" })
            Rime.clearRimeComposition()
            File(context.filesDir, "mobile-$family-result.json").writeText(SyncProtocol.json.encodeToString(packages.single()))
        } finally {
            EngineRuntime.activatePackages(context, previous)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun badBuildNeverActivatesAndDefaultRecoveryWorksWithBrokenManifest() {
        ready()
        val activity = instrumentation.startActivitySync(Intent(context, ImeTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val previous = DictionaryStore.active(context)
        val baseline = DictionaryStore.builtin(context)
        val original = DictionaryStore.materialize(context, baseline)
        val broken = File(context.cacheDir, "broken-mobile-dictionary.zip")
        try {
            java.util.zip.ZipFile(original).use { zip -> java.util.zip.ZipOutputStream(broken.outputStream()).use { output ->
                zip.entries().asSequence().forEach { entry ->
                    output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    if (entry.name == "rime_frost.dict.yaml") output.write("name: rime_frost\nversion: broken\nimport_tables:\n- missing_dictionary\n...\n".toByteArray())
                    else zip.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            } }
            val pkg = baseline.copy(sha256 = TransferFiles.sha256(broken), size = broken.length())
            val stored = DictionaryStore.packageFile(context, pkg).apply { parentFile!!.mkdirs() }
            broken.copyTo(stored, overwrite = true)
            val request = DictionaryBuildRequest("frost", previous + ("frost" to pkg), fuzzyRules = FuzzyPinYinUtils.algebraRules())
            val failure = runCatching { DictionaryBuildClient.run(context, request) {} }.exceptionOrNull()
            assertNotNull("Malformed dictionary must fail isolated build or tests", failure)
            assertEquals(previous, DictionaryStore.active(context))
            val marker = File(DictionaryStore.root(context), "user/recovery-preserved.txt").apply { parentFile!!.mkdirs(); writeText("preserve-user-data") }
            val state = File(DictionaryStore.root(context), "packages/active.json")
            TransferFiles.write(state, "damaged manifest")
            EngineRuntime.restoreBundled(context)
            assertTrue(EngineRuntime.isReady)
            assertEquals(setOf("frost"), DictionaryStore.active(context).keys)
            assertEquals(baseline.sha256, DictionaryStore.active(context).getValue("frost").sha256)
            assertEquals("builtin", DictionaryRules.settings(context).mode)
            assertEquals("preserve-user-data", marker.readText())
            marker.delete(); stored.delete()
        } finally {
            broken.delete()
            if (!EngineRuntime.isReady) EngineRuntime.restoreBundled(context)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
