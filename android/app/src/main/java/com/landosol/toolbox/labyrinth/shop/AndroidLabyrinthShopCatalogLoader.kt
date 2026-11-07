package com.landosol.toolbox.labyrinth.shop

import android.content.Context
import com.landosol.toolbox.gamedata.LabyrinthSummonUnitResources

class AndroidLabyrinthShopCatalogLoader(private val context: Context) {
    fun load(): LabyrinthShopImprintCatalog {
        val path = "resource-packs/cn-bilibili/${LabyrinthSummonUnitResources.FILE_NAME}"
        val document = context.assets.open(path).bufferedReader(Charsets.UTF_8).use {
            LabyrinthSummonUnitResources.decode(it.readText())
        }
        return LabyrinthShopImprintCatalog(document.summonUnits)
    }
}
