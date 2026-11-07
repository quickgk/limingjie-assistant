package com.landosol.toolbox.labyrinth.shop

import java.io.File
import com.landosol.toolbox.labyrinth.vision.ShopPerformance
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

/**
 * 仅保存同一账户、实际 enter 的购物事实，不提供整局导出或全局状态。
 *
 * [onPruneFailure] 是清理未完成时的诊断出口：journal 不依赖 Android 日志，由调用方决定怎么记。
 * 每次 [open] 都会把 [pruneRuns] 里没删干净的目录交给它，否则这些失败只留在返回值里，
 * 生产装配不会去看。
 */
class LabyrinthShoppingJournal(
    private val root: File,
    private val onPruneFailure: (File) -> Unit = {},
) {
    // 同一目录的不同 facade / store 实例也必须串行读改写。
    internal val lock: Any = locks.computeIfAbsent(root.canonicalPath) { Any() }
    private val parsedCache = caches.computeIfAbsent(root.canonicalPath) { linkedMapOf() }
    private data class Parsed(val bytes: ByteArray, val events: List<JsonObject>)
    private val json = Json { prettyPrint = false }

    data class Run(val runId: String, val enterId: Long, val directory: File)

    fun open(accountId: Long?, enterId: Long, batchRunId: String?, now: Long,
        delete: (File) -> Boolean = File::delete): Run = synchronized(lock) {
        require(enterId > 0) { "必须使用实际 enterId" }
        val id = UUID.nameUUIDFromBytes("labyrinth-shopping:${accountId ?: "interactive"}:$enterId"
            .toByteArray(Charsets.UTF_8)).toString()
        val run = Run(id, enterId, File(root, id))
        check(run.directory.isDirectory || run.directory.mkdirs()) { "无法创建购物证据目录" }
        validateRun(run)
        val manifest = containedFile(run, "manifest.json")
        if (!manifest.exists()) atomicWrite(manifest, buildJsonObject {
            put("schema_version", 1); put("run_id", id); put("enter_id", enterId)
            put("account_id", JsonPrimitive(accountId)); put("batch_run_id", JsonPrimitive(batchRunId))
            put("started_at", Instant.ofEpochMilli(now).toString()); put("scope", "shopping")
        }.toString().toByteArray(Charsets.UTF_8))
        val existing = json.parseToJsonElement(manifest.readText(Charsets.UTF_8)).jsonObject
        check(existing["run_id"]?.jsonPrimitive?.content == id &&
            existing["enter_id"]?.jsonPrimitive?.longOrNull == enterId &&
            existing["account_id"] == JsonPrimitive(accountId)) { "购物日志账户或 enter 不一致" }
        read(run)
        runCatching { pruneRuns(currentRunId = run.runId, delete = delete) }.getOrNull()?.let(::reportPruneFailures)
        run
    }

    /** 把清理失败的目录交给诊断出口；[open] 与需要观察结果的调用方共用这一条路径。 */
    internal fun reportPruneFailures(outcome: PruneOutcome) {
        if (outcome.clean) return
        outcome.failed.forEach { directory -> runCatching { onPruneFailure(directory) } }
    }

    fun append(run: Run, eventId: String, type: String, nodeId: Long?, floor: Int?, now: Long,
        payload: JsonObject, verification: String = "verified",
        source: String = "actual_screen"): JsonObject = synchronized(lock) {
        validateRun(run)
        require(eventId.matches(EVENT_ID) && type.isNotBlank()) { "事件编号或类型无效" }
        val events = read(run)
        events.firstOrNull { it["event_id"]?.jsonPrimitive?.content == eventId }?.let { event ->
            check(event["type"]?.jsonPrimitive?.content == type && event["payload"] == payload &&
                event["node_id"] == JsonPrimitive(nodeId) && event["floor"] == JsonPrimitive(floor) &&
                event["verification_status"]?.jsonPrimitive?.content == verification &&
                event["source"]?.jsonPrimitive?.content == source) { "同一事件编号内容发生变化" }
            return@synchronized event
        }
        val event = buildJsonObject {
            put("schema_version", 1); put("run_id", run.runId); put("enter_id", run.enterId)
            put("event_id", eventId); put("seq", events.size + 1); put("type", type)
            put("node_id", JsonPrimitive(nodeId)); put("floor", JsonPrimitive(floor))
            put("timestamp_utc", Instant.ofEpochMilli(now).toString())
            put("source", source); put("verification_status", verification)
            put("payload", payload)
        }
        parsedCache.remove(run.runId)
        val appendStarted = System.nanoTime()
        FileOutputStream(containedFile(run, "events.jsonl"), true).use {
            it.write((event.toString() + "\n").toByteArray(Charsets.UTF_8))
            val syncStarted = System.nanoTime()
            it.fd.sync()
            ShopPerformance.event("journal.sync", syncStarted, field = type)
        }
        ShopPerformance.event("journal.append", appendStarted, field = type)
        event
    }

    fun read(run: Run): List<JsonObject> = synchronized(lock) {
        try {
            validateRun(run)
            validateManifest(run)
            val file = containedFile(run, "events.jsonl")
            if (!file.exists()) {
                parsedCache.remove(run.runId)
                return@synchronized emptyList()
            }
            val readStarted = System.nanoTime()
            val bytes = file.readBytes()
            ShopPerformance.event("journal.read", readStarted, count = bytes.size.toLong())
            parsedCache[run.runId]?.takeIf { it.bytes.contentEquals(bytes) }?.let {
                ShopPerformance.event("journal.cache_hit", count = it.events.size.toLong())
                return@synchronized it.events
            }
            parsedCache.remove(run.runId)
            val parseStarted = System.nanoTime()
            val completeLength = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
            val completeBytes = bytes.copyOf(completeLength)
            val events = completeBytes.toString(Charsets.UTF_8).lineSequence()
                .filter(String::isNotBlank).map { immutableJson(json.parseToJsonElement(it)).jsonObject }.toList()
            val ids = mutableSetOf<String>()
            events.forEachIndexed { index, event ->
                check(event["run_id"]?.jsonPrimitive?.content == run.runId &&
                    event["enter_id"]?.jsonPrimitive?.longOrNull == run.enterId &&
                    event["seq"]?.jsonPrimitive?.intOrNull == index + 1 &&
                    event["event_id"]?.jsonPrimitive?.content?.let { it.matches(EVENT_ID) && ids.add(it) } == true) {
                    "购物日志完整事件损坏或重复"
                }
            }
            ShopPerformance.event("journal.parse", parseStarted, count = events.size.toLong())
            // 完整行损坏直接拒绝；只恢复中断追加的末尾，并先原子保存截断内容。
            if (completeLength != bytes.size) {
                atomicWrite(containedFile(run, "interrupted-tail-${UUID.randomUUID()}.bin"),
                    bytes.copyOfRange(completeLength, bytes.size))
                atomicWrite(file, completeBytes)
            }
            val snapshot = java.util.Collections.unmodifiableList(events)
            if (parsedCache.size >= RETAINED_RUNS) parsedCache.remove(parsedCache.keys.first())
            parsedCache[run.runId] = Parsed(completeBytes, snapshot)
            snapshot
        } catch (failure: Exception) {
            parsedCache.remove(run.runId)
            throw failure
        }
    }

    private fun immutableJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(java.util.Collections.unmodifiableMap(value.mapValues { immutableJson(it.value) }))
        is JsonArray -> JsonArray(java.util.Collections.unmodifiableList(value.map(::immutableJson)))
        else -> value
    }

    /**
     * 保留最近 [keep] 次 enter 的日志目录，包括 [currentRunId]。
     * 只清理本根目录内有 manifest 的旧运行；当前运行永不删除。
     * 静置时间取目录内文件最大 mtime，追加中的日志不会被误判为空闲。
     * 返回删除数与未清理完的目录，便于记录诊断并重试。
     */
    fun pruneRuns(
        keep: Int = RETAINED_RUNS,
        olderThanMillis: Long = RETAINED_RUN_AGE_MILLIS,
        now: Long = System.currentTimeMillis(),
        currentRunId: String? = null,
        delete: (File) -> Boolean = File::delete,
    ): PruneOutcome = synchronized(lock) {
        require(keep >= 1) { "至少保留一次运行" }
        val rootPath = root.canonicalPath + File.separator
        val runs = root.listFiles { file -> file.isDirectory && RUN_DIR.matches(file.name) && File(file, "manifest.json").isFile }
            .orEmpty()
            .filter { it.name != currentRunId }
            .sortedByDescending(::lastActivity)
        // 当前运行也占一个名额，keep 才是「一共保留几次」。
        val retainedOthers = if (currentRunId != null) (keep - 1).coerceAtLeast(0) else keep
        var removed = 0
        val failed = mutableListOf<File>()
        runs.drop(retainedOthers).forEach { directory ->
            if (now - lastActivity(directory) < olderThanMillis) return@forEach
            if (!directory.canonicalPath.startsWith(rootPath)) return@forEach
            if (deleteTree(directory, delete)) removed++ else failed += directory
        }
        PruneOutcome(removed, failed)
    }

    /**
     * [pruneRuns] 的结果：删掉的次数，以及没删干净的目录。
     *
     * 通常这些目录的 manifest 仍在，下一轮会重试。但 manifest 写回本身失败时，目录可能已经没有
     * manifest，下一次清理的 manifest 过滤就看不到它——这种残留只能通过 [failed] 观察到，
     * 所以 [open] 会把 [failed] 交给构造时的 `onPruneFailure`，由应用层记成诊断。
     */
    data class PruneOutcome(val removed: Int, val failed: List<File>) {
        val clean: Boolean get() = failed.isEmpty()
    }

    /** 运行目录的最后活动时间：目录内文件的最大 mtime，没有文件时退回目录自身。 */
    private fun lastActivity(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: directory.lastModified()

    /**
     * 自底向上删除运行目录及子项；只有目录真的消失才算删除成功。
     *
     * manifest 是清理的索引：其它节点全部删掉之后才删它；任一项删不掉就保留 manifest 并返回
     * false，下一次 prune 仍能看见这个目录继续清理。目录本身删不掉时把 manifest 原样写回（连
     * 时间戳一起），避免留下没有 manifest 的壳。写回本身失败时同样返回 false 并计入
     * [PruneOutcome.failed]；这种情况下目录可能真的已经没有 manifest，下一轮看不到它，
     * 只能通过 failed 观察到。
     * [delete] 可注入，便于在任意平台上验证删除失败的路径。
     */
    private fun deleteTree(directory: File, delete: (File) -> Boolean): Boolean {
        // 先取快照再删，避免边遍历边改目录树。
        val nodes = directory.walkBottomUp().toList().filter { it != directory }
        val manifest = File(directory, "manifest.json")
        val others = nodes.filter { it.canonicalPath != manifest.canonicalPath }
        if (!others.all { delete(it) || !it.exists() }) return false
        val manifestBytes = if (manifest.isFile) runCatching { manifest.readBytes() }.getOrNull() else null
        val manifestModified = manifest.lastModified()
        if (!delete(manifest) && manifest.exists()) return false
        if (delete(directory) || !directory.exists()) return true
        if (manifestBytes != null) {
            val restored = runCatching {
                atomicWrite(manifest, manifestBytes)
                manifest.setLastModified(manifestModified)
            }.isSuccess
            if (!restored) return false
        }
        return false
    }

    /** 空日志也须核对运行清单，不能借伪造的 Run 写入其他账户或 enter。 */
    private fun validateManifest(run: Run) {
        val manifest = containedFile(run, "manifest.json")
        check(manifest.isFile) { "购物运行清单缺失" }
        val saved = json.parseToJsonElement(manifest.readText(Charsets.UTF_8)).jsonObject
        val account = saved["account_id"]
        check(account != null && (account == JsonNull ||
            !account.jsonPrimitive.isString && account.jsonPrimitive.longOrNull != null)) { "购物清单账户无效" }
        val accountId = account.jsonPrimitive.longOrNull
        val expectedId = UUID.nameUUIDFromBytes("labyrinth-shopping:${accountId ?: "interactive"}:${run.enterId}"
            .toByteArray(Charsets.UTF_8)).toString()
        check(saved["schema_version"] == JsonPrimitive(1) && saved["scope"] == JsonPrimitive("shopping") &&
            saved["run_id"] == JsonPrimitive(run.runId) && saved["enter_id"] == JsonPrimitive(run.enterId) &&
            run.runId == expectedId) { "购物日志账户、enter 或运行清单不一致" }
    }

    private fun validateRun(run: Run) {
        require(run.enterId > 0 && run.runId.matches(Regex("[a-f0-9-]{36}"))) { "购物记录标识无效" }
        require(run.directory.canonicalFile == File(root.canonicalFile, run.runId).absoluteFile &&
            run.directory.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "购物目录越界" }
    }

    private fun containedFile(run: Run, relative: String): File = File(run.directory, relative).also {
        require(it.canonicalPath.startsWith(run.directory.canonicalPath + File.separator)) { "购物文件路径越界" }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val pending = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        require(pending.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "临时文件路径越界" }
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (pending.exists()) check(pending.delete()) { "无法清理未完成日志" }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private val caches = ConcurrentHashMap<String, MutableMap<String, Parsed>>()
        private val EVENT_ID = Regex("[a-zA-Z0-9_-]+")
        private val RUN_DIR = Regex("[a-f0-9-]{36}")
        /** 保留最近几次 enter 的证据；更早的运行已经提交过持有，不需要再回放。 */
        private const val RETAINED_RUNS = 4
        /** 目录静置多久才允许删除，避免误删仍在进行的运行。 */
        private const val RETAINED_RUN_AGE_MILLIS = 3_600_000L
        fun key(value: String): String = keyBytes(value.toByteArray(Charsets.UTF_8))
        private fun keyBytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it) }
    }
}
