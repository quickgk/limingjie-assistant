package com.landosol.toolbox.labyrinth.shop

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LabyrinthShoppingJournalTest {
    @get:Rule val temp = TemporaryFolder()
    private val root by lazy { temp.newFolder("journal") }
    private val store by lazy { LabyrinthShoppingJournal(root) }
    private val run by lazy { store.open(1, 99, "batch-1", 0) }
    private val body = buildJsonObject { put("amount", 12) }
    private fun append(key: String = "test-1") = store.append(run, key, "test.fact", 3, 1, 0, body)

    @Test fun `只保留最近运行且当前运行与静置不足的目录不删除`() {
        val now = System.currentTimeMillis()
        val runs = (1..4).map { store.open(1, 100L + it, null, 0) }
        // 残留子目录也必须自底向上清理。
        runs.forEach { run ->
            val key = "probe-${run.enterId}"
            store.append(run, key, "test.fact", 1, 1, 0, body)
            File(run.directory, "nested/child.txt").apply { check(parentFile!!.mkdirs()); writeText("retained") }
        }
        runs[0].let { age(it, now - 14_400_000L) }
        runs[1].let { age(it, now - 10_800_000L) }
        runs[2].let { age(it, now - 7_200_000L) }
        runs[3].let { age(it, now) }

        // 当前运行（runs[0]，最旧）绝不能被当成旧运行删掉；keep=2 表示「当前 + 一个其它运行」。
        assertEquals(2, store.pruneRuns(keep = 2, olderThanMillis = 3_600_000L, now = now, currentRunId = runs[0].runId).removed)
        assertTrue(runs[0].directory.exists())
        assertTrue(File(runs[0].directory, "events.jsonl").isFile)
        assertTrue(runs[3].directory.exists())
        assertFalse(runs[1].directory.exists())
        assertFalse(runs[2].directory.exists())
        assertFalse(File(runs[1].directory, "nested").exists())
        assertEquals(1, store.read(runs[3]).size)

        val other = LabyrinthShoppingJournal(temp.newFolder("prune"))
        val recent = (1..3).map { other.open(1, 200L + it, null, 0) }
        recent[0].let { age(it, now - 7_200_000L) }
        recent[1].let { age(it, now - 7_200_000L) }
        recent[2].let { age(it, now) }
        // 静置不足的目录即使超出保留数量也不删。
        assertEquals(2, other.pruneRuns(keep = 1, olderThanMillis = 3_600_000L, now = now).removed)
        assertEquals(listOf(false, false, true), recent.map { it.directory.exists() })
    }

    @Test fun `子项删不掉时保留manifest等下一轮清理`() {
        val now = System.currentTimeMillis()
        val runs = seededRuns(now, baseEnter = 300L)
        val blockedFile = File(runs[0].directory, "events.jsonl")
        // 注入删除失败，不依赖平台的文件占用语义，CI 上同样能跑。
        val blocked = store.pruneRuns(keep = 1, olderThanMillis = 3_600_000L, now = now,
            delete = { file -> file != blockedFile && file.delete() })
        assertEquals(0, blocked.removed)
        assertEquals(listOf(runs[0].directory), blocked.failed)
        assertTrue(File(runs[0].directory, "manifest.json").isFile)
        assertTrue(blockedFile.exists())

        age(runs[0], now - 7_200_000L)
        assertEquals(1, store.pruneRuns(keep = 1, olderThanMillis = 3_600_000L, now = now).removed)
        assertFalse(runs[0].directory.exists())
    }

    @Test fun `目录本身删不掉时把manifest写回等下一轮`() {
        val now = System.currentTimeMillis()
        val runs = seededRuns(now, baseEnter = 400L)
        val manifest = File(runs[0].directory, "manifest.json")
        val originalModified = manifest.lastModified()
        val blocked = store.pruneRuns(keep = 1, olderThanMillis = 3_600_000L, now = now,
            delete = { file -> file != runs[0].directory && file.delete() })
        assertEquals(0, blocked.removed)
        assertEquals(listOf(runs[0].directory), blocked.failed)
        // 其它节点已经删掉，但目录还在：manifest 必须写回，否则下一轮再也看不到这个目录。
        assertTrue(manifest.isFile)
        assertEquals(originalModified, manifest.lastModified())
        assertFalse(File(runs[0].directory, "events.jsonl").exists())
        assertEquals(0, store.read(runs[0]).size)

        assertEquals(1, store.pruneRuns(keep = 1, olderThanMillis = 3_600_000L, now = now).removed)
        assertFalse(runs[0].directory.exists())
    }

    @Test fun `清理失败通过构造时的诊断回调暴露`() {
        val failures = mutableListOf<File>()
        val journal = LabyrinthShoppingJournal(temp.newFolder("diagnostics")) { failures += it }
        journal.reportPruneFailures(LabyrinthShoppingJournal.PruneOutcome(2, emptyList()))
        assertTrue(failures.isEmpty())
        journal.reportPruneFailures(LabyrinthShoppingJournal.PruneOutcome(0, listOf(File("a"), File("b"))))
        assertEquals(listOf(File("a"), File("b")), failures)
    }

    @Test fun `open清理失败时交给诊断回调`() {
        val now = System.currentTimeMillis()
        val failures = mutableListOf<File>()
        val journal = LabyrinthShoppingJournal(temp.newFolder("open-prune")) { failures += it }
        val runs = (1..5).map { journal.open(1, 500L + it, null, 0) }
        val target = runs[0]
        val key = "probe-${target.enterId}"
        journal.append(target, key, "test.fact", 1, 1, 0, body)
        val blockedFile = File(target.directory, "events.jsonl")
        age(target, now - 7_200_000L)

        // 默认 keep=4，目标运行最旧；删除只卡住它的证据文件，目录因此清理失败。
        val current = journal.open(1, 600L, null, 0, delete = { file -> file != blockedFile && file.delete() })

        assertEquals(listOf(target.directory), failures)
        assertTrue(blockedFile.exists())
        assertEquals(600L, current.enterId)
        assertTrue(current.directory.isDirectory)
    }

    @Test fun `诊断回调抛异常不影响open返回运行`() {
        val now = System.currentTimeMillis()
        var callbackInvoked = false
        var deleteAttempted = false
        val journal = LabyrinthShoppingJournal(temp.newFolder("throwing-diagnostics")) {
            callbackInvoked = true
            error("诊断回调失败")
        }
        val runs = (1..5).map { journal.open(1, 700L + it, null, 0) }
        val target = runs[0]
        val key = "probe-${target.enterId}"
        journal.append(target, key, "test.fact", 1, 1, 0, body)
        val blockedFile = File(target.directory, "events.jsonl")
        age(target, now - 7_200_000L)

        val current = journal.open(1, 800L, null, 0, delete = { file ->
            if (file == blockedFile) deleteAttempted = true
            file != blockedFile && file.delete()
        })

        assertTrue(deleteAttempted)
        assertTrue(callbackInvoked)
        assertEquals(800L, current.enterId)
        assertTrue(current.directory.isDirectory)
    }

    /** 两个带事件与证据的运行：第一个静置超期、第二个是新的。 */
    private fun seededRuns(now: Long, baseEnter: Long): List<LabyrinthShoppingJournal.Run> {
        val runs = (1..2).map { store.open(1, baseEnter + it, null, 0) }
        runs.forEach { run ->
            val key = "probe-${run.enterId}"
            store.append(run, key, "test.fact", 1, 1, 0, body)
        }
        age(runs[0], now - 7_200_000L)
        age(runs[1], now)
        return runs
    }

    /** 目录自身的 mtime 不随追加日志变化，所以按目录内文件统一回拨。 */
    private fun age(run: LabyrinthShoppingJournal.Run, millis: Long) {
        run.directory.walkTopDown().forEach { it.setLastModified(millis) }
    }

    @Test fun `账户实际enter稳定batch及时间变化不会新建目录`() {
        val first = run
        val second = store.open(1, 99, "batch-2", 999)
        assertEquals(first, second)
        val manifest = Json.parseToJsonElement(File(run.directory, "manifest.json").readText()).jsonObject
        assertEquals("batch-1", manifest["batch_run_id"]!!.jsonPrimitive.content)
        assertEquals(1, root.listFiles()!!.count { it.isDirectory })
    }

    @Test fun `账户enter与空账户目录彼此独立`() {
        val ids = listOf(run, store.open(2, 99, null, 0), store.open(1, 100, null, 0), store.open(null, 99, null, 0))
            .map { it.runId }
        assertEquals(4, ids.distinct().size)
    }

    @Test fun `实际enter必须为正且错误不创建日志`() {
        ShoppingTestData.reject { store.open(1, 0, null, 0) }
        ShoppingTestData.reject { store.open(1, -1, null, 0) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun `事件按连续序号耐久存储并可由新store读取`() {
        append(); append("test-2")
        val reopened = LabyrinthShoppingJournal(root)
        assertEquals(listOf(1, 2), reopened.read(reopened.open(1, 99, null, 1)).map { it["seq"]!!.jsonPrimitive.int })
    }

    @Test fun `append同键幂等但类型或payload变化必须拒绝`() {
        val event = append(); assertEquals(event, append())
        ShoppingTestData.reject { store.append(run, "test-1", "changed", 3, 1, 0, body) }
        ShoppingTestData.reject { store.append(run, "test-1", "test.fact", 3, 1, 0, buildJsonObject { put("amount", 13) }) }
        assertEquals(1, store.read(run).size)
    }

    @Test fun `新事件仅保存可回放日志而不产生旁路文件`() {
        val event = append()
        assertEquals(setOf("schema_version", "run_id", "enter_id", "event_id", "seq", "type", "node_id", "floor",
            "timestamp_utc", "source", "verification_status", "payload"), event.keys)
        assertEquals(setOf("manifest.json", "events.jsonl"), run.directory.listFiles()!!.map { it.name }.toSet())
        assertEquals(event, append())
    }

    @Test fun `日志IO失败不产生事件并可恢复追加`() {
        val before = append()
        ShoppingTestData.withBlockedAppend(run) { ShoppingTestData.reject { append("test-2") } }
        assertEquals(listOf(before), store.read(run))
        assertEquals(2, append("test-2")["seq"]!!.jsonPrimitive.int)
    }

    @Test fun `重复编号的作用域来源和状态都必须相同`() {
        val event = append()
        ShoppingTestData.reject { store.append(run, "test-1", "test.fact", 4, 1, 0, body) }
        ShoppingTestData.reject { store.append(run, "test-1", "test.fact", 3, 2, 0, body) }
        ShoppingTestData.reject { store.append(run, "test-1", "test.fact", 3, 1, 0, body, source = "automation_action") }
        ShoppingTestData.reject { store.append(run, "test-1", "test.fact", 3, 1, 0, body, verification = "intent_only") }
        assertEquals(listOf(event), store.read(run))
    }

    @Test fun `拒绝事件编号路径穿越及伪造run`() {
        ShoppingTestData.reject { append("../escape") }
        ShoppingTestData.reject { store.read(run.copy(directory = temp.root)) }
        val forged = run.copy(enterId = 100)
        ShoppingTestData.reject { store.read(forged) }
        ShoppingTestData.reject { store.append(forged, "bad", "test.fact", 3, 1, 0, body) }
        assertTrue(store.read(run).isEmpty())
    }

    @Test fun `其他enter完整事件不能混入当前日志`() {
        append()
        ShoppingTestData.rewriteEvents(run) { event -> JsonObject(event + ("enter_id" to JsonPrimitive(100))) }
        ShoppingTestData.reject { store.read(run) }
    }

    @Test fun `中断追加末尾先保存再截断且后续序号正常`() {
        append()
        val partial = "{\"event_id\":\"incomplete\"".toByteArray()
        FileOutputStream(File(run.directory, "events.jsonl"), true).use { it.write(partial) }
        val recovered = LabyrinthShoppingJournal(root)
        assertEquals(1, recovered.read(run).size)
        val tail = run.directory.listFiles()!!.single { it.name.startsWith("interrupted-tail-") }
        assertArrayEquals(partial, tail.readBytes())
        assertEquals('\n'.code.toByte(), File(run.directory, "events.jsonl").readBytes().last())
        recovered.append(run, "next", "test.fact", 3, 1, 1, body)
        assertEquals(2, recovered.read(run).last()["seq"]!!.jsonPrimitive.int)
    }

    @Test fun `只有半条首事件时恢复为空日志`() {
        val current = run
        val file = File(current.directory, "events.jsonl"); file.writeText("{\"seq\":1")
        assertTrue(store.read(current).isEmpty()); assertEquals(0L, file.length())
        assertEquals(1, append()["seq"]!!.jsonPrimitive.int)
    }

    @Test fun `完整行损坏不能当作中断尾静默删掉`() {
        append(); val file = File(run.directory, "events.jsonl")
        FileOutputStream(file, true).use { it.write("not-json\n".toByteArray()) }
        val original = file.readBytes()
        ShoppingTestData.reject { store.read(run) }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun `同长度日志修改也必须重读拒绝而不能信任缓存`() {
        append(); val file = File(run.directory, "events.jsonl")
        val timestamp = file.lastModified()
        file.writeText(file.readText().replace("\"seq\":1", "\"seq\":2"))
        Files.setLastModifiedTime(file.toPath(), java.nio.file.attribute.FileTime.fromMillis(timestamp))
        ShoppingTestData.reject { store.read(run) }
    }

    @Test fun `完整事件重复行拒绝double booking`() {
        append(); val file = File(run.directory, "events.jsonl"); val line = file.readBytes()
        FileOutputStream(file, true).use { it.write(line) }
        ShoppingTestData.reject { store.read(run) }
    }

    @Test fun `manifest账户损坏拒绝读取追加及重新open`() {
        val current = run; val file = File(current.directory, "manifest.json")
        file.writeText(file.readText().replace("\"account_id\":1", "\"account_id\":2"))
        ShoppingTestData.reject { store.read(current) }
        ShoppingTestData.reject { append() }
        ShoppingTestData.reject { store.open(1, 99, null, 1) }
        assertFalse(File(current.directory, "events.jsonl").exists())
    }
}
