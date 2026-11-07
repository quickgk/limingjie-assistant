package com.landosol.toolbox.gamedata

import com.landosol.toolbox.labyrinth.shop.LabyrinthShopImprintCatalog
import com.landosol.toolbox.labyrinth.vision.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LabyrinthSummonUnitResourcesTest {
    @get:Rule val temp = TemporaryFolder()
    private val unit = LabyrinthSummonUnitResource("31003000", "攻击型职能的随机印记", 3, 1, 1200)

    @Test fun `declared summon resource is loaded through the resource pack contract`() {
        val root = temp.newFolder()
        File(root, "manifest.json").writeText("""{"packId":"fixture","version":"1","files":[
            "characters.json","relics.json","bosses.json","events.json","${LabyrinthSummonUnitResources.FILE_NAME}"]}""")
        for (category in listOf("characters", "relics", "bosses", "events")) {
            File(root, "$category.json").writeText("""{"schemaVersion":1,"$category":[]}""")
        }
        val file = File(root, LabyrinthSummonUnitResources.FILE_NAME)
        file.writeText(Json.encodeToString(LabyrinthSummonUnitDocument.serializer(), LabyrinthSummonUnitDocument(summonUnits = listOf(unit))))
        val success = ResourcePackLoader().load(root)
        assertTrue("$success", success is ResourcePackLoadResult.Success)
        assertEquals(listOf(unit), (success as ResourcePackLoadResult.Success).data.summonUnits!!.summonUnits)
        file.writeText("""{"schemaVersion":2,"summonUnits":[]}""")
        assertTrue(ResourcePackLoader().load(root) is ResourcePackLoadResult.Failure)
        assertTrue(file.delete())
        assertTrue(ResourcePackLoader().load(root) is ResourcePackLoadResult.Failure)
    }

    @Test fun `schema duplicate ids malformed sale prices and summon fields fail validation`() {
        val invalid = listOf(
            LabyrinthSummonUnitDocument(schemaVersion = 2, summonUnits = listOf(unit)),
            LabyrinthSummonUnitDocument(),
            LabyrinthSummonUnitDocument(summonUnits = listOf(unit, unit)),
        ) + listOf(unit.copy(id = "bad"), unit.copy(name = ""), unit.copy(price = 0), unit.copy(price = -1),
            unit.copy(price = 99_999_999), unit.copy(summonType = 2), unit.copy(summonNum = 2))
            .map { LabyrinthSummonUnitDocument(summonUnits = listOf(it)) }
        invalid.forEach { document ->
            assertTrue(LabyrinthSummonUnitResources.validate(document).isNotEmpty())
            val data = ResourcePackData(ResourcePackDocument("fixture", "1"), emptyList(), emptyList(), emptyList(), emptyList(),
                summonUnits = document)
            assertTrue(ResourcePackDataValidator().validate(data).isNotEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                LabyrinthSummonUnitResources.decode(Json.encodeToString(LabyrinthSummonUnitDocument.serializer(), document))
            }
        }
    }

    @Test fun `bundled imprint catalog prices only the existing eight role identities and types`() {
        val root = listOf(File("src/main/assets/resource-packs/cn-bilibili"),
            File("android/app/src/main/assets/resource-packs/cn-bilibili")).first(File::isDirectory)
        val manifest = Json.parseToJsonElement(File(root, "manifest.json").readText()).jsonObject
        assertTrue(manifest["files"]!!.jsonArray.any { it.jsonPrimitive.content == LabyrinthSummonUnitResources.FILE_NAME })
        val units = LabyrinthSummonUnitResources.decode(File(root, LabyrinthSummonUnitResources.FILE_NAME).readText()).summonUnits
        assertEquals(23, units.size)
        assertEquals(mapOf(1200 to 13, 1800 to 1, 2800 to 8, 3600 to 1), units.groupingBy { it.price }.eachCount())
        val catalog = LabyrinthShopImprintCatalog(units)
        val rect = EntryPixelRect(0, 0, 1, 1)
        val recognized = units.mapNotNull { source ->
            val label = LabyrinthShopItemText.roleImprintLabel(source.name) ?: return@mapNotNull null
            val item = LabyrinthShopItemMatch("slot", rect, rect, 1.0, status = LabyrinthShopItemStatus.AVAILABLE,
                kind = LabyrinthShopItemKind.ROLE_IMPRINT, titleText = source.name, roleImprintLabel = label,
                choiceRoleImprint = source.summonType == 1)
            assertEquals(source, catalog.find(item))
            assertEquals(if (item.choiceRoleImprint) 2800 else 1200, catalog.find(item)!!.price)
            item
        }
        assertEquals(16, recognized.size)
        assertEquals(8, recognized.map { it.roleImprintLabel }.distinct().size)
        assertTrue(units.none { it.price >= LabyrinthSummonUnitResources.NON_SHOP_PRICE })
    }
}
