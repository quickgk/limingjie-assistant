package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.vision.ShopPerformance
import com.landosol.toolbox.labyrinth.vision.ShopPerformanceRecorder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LabyrinthShoppingJournalCacheTest {
    private fun mutationRejected(block: () -> Unit) {
        try { block(); fail("mutable cached snapshot") }
        catch (_: UnsupportedOperationException) { }
        catch (_: ClassCastException) { }
    }
    @get:Rule val temp = TemporaryFolder()

    @Test fun `warm reads across facades validate bytes without repeating parse`() {
        val root = temp.newFolder()
        val store = LabyrinthShoppingJournal(root)
        val run = store.open(1, 10, null, 0)
        store.append(run, "one", "test", 50001, 5, 0, buildJsonObject { put("text", "old") })
        val previous = ShopPerformance.recorder
        val counts = mutableMapOf<String, Int>()
        ShopPerformance.recorder = ShopPerformanceRecorder { counts[it.name] = (counts[it.name] ?: 0) + 1 }
        try {
            val first = store.read(run)
            val other = LabyrinthShoppingJournal(root)
            repeat(50) { assertEquals(first, other.read(run)) }
            assertEquals(1, counts["journal.parse"])
            assertEquals(51, counts["journal.read"])
            assertEquals(50, counts["journal.cache_hit"])
            other.append(run, "two", "test", 50001, 5, 1, buildJsonObject { put("text", "new") })
            assertEquals(2, store.read(run).size)
            assertEquals(2, counts["journal.parse"])
            assertEquals(1, first.size)
            assertEquals(2, other.read(run).size)
            assertEquals(2, counts["journal.parse"])
        } finally { ShopPerformance.recorder = previous }
    }

    @Test fun `same size restored mtime edits cannot return cached content or mask corruption`() {
        val root = temp.newFolder()
        val store = LabyrinthShoppingJournal(root)
        val run = store.open(1, 10, null, 0)
        store.append(run, "one", "test", null, null, 0, buildJsonObject { put("text", "old") })
        store.read(run)
        val file = File(run.directory, "events.jsonl")
        val time = Files.getLastModifiedTime(file.toPath())
        val before = file.readBytes()
        file.writeText(file.readText().replace("old", "new"))
        Files.setLastModifiedTime(file.toPath(), time)
        assertEquals(before.size.toLong(), file.length())
        assertEquals("new", store.read(run).single()["payload"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        file.writeText(file.readText().replace("\"seq\":1", "\"seq\":2"))
        Files.setLastModifiedTime(file.toPath(), time)
        ShoppingTestData.reject { store.read(run) }
        ShoppingTestData.reject { LabyrinthShoppingJournal(root).read(run) }
    }

    @Test fun `warm cache cannot bypass manifest directory failure or interrupted tail recovery`() {
        val root = temp.newFolder()
        val store = LabyrinthShoppingJournal(root)
        val run = store.open(1, 10, null, 0)
        store.append(run, "one", "test", null, null, 0, buildJsonObject { put("text", "old") })
        val initial = store.read(run)
        val file = File(run.directory, "events.jsonl")
        FileOutputStream(file, true).use { it.write("{\"seq\":2".toByteArray()) }
        assertEquals(initial, store.read(run))
        assertTrue(run.directory.listFiles()!!.any { it.name.startsWith("interrupted-tail-") && it.readText() == "{\"seq\":2" })
        val canonical = file.readBytes()
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        ShoppingTestData.reject { store.read(run) }
        assertTrue(file.delete())
        file.writeBytes(canonical)
        assertEquals(initial, store.read(run))
        val manifest = File(run.directory, "manifest.json")
        val saved = manifest.readText()
        manifest.writeText(saved.replace("\"enter_id\":10", "\"enter_id\":11"))
        ShoppingTestData.reject { store.read(run) }
        manifest.writeText(saved)
        assertEquals(initial, store.read(run))
        assertEquals(2, store.append(run, "two", "test", null, null, 1, buildJsonObject { })["seq"]!!.jsonPrimitive.int)
    }

    @Test fun `cached event snapshots and nested json are deeply immutable`() {
        val root = temp.newFolder()
        val store = LabyrinthShoppingJournal(root)
        val run = store.open(1, 10, null, 0)
        val nested = mutableMapOf<String, JsonElement>("value" to JsonPrimitive("old"))
        val array = mutableListOf<JsonElement>(JsonObject(nested))
        val payload = JsonObject(mutableMapOf("nested" to JsonArray(array)))
        store.append(run, "one", "test", null, null, 0, payload)
        nested["value"] = JsonPrimitive("new")
        array.clear()
        val events = store.read(run)
        @Suppress("UNCHECKED_CAST")
        mutationRejected { (events as MutableList<JsonObject>).clear() }
        val cached = events.single()["payload"]!!.jsonObject["nested"]!!.jsonArray
        @Suppress("UNCHECKED_CAST")
        mutationRejected { (cached as java.util.List<JsonElement>).clear() }
        @Suppress("UNCHECKED_CAST")
        mutationRejected { (cached.single().jsonObject as java.util.Map<String, JsonElement>).put("value", JsonPrimitive("bad")) }
        mutationRejected { (cached.single().jsonObject.entries.first() as java.util.Map.Entry<String, JsonElement>).setValue(JsonPrimitive("bad")) }
        assertEquals("old", store.read(run).single()["payload"]!!.jsonObject["nested"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
    }
}
