package com.cobblespawnregions.gui

import com.cobblespawnregions.utils.RegionsConfig
import com.everlastingutils.gui.CustomGui
import com.everlastingutils.gui.InteractionContext
import com.everlastingutils.gui.setCustomName
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Intermediate settings hub — identical structure for both the main region
 * and any sub-region. The [subRegionId] parameter is what distinguishes them:
 *   null  → editing the parent region itself
 *   "xyz" → editing sub-region "xyz" inside that region
 *
 * Layout (bare-bones, expandable later):
 *   Slot 22 — Natural Spawn Restrictions
 *   Slot 49 — Back (→ RegionEditorGui)
 */
object RegionSettingsGui {

    private const val NATURAL_SPAWN_SLOT = 22
    private const val BACK_SLOT          = 49

    private object Textures {
        const val NATURAL = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTdiMjE4OTMwMGYzMzliYTA1MGUwMWFlMmE1NDBiN2U4OWVmODk2YTU1Yzc5MTZkY2M5ZTU4NTFhZjg2NDExZSJ9fX0="
        const val BACK    = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String, subRegionId: String?) {
        val label = RegionsConfig.scopeLabel(regionId, subRegionId)
        CustomGui.openGui(
            player,
            "Settings — $label",
            buildLayout(regionId, subRegionId),
            { ctx -> handleClick(ctx, player, regionId, subRegionId) },
            {}
        )
    }

    private fun handleClick(
        ctx: InteractionContext,
        player: ServerPlayerEntity,
        regionId: String,
        subRegionId: String?
    ) {
        when (ctx.slotIndex) {
            NATURAL_SPAWN_SLOT -> RegionNaturalSpawnGui.open(player, regionId, subRegionId)
            BACK_SLOT          -> RegionEditorGui.open(player, regionId)
        }
    }

    private fun buildLayout(regionId: String, subRegionId: String?): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val restr  = RegionsConfig.getRestriction(regionId, subRegionId) ?: return layout

        layout[NATURAL_SPAWN_SLOT] = naturalSpawnBtn(restr.disableAll, restr.disallowedSpecies.size)
        layout[BACK_SLOT]          = backBtn()

        return layout
    }

    private fun naturalSpawnBtn(disableAll: Boolean, blockedCount: Int) =
        CustomGui.createPlayerHeadButton(
            "NaturalSpawns",
            Text.literal("Natural Spawn Restrictions").formatted(Formatting.AQUA),
            listOf(
                Text.literal("§7Controls which Pokémon can spawn naturally"),
                Text.literal("§7in this area."),
                Text.literal(""),
                Text.literal("§7Disable All: ${flag(disableAll)}"),
                Text.literal("§7Blocked Species: §f$blockedCount"),
                Text.literal(""),
                Text.literal("§eClick §7to configure")
            ),
            Textures.NATURAL
        )

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back",
        Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to region overview")),
        Textures.BACK
    )

    private fun flag(b: Boolean) = if (b) "§atrue" else "§cfalse"
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}