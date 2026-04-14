package com.cobblespawnregions.gui

import com.cobblespawnregions.utils.RegionsConfig
import com.cobblespawnregions.utils.SubRegionData
import com.everlastingutils.gui.CustomGui
import com.everlastingutils.gui.InteractionContext
import com.everlastingutils.gui.setCustomName
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Region overview GUI.
 *
 * Layout:
 *   Row 0  : cyan glass bar — slot 4 opens the MAIN REGION settings hub.
 *   Rows 1-4: sub-regions — each one is clickable and opens the SUB-REGION
 *             settings hub for that specific sub. They share the same GUI
 *             stack; only the scope (subRegionId) differs.
 *   Row 5  : filler + back button (slot 49).
 */
object RegionEditorGui {

    private const val REGION_SETTINGS_SLOT = 4
    private const val BACK_SLOT            = 49
    private const val SUB_REGION_START     = 9
    private const val SUB_REGION_END       = 44

    private object Textures {
        const val SETTINGS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTdiMjE4OTMwMGYzMzliYTA1MGUwMWFlMmE1NDBiN2U4OWVmODk2YTU1Yzc5MTZkY2M5ZTU4NTFhZjg2NDExZSJ9fX0="
        const val BACK     = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        const val SUB      = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String) {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            player.sendMessage(Text.literal("§c[CSR] Region '$regionId' not found."), false)
            return
        }
        CustomGui.openGui(
            player,
            "Region: ${region.regionName}",
            buildLayout(regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            {}
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        val region = RegionsConfig.getRegion(regionId) ?: return

        when (ctx.slotIndex) {
            // Main region settings hub (subRegionId = null)
            REGION_SETTINGS_SLOT -> RegionSettingsGui.open(player, regionId, subRegionId = null)

            // Back to the region list
            BACK_SLOT -> RegionListGui.open(player)

            // Sub-region slots → settings hub scoped to that sub
            in SUB_REGION_START..SUB_REGION_END -> {
                val idx = ctx.slotIndex - SUB_REGION_START
                val sub = region.subRegions.getOrNull(idx) ?: return
                RegionSettingsGui.open(player, regionId, subRegionId = sub.subRegionId)
            }
        }
    }

    private fun buildLayout(regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val region = RegionsConfig.getRegion(regionId) ?: return layout

        // Row 0 — cyan glass + main region settings button
        for (i in 0..8) layout[i] = glass()
        layout[REGION_SETTINGS_SLOT] = regionSettingsBtn(regionId)

        // Rows 1-4 — sub-regions (now clickable)
        region.subRegions.forEachIndexed { i, sub ->
            val slot = SUB_REGION_START + i
            if (slot <= SUB_REGION_END) layout[slot] = subRegionItem(sub)
        }

        // Row 5
        for (i in 45..53) layout[i] = filler()
        layout[BACK_SLOT] = backBtn()

        return layout
    }

    private fun regionSettingsBtn(regionId: String): ItemStack {
        val r = RegionsConfig.getRegion(regionId) ?: return filler()
        val restr = r.spawnRestrictions
        return CustomGui.createPlayerHeadButton(
            "RegionSettings",
            Text.literal("Region Settings").formatted(Formatting.AQUA),
            listOf(
                Text.literal("§7This region's own settings."),
                Text.literal(""),
                Text.literal("§7Natural Spawns — Disable All: ${flag(restr.disableAll)}"),
                Text.literal("§7Natural Spawns — Blocked Species: §f${restr.disallowedSpecies.size}"),
                Text.literal(""),
                Text.literal("§eClick §7to configure")
            ),
            Textures.SETTINGS
        )
    }

    private fun subRegionItem(sub: SubRegionData): ItemStack {
        val restr = sub.spawnRestrictions
        return CustomGui.createPlayerHeadButton(
            "sub_${sub.subRegionId}",
            Text.literal(sub.subRegionName).formatted(Formatting.LIGHT_PURPLE),
            listOf(
                Text.literal("§8${sub.subRegionId}"),
                Text.literal("§7(${sub.pos1.x},${sub.pos1.y},${sub.pos1.z}) → (${sub.pos2.x},${sub.pos2.y},${sub.pos2.z})"),
                Text.literal(""),
                Text.literal("§7Natural Spawns — Disable All: ${flag(restr.disableAll)}"),
                Text.literal("§7Natural Spawns — Blocked Species: §f${restr.disallowedSpecies.size}"),
                Text.literal(""),
                Text.literal("§eClick §7to configure")
            ),
            Textures.SUB
        )
    }

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back",
        Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to region list")),
        Textures.BACK
    )

    private fun flag(b: Boolean) = if (b) "§atrue" else "§cfalse"
    private fun glass()  = ItemStack(Items.CYAN_STAINED_GLASS_PANE).apply  { setCustomName(Text.literal(" ")) }
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply  { setCustomName(Text.literal(" ")) }
}