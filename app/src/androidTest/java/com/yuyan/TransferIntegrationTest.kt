package com.yuyan

import android.os.SystemClock
import android.util.Base64
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuyan.imemodule.data.transfer.*
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.speech.SpeechCredentials
import com.yuyan.inputmethod.DictionaryIndex
import com.yuyan.inputmethod.DictionaryStore
import com.yuyan.inputmethod.EngineRuntime
import com.yuyan.inputmethod.core.Rime
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TransferIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val testRoot get() = File(context.filesDir, "transfer-tests").apply { mkdirs() }
    private val json get() = SyncProtocol.json

    private fun ready() {
        val deadline = SystemClock.elapsedRealtime() + 240000
        while (!EngineRuntime.isReady && SystemClock.elapsedRealtime() < deadline) {
            if (EngineRuntime.status.value?.phase == EngineRuntime.Phase.Failed) fail("Engine failed: ${Rime.getLastError()}")
            SystemClock.sleep(100)
        }
        assertTrue("Engine timeout", EngineRuntime.isReady)
        instrumentation.waitForIdleSync()
    }

    @Before fun initialize() {
        instrumentation.runOnMainSync { EngineRuntime.initialize(context) }
        ready()
    }

    private fun snapshot(name: String = "capture"): LocalDataSnapshot = EngineRuntime.maintenance(context) { shared, user ->
        LocalDataSnapshots.capture(context, shared, user, File(testRoot, name).apply { mkdirs() })
    }.also { ready() }

    @Test fun a_builtinContainsOnlyFrost() {
        val entries = mutableSetOf<String>()
        java.util.zip.ZipInputStream(context.assets.open("rime-selfopt.zip")).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries += entry.name; zip.closeEntry() }
        }
        assertTrue("Missing base dictionary", "rime_frost.dict.yaml" in entries)
        assertFalse("Optional dictionary must not be bundled", entries.any { it.startsWith("ice_") || it.contains("selfopt_ice_") || it == "rime_ice.dict.yaml" })
        assertEquals("frost", DictionaryStore.builtin(context).family)
    }

    @Test fun l_partialBackupRestoresOnlySelectedCategories() {
        val previous = snapshot("partial-before")
        val probe = "partial_backup_probe"
        val archive = File(testRoot, "settings-only.zip")
        try {
            prefs.edit().putString(probe, "exported").commit()
            val selected = snapshot("partial-export").select(setOf(SyncScope.SETTINGS))
            assertTrue(selected.blobs.isEmpty())
            assertTrue(selected.values.keys.all { it.startsWith("pref/") })
            BackupArchive.write(context, archive.outputStream(), selected)
            val (metadata, read) = BackupArchive.read(archive.inputStream(), File(testRoot, "partial-read").apply { deleteRecursively() })
            assertEquals(3, metadata.format)
            assertEquals(setOf("settings"), metadata.scopes)
            val preview = com.yuyan.imemodule.manager.UserDataManager.preview(archive.inputStream())
            assertEquals(setOf("settings"), preview.scopes)
            prefs.edit().putString(probe, "local-change").commit()
            val excluded = snapshot("partial-excluded").values.filterKeys { !it.startsWith("pref/") }
            BackupArchive.apply(context, read)
            assertEquals("exported", prefs.getString(probe, null))
            assertEquals("Excluded categories must remain untouched", excluded, snapshot("partial-after").values.filterKeys { !it.startsWith("pref/") })
            val full = previous.select(SyncScope.entries.toSet())
            assertNull(full.scopes)
            val bytes = ByteArrayOutputStream()
            BackupArchive.write(context, bytes, full)
            assertEquals(2, BackupArchive.read(bytes.toByteArray().inputStream(), File(testRoot, "full-compatible").apply { deleteRecursively() }).first.format)
        } finally { BackupArchive.apply(context, previous); ready() }
    }

    @Test fun m_partialBackupRejectsChangedCategoryMetadata() {
        val selected = snapshot("category-check").select(setOf(SyncScope.DICTIONARY_RULES))
        val original = ByteArrayOutputStream()
        BackupArchive.write(context, original, selected)
        val changed = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(original.toByteArray().inputStream()).use { input ->
            ZipOutputStream(changed).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    output.putNextEntry(ZipEntry(entry.name))
                    val content = input.readBytes()
                    if (entry.name == "metadata.json") {
                        val metadata = JSONObject(content.toString(Charsets.UTF_8))
                        metadata.put("scopes", org.json.JSONArray().put("phrases"))
                        output.write(metadata.toString().toByteArray())
                    } else output.write(content)
                    output.closeEntry()
                }
            }
        }
        assertTrue("Category metadata must match checksummed payload",
            runCatching { BackupArchive.read(changed.toByteArray().inputStream(), File(testRoot, "bad-categories").apply { deleteRecursively() }) }.isFailure)
    }

    @Test fun b_causalMergeDeletionPoliciesAndCalendar() {
        val base = SyncProtocol.capture(SyncDocument(), mapOf("common" to "base", "deleted" to "exists"), "a", 100)
        val left = SyncProtocol.capture(base, mapOf("common" to "left", "local" to "new"), "a", 200)
        val right = SyncProtocol.capture(base, mapOf("common" to "right", "deleted" to "exists", "remote" to "new"), "b", 300)
        val merged = SyncProtocol.merge(left, right, ConflictPolicy.LATEST).document
        assertEquals("right", merged.records.getValue("common").value)
        assertNull(merged.records.getValue("deleted").value)
        assertEquals("new", merged.records.getValue("local").value)
        assertEquals("new", merged.records.getValue("remote").value)
        assertEquals(merged, SyncProtocol.merge(merged, right, ConflictPolicy.LATEST).document)
        assertEquals("left", SyncProtocol.merge(left, right, ConflictPolicy.LOCAL).document.records.getValue("common").value)
        assertEquals("right", SyncProtocol.merge(left, right, ConflictPolicy.REMOTE).document.records.getValue("common").value)
        assertEquals(listOf("common"), SyncProtocol.merge(left, right, ConflictPolicy.ASK).conflicts)
        val defaults = SyncProtocol.capture(SyncDocument(), mapOf("setting" to "default"), "new-device", 1000, mapOf("setting" to 0L))
        val customized = SyncProtocol.capture(SyncDocument(), mapOf("setting" to "custom"), "existing-device", 500)
        assertEquals("custom", SyncProtocol.merge(defaults, customized, ConflictPolicy.LATEST).document.records.getValue("setting").value)
        val monday = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 2, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        assertNull(SyncProtocol.period("off", monday))
        assertEquals(SyncProtocol.period("weekly", monday), SyncProtocol.period("weekly", monday + 5 * 86400000L))
        assertNotEquals(SyncProtocol.period("weekly", monday), SyncProtocol.period("weekly", monday + 7 * 86400000L))
        assertNotEquals(SyncProtocol.period("daily", monday), SyncProtocol.period("daily", monday + 86400000L))
        assertNotEquals(SyncProtocol.period("monthly", monday), SyncProtocol.period("monthly", monday + 31 * 86400000L))
    }

    @Test fun c_archiveRejectsTraversalAndDuplicateEntries() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry("../outside.txt")); zip.write(1); zip.closeEntry() }
        val root = File(testRoot, "malformed")
        assertTrue(runCatching { TransferFiles.extract(ByteArrayInputStream(bytes.toByteArray()), root) }.isFailure)
        assertFalse(File(testRoot, "outside.txt").exists())
    }

    @Test fun d_backupLearningAndRestoreAreRepeatable() {
        val before = snapshot("backup-before")
        try {
            val text = File(testRoot, "learning.tsv").apply { writeText("自测同步词\tzi ce tong bu ci\t17\n") }
            EngineRuntime.maintenance(context) { shared, user ->
                assertEquals(1, Rime.transferUserDictionary(shared.path, user.path, "rime_frost", text.path, true))
            }
            ready()
            prefs.edit().putString("selfopt_transfer_probe", "before").commit()
            DataBaseKT.instance.phraseDao().insert(Phrase("同步回归常用语", t9 = "", qwerty = "", lx17 = ""))
            val captured = snapshot("backup-capture")
            assertTrue(captured.values.keys.any { it.startsWith("learn/rime_frost/") })
            assertTrue("dictionary/frost" in captured.values)
            assertFalse("dictionary/ice" in captured.values)
            val archive = File(testRoot, "roundtrip.zip")
            BackupArchive.write(context, archive.outputStream(), captured)
            val read = BackupArchive.read(archive.inputStream(), File(testRoot, "roundtrip-read").apply { deleteRecursively() }).second
            assertEquals(captured.values, read.values)
            prefs.edit().putString("selfopt_transfer_probe", "after").commit()
            DataBaseKT.instance.phraseDao().deleteByContent("同步回归常用语")
            BackupArchive.apply(context, read)
            ready()
            assertEquals("before", prefs.getString("selfopt_transfer_probe", ""))
            assertTrue(DataBaseKT.instance.phraseDao().getAll().any { it.content == "同步回归常用语" })
            val once = snapshot("after-once").values.filterKeys { it.startsWith("learn/") }
            BackupArchive.apply(context, read)
            ready()
            assertEquals(once, snapshot("after-twice").values.filterKeys { it.startsWith("learn/") })
            val blob = read.blobs.values.first()
            val damaged = File(testRoot, "damaged-blob").apply { writeText("corrupted") }
            assertTrue(runCatching { LocalDataSnapshots.validate(read.values, read.blobs.mapValues { if (it.value == blob) damaged else it.value }) }.isFailure)
        } finally { BackupArchive.apply(context, before); ready() }
    }

    @Test fun e_optionalIceDownloadValidationAndActivation() {
        val indexFile = File(testRoot, "index.json")
        if (InstrumentationRegistry.getArguments().getString("requireTransferFixtures") != "true") org.junit.Assume.assumeTrue("Optional dictionary fixture is not provisioned", indexFile.isFile)
        assertTrue("Dictionary fixtures were not installed", indexFile.isFile)
        val fixture = json.decodeFromString<DictionaryIndex>(indexFile.readText())
        val ice = fixture.packages.single { it.family == "ice" }
        val file = File(testRoot, "ice.zip")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (chain.request().url.encodedPath.endsWith("index.json")) indexFile.readBytes() else file.readBytes()).toResponseBody()).build()
        }.build()
        val active = DictionaryStore.active(context)
        try {
            assertEquals(2, DictionaryStore.fetchIndex(context, "https://fixture.invalid/index.json", http).packages.size)
            val archive = DictionaryStore.download(context, ice, http)
            assertEquals(ice.sha256, TransferFiles.sha256(archive))
            assertEquals("Download alone must not activate data", "ice" in active, DictionaryStore.hasIce(context))
            val imported = DictionaryStore.importPackage(context, file.inputStream())
            assertEquals(ice.sha256, imported.sha256)
            TransferCoordinator.installLocalPackage(context, imported)
            val deadline = SystemClock.elapsedRealtime() + 240000
            while ((!DictionaryStore.hasIce(context) || !EngineRuntime.isReady || TransferCoordinator.isRunning()) && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(100)
            }
            instrumentation.waitForIdleSync()
            assertTrue("Local dictionary activation timed out", DictionaryStore.hasIce(context))
            ready()
            prefs.edit().putString("rime_dictionary_family", "ice").commit()
            instrumentation.runOnMainSync { EngineRuntime.prepare(context) }
            ready()
            assertTrue(DictionaryStore.hasIce(context))
            assertTrue(Rime.selectSchema("pinyin"))
            Rime.clearComposition()
            "nihao".forEach { Rime.processKey(it.code, 0) }
            assertTrue(Rime.candidates.any { it.text == "你好" })
            Rime.clearComposition()
        } finally {
            prefs.edit().putString("rime_dictionary_family", "frost").commit()
            EngineRuntime.activatePackages(context, active)
            ready()
            TransferCoordinator.acknowledgeDetails(context)
        }
    }

    @Test fun f_liveWebDavTwoDeviceMergeAndConditionalWrites() {
        val configFile = File(testRoot, "webdav-test-secrets.json")
        if (InstrumentationRegistry.getArguments().getString("requireWebDavTests") != "true") org.junit.Assume.assumeTrue("Live WebDAV configuration is not provisioned", configFile.isFile)
        assertTrue("WebDAV test credentials were not provided", configFile.isFile)
        val config = json.decodeFromString<WebDavSettings>(configFile.readText())
        require(config.directory.startsWith("YuyanSelfOptTest/run-")) { "Refusing to test outside the isolated remote directory" }
        val credentialStore = SpeechCredentials(context)
        val oldConfiguration = credentialStore.read("webdav").orEmpty()
        val oldSync = WebDavSync.stateFile(context).takeIf { it.isFile }?.readText()
        val before = snapshot("sync-before")
        val hadIce = DictionaryStore.hasIce(context)
        val client = WebDavClient(config.url, config.username, config.password)
        val remote = config.directory
        val oldScopes = SyncScope.entries.associateWith { prefs.contains(it.preferenceKey) to prefs.getBoolean(it.preferenceKey, it.enabledByDefault) }
        prefs.edit().also { editor -> SyncScope.entries.forEach { editor.putBoolean(it.preferenceKey, it.enabledByDefault) } }.commit()
        var failure: Throwable? = null
        try {
            client.testConnection()
            WebDavSync.configure(context, config)
            WebDavSync.stateFile(context).delete()
            prefs.edit().putString("selfopt_transfer_probe", "device-a").commit()
            WebDavSync.synchronize(context, ConflictPolicy.LATEST)
            ready()
            val settingsPath = "$remote/categories/settings.json"
            val head = client.read(settingsPath)!!
            assertFalse("Server must return a version token", head.etag.isNullOrBlank())
            val first = json.decodeFromString<SyncDocument>(head.text)
            val key = "pref/" + Base64.encodeToString("selfopt_transfer_probe".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val remoteValues = first.records.filterValues { it.value != null }.mapValues { it.value.value!! }.toMutableMap()
            remoteValues[key] = JSONObject().put("type", "string").put("value", "device-b").toString()
            val second = SyncProtocol.capture(first, remoteValues, "test-device-b", System.currentTimeMillis())
            client.write(settingsPath, json.encodeToString(second), head)
            assertTrue("Stale writes must be rejected", runCatching { client.write(settingsPath, head.text, head) }.exceptionOrNull() is WebDavClient.Conflict)
            WebDavSync.synchronize(context, ConflictPolicy.LATEST)
            ready()
            assertEquals("device-b", prefs.getString("selfopt_transfer_probe", ""))
            val learning = snapshot("sync-learning-once").values.filterKeys { it.startsWith("learn/") }
            WebDavSync.synchronize(context, ConflictPolicy.LATEST)
            ready()
            assertEquals(learning, snapshot("sync-learning-twice").values.filterKeys { it.startsWith("learn/") })
            assertEquals("Synchronization must not install an unselected optional dictionary", hadIce, DictionaryStore.hasIce(context))
            for (scope in SyncScope.entries) {
                val beforeScope = snapshot("scope-before-${scope.id}").values.filterKeys(scope::matches)
                prefs.edit().putBoolean(scope.preferenceKey, false).commit()
                val path = "$remote/categories/${scope.id}.json"
                val original = client.read(path)
                client.write(path, "disabled-category-must-not-be-read", original)
                val changeScope = if (scope == SyncScope.SETTINGS) SyncScope.PHRASES else SyncScope.SETTINGS
                val changePath = "$remote/categories/${changeScope.id}.json"
                val changeHead = client.read(changePath)!!
                val changeDoc = json.decodeFromString<SyncDocument>(changeHead.text)
                val changeValues = changeDoc.records.filterValues { it.value != null }.mapValues { it.value.value!! }.toMutableMap()
                if (changeScope == SyncScope.SETTINGS) {
                    val probe = Base64.encodeToString("selfopt_scope_probe".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                    changeValues["pref/$probe"] = JSONObject().put("type", "string").put("value", scope.id).toString()
                } else {
                    val content = "范围隔离回归"
                    val recordKey = content.toByteArray().joinToString("") { "%02X".format(it.toInt() and 255) }
                    changeValues["row/phrase/$recordKey"] = JSONObject().put("content", content).put("isKeep", 0)
                        .put("t9", "").put("qwerty", "").put("lx17", "").put("time", System.currentTimeMillis()).toString()
                }
                client.write(changePath, json.encodeToString(SyncProtocol.capture(changeDoc, changeValues, "scope-fixture", System.currentTimeMillis())), changeHead)
                WebDavSync.synchronize(context, ConflictPolicy.LATEST)
                ready()
                assertTrue("Disabled category changed local data: ${scope.id}", beforeScope == snapshot("scope-after-${scope.id}").values.filterKeys(scope::matches))
                val unchanged = client.read(path)!!
                assertTrue("Disabled category was uploaded: ${scope.id}", unchanged.text == "disabled-category-must-not-be-read")
                if (original == null) client.delete(path) else client.write(path, original.text, unchanged)
                prefs.edit().putBoolean(scope.preferenceKey, oldScopes.getValue(scope).second).commit()
            }
        } catch (error: Throwable) { failure = error; throw error } finally {
            val editor = prefs.edit()
            oldScopes.forEach { (scope, old) -> if (old.first) editor.putBoolean(scope.preferenceKey, old.second) else editor.remove(scope.preferenceKey) }
            editor.commit()
            BackupArchive.apply(context, before)
            ready()
            credentialStore.write("webdav", oldConfiguration)
            if (oldSync == null) WebDavSync.stateFile(context).delete() else TransferFiles.write(WebDavSync.stateFile(context), oldSync)
            try { client.delete(remote) } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            } finally { configFile.delete() }
        }
    }

    @Test fun g_interruptedRestoreRecoversCompleteSnapshot() {
        val before = snapshot("recovery-before")
        val old = prefs.getString("selfopt_recovery_probe", null)
        try {
            BackupArchive.write(context, BackupArchive.journal(context).outputStream(), before)
            TransferFiles.write(File(BackupArchive.directory(context), "restore-pending"), "2")
            prefs.edit().putString("selfopt_recovery_probe", "incomplete").commit()
            EngineRuntime.maintenance(context) { _, _ -> BackupArchive.recoverOnStartup(context) }
            ready()
            assertEquals(old, prefs.getString("selfopt_recovery_probe", null))
            assertFalse(File(BackupArchive.directory(context), "restore-pending").exists())
            assertTrue(Rime.selectSchema("pinyin"))
        } finally { BackupArchive.apply(context, before); ready() }
    }

    @Test fun h_legacyBackupIsConvertedWithoutReplacingLiveDatabase() {
        val before = snapshot("legacy-before")
        try {
            val databaseFile = File(testRoot, "legacy-ime-db").apply { delete() }
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
                database.execSQL("CREATE TABLE phrase(content TEXT PRIMARY KEY, isKeep INTEGER, t9 TEXT, qwerty TEXT, lx17 TEXT, time INTEGER)")
                database.execSQL("INSERT INTO phrase VALUES('旧备份回归短语', 1, '1', 'a', 'a', 100)")
            }
            val archive = File(testRoot, "legacy.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(json.encodeToString(com.yuyan.imemodule.manager.UserDataManager.Metadata(context.packageName, 1, "1.0.0", 100)).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("shared_prefs/legacy_preferences.xml"))
                zip.write("<map><string name=\"selfopt_legacy_probe\">legacy</string><boolean name=\"selfopt_legacy_boolean\" value=\"true\"/></map>".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("databases/ime_db"))
                databaseFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            com.yuyan.imemodule.manager.UserDataManager.import(archive.inputStream()).getOrThrow()
            ready()
            assertEquals("legacy", prefs.getString("selfopt_legacy_probe", ""))
            assertTrue(prefs.getBoolean("selfopt_legacy_boolean", false))
            assertTrue(DataBaseKT.instance.phraseDao().getAll().any { it.content == "旧备份回归短语" })
            assertTrue(Rime.selectSchema("pinyin"))
        } finally { BackupArchive.apply(context, before); ready() }
    }

    @Test fun i_latestBaseUpdateAndFailedDeploymentRollback() {
        if (InstrumentationRegistry.getArguments().getString("requireTransferFixtures") != "true") org.junit.Assume.assumeTrue("Latest dictionary fixture is not provisioned", File(testRoot, "index-latest.json").isFile)
        val fixture = json.decodeFromString<DictionaryIndex>(File(testRoot, "index-latest.json").readText())
        val frost = fixture.packages.single { it.family == "frost" }
        val source = File(testRoot, "frost-latest.zip")
        DictionaryStore.validateArchive(source, frost)
        val previous = DictionaryStore.active(context)
        try {
            val stored = DictionaryStore.packageFile(context, frost).apply { parentFile!!.mkdirs() }
            source.copyTo(stored, overwrite = true)
            EngineRuntime.activatePackages(context, previous + ("frost" to frost))
            ready()
            assertEquals(frost.sha256, DictionaryStore.active(context).getValue("frost").sha256)
            assertTrue(Rime.selectSchema("pinyin"))
            Rime.clearComposition()
            "nihao".forEach { Rime.processKey(it.code, 0) }
            assertTrue(Rime.candidates.any { it.text == "你好" })
            Rime.clearComposition()
            val broken = File(testRoot, "broken-dictionary.zip")
            java.util.zip.ZipFile(source).use { zip -> ZipOutputStream(broken.outputStream()).use { out ->
                out.setLevel(0)
                zip.entries().asSequence().forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "rime_frost.dict.yaml") out.write("name: rime_frost\nversion: 'broken-test'\nimport_tables:\n- nonexistent_dictionary\n...\n".toByteArray())
                    else zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            } }
            val invalid = frost.copy(sha256 = TransferFiles.sha256(broken), size = broken.length())
            val badTarget = DictionaryStore.packageFile(context, invalid).apply { parentFile!!.mkdirs() }
            broken.copyTo(badTarget, overwrite = true)
            assertTrue("Invalid deployment must fail", runCatching { EngineRuntime.activatePackages(context, previous + ("frost" to invalid)) }.isFailure)
            instrumentation.waitForIdleSync()
            ready()
            assertEquals(frost.sha256, DictionaryStore.active(context).getValue("frost").sha256)
            assertTrue(Rime.selectSchema("pinyin"))
            badTarget.delete()
        } finally { EngineRuntime.activatePackages(context, previous); ready() }
    }

    @Test fun k_interruptedActivationStopsAutomaticRetry() {
        val indexFile = File(testRoot, "index.json")
        if (InstrumentationRegistry.getArguments().getString("requireTransferFixtures") != "true") org.junit.Assume.assumeTrue(indexFile.isFile)
        val ice = json.decodeFromString<DictionaryIndex>(indexFile.readText()).packages.single { it.family == "ice" }
        val sandbox = File(testRoot, "activation-sandbox").apply { deleteRecursively(); mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = sandbox
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                this@TransferIntegrationTest.context.getSharedPreferences("activation_test_$name", mode)
        }
        val state = isolated.getSharedPreferences("transfer_state", android.content.Context.MODE_PRIVATE)
        state.edit().clear().commit()
        try {
            val previous = DictionaryStore.active(isolated)
            val target = DictionaryStore.packageFile(isolated, ice).apply { parentFile!!.mkdirs() }
            File(testRoot, "ice.zip").copyTo(target, overwrite = true)
            DictionaryStore.beginActivation(isolated, previous)
            DictionaryStore.setActive(isolated, previous + ("ice" to ice))
            val pending = File(sandbox, "transfer/dictionary-pending.json")
            TransferFiles.write(pending, json.encodeToString(listOf(ice)))
            state.edit().putBoolean("update_requested", true).putBoolean("update_running", true).commit()
            DictionaryStore.recoverActivation(isolated)
            assertFalse(DictionaryStore.hasIce(isolated))
            assertFalse(pending.exists())
            assertFalse(state.getBoolean("update_requested", true))
            assertFalse(state.getBoolean("update_running", true))
            assertTrue(state.getStringSet("blocked_dictionaries", emptySet())!!.contains(ice.sha256))
        } finally { state.edit().clear().commit(); sandbox.deleteRecursively() }
    }
}
