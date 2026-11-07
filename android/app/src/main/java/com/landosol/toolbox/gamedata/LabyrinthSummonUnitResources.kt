package com.landosol.toolbox.gamedata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only shop-listed summon items belong here; reward-only sentinel prices are not sale prices. */
@Serializable
data class LabyrinthSummonUnitResource(
    val id: String = "",
    val name: String = "",
    val summonType: Int = 0,
    val summonNum: Int = 0,
    val price: Int = 0,
    val description: String = "",
)

@Serializable
data class LabyrinthSummonUnitDocument(
    val schemaVersion: Int = 1,
    val summonUnits: List<LabyrinthSummonUnitResource> = emptyList(),
)

/** Shared validation for installed resource packs and the production Android asset loader. */
object LabyrinthSummonUnitResources {
    const val FILE_NAME = "labyrinth-summon-units.json"
    const val NON_SHOP_PRICE = 99_999_999
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): LabyrinthSummonUnitDocument =
        json.decodeFromString<LabyrinthSummonUnitDocument>(text).also { document ->
            val reasons = validate(document)
            require(reasons.isEmpty()) { reasons.joinToString("；") }
        }

    fun validate(document: LabyrinthSummonUnitDocument): List<String> = buildList {
        if (document.schemaVersion != 1) add("不支持的印记资源 Schema 版本：${document.schemaVersion}")
        if (document.summonUnits.isEmpty()) add("商店印记资源为空")
        val ids = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        document.summonUnits.forEach { unit ->
            if (unit.id.toLongOrNull()?.let { it > 0 } != true) add("印记 ID 无效：${unit.id}")
            if (!ids.add(unit.id)) add("印记存在重复 ID：${unit.id}")
            if (unit.name.isBlank()) add("印记 ${unit.id} 名称为空")
            if (!names.add(unit.name.filterNot(Char::isWhitespace))) add("印记存在重复名称：${unit.name}")
            if (unit.summonType !in setOf(1, 3)) add("印记 ${unit.id} 召唤类型不是选择或随机")
            if (unit.summonNum != 1) add("商店印记 ${unit.id} 召唤数量必须为 1")
            if (unit.price !in 1 until NON_SHOP_PRICE) add("印记 ${unit.id} 不是有效上架价格")
        }
    }
}
