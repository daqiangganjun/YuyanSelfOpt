package com.yuyan

import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuyan.imemodule.data.transfer.*
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.speech.SpeechCredentials
import com.yuyan.inputmethod.EngineRuntime
import com.yuyan.inputmethod.core.Rime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 由两台独立设备交替执行阶段，验证真实 WebDAV 上的离线修改、合并与删除传播。 */
@RunWith(AndroidJUnit4::class)
class SyncDeviceScenarioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val work get() = File(context.filesDir, "transfer-tests/scenario").apply { mkdirs() }
    private fun ready() {
        val deadline = SystemClock.elapsedRealtime() + 240000
        while (!EngineRuntime.isReady && SystemClock.elapsedRealtime() < deadline) {
            if (EngineRuntime.status.value?.phase == EngineRuntime.Phase.Failed) fail("Engine deployment failed")
            SystemClock.sleep(100)
        }
        assertTrue("Engine timeout", EngineRuntime.isReady)
        instrumentation.waitForIdleSync()
    }

    @Test fun executeStage() {
        org.junit.Assume.assumeTrue("Two-device stages must be invoked explicitly", args.containsKey("stage"))
        instrumentation.runOnMainSync { EngineRuntime.initialize(context) }
        ready()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val credentials = SpeechCredentials(context)
        val configFile = File(context.filesDir, "transfer-tests/webdav-test-secrets.json")
        when (args.getString("stage")) {
            "notice" -> {
                context.getSharedPreferences("transfer_state", android.content.Context.MODE_PRIVATE).edit()
                    .putString("update_details", "白霜 · 更新验证\n词库已更新，学习记录保持不变。").commit()
            }
            "initialize" -> {
                val config = SyncProtocol.json.decodeFromString<WebDavSettings>(configFile.readText())
                require(config.directory.startsWith("YuyanSelfOptTest/pair-")) { "Isolated test directory required" }
                if (!File(work, "original.zip").exists()) {
                    BackupArchive.export(context, File(work, "original.zip").outputStream(), System.currentTimeMillis())
                    credentials.write("webdav_test_previous", credentials.read("webdav").orEmpty())
                    WebDavSync.stateFile(context).takeIf { it.isFile }?.copyTo(File(work, "original-state.json"), overwrite = true)
                }
                WebDavSync.configure(context, config)
                WebDavSync.stateFile(context).delete()
                if (args.getString("seed") == "true") prefs.edit().putString("selfopt_device_sync_probe", "baseline").commit()
                WebDavSync.synchronize(context, ConflictPolicy.REMOTE)
                ready()
                assertEquals("baseline", prefs.getString("selfopt_device_sync_probe", ""))
            }
            "edit" -> {
                val device = args.getString("label")!!
                require(device in setOf("A", "B"))
                prefs.edit().putString("selfopt_device_sync_probe", "device-$device").commit()
                DataBaseKT.instance.phraseDao().insert(Phrase("设备同步回归$device", t9 = "", qwerty = "", lx17 = ""))
                val line = if (device == "A") "甲端回归词\tjia duan hui gui ci\t17\n" else "乙端回归词\tyi duan hui gui ci\t23\n"
                val input = File(work, "learning.tsv").apply { writeText(line) }
                EngineRuntime.maintenance(context) { shared, user ->
                    assertEquals(1, Rime.transferUserDictionary(shared.path, user.path, "rime_frost", input.path, true))
                }
                ready()
            }
            "sync" -> {
                WebDavSync.synchronize(context, ConflictPolicy.LATEST)
                ready()
                args.getString("expected")?.let { assertEquals(it, prefs.getString("selfopt_device_sync_probe", "")) }
                if (args.getString("checkMerged") == "true") {
                    val phrases = DataBaseKT.instance.phraseDao().getAll().map { it.content }
                    assertTrue("Local phrase lost", "设备同步回归A" in phrases)
                    assertTrue("Remote phrase lost", "设备同步回归B" in phrases)
                    val snapshot = EngineRuntime.maintenance(context) { shared, user -> LocalDataSnapshots.capture(context, shared, user, File(work, "verify")) }
                    ready()
                    for ((word, code, count) in listOf(Triple("甲端回归词", "jia duan hui gui ci", "17"), Triple("乙端回归词", "yi duan hui gui ci", "23"))) {
                        val key = android.util.Base64.encodeToString("$word\t$code".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                        assertEquals("Learning count changed after synchronization", count, snapshot.values["learn/rime_frost/$key"])
                    }
                }
                if (args.getString("checkDeleted") == "true") assertFalse(DataBaseKT.instance.phraseDao().getAll().any { it.content == "设备同步回归A" })
            }
            "delete" -> {
                DataBaseKT.instance.phraseDao().deleteByContent("设备同步回归A")
                WebDavSync.synchronize(context, ConflictPolicy.LATEST)
                ready()
            }
            "cleanup" -> {
                val snapshot = BackupArchive.read(File(work, "original.zip").inputStream(), File(work, "restore").apply { deleteRecursively() }).second
                BackupArchive.apply(context, snapshot)
                ready()
                credentials.write("webdav", credentials.read("webdav_test_previous").orEmpty())
                credentials.write("webdav_test_previous", "")
                val original = File(work, "original-state.json")
                if (original.exists()) TransferFiles.write(WebDavSync.stateFile(context), original.readText()) else WebDavSync.stateFile(context).delete()
                work.deleteRecursively()
                configFile.delete()
            }
            else -> error("Unknown test stage")
        }
    }
}
