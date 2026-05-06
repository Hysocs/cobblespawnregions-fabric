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
 * Unnatural-spawns hub for a region.
 *
 * Layout (54 slots):
 *   Row 0  (0–8)  : cyan glass header
 *   Row 2, slot 21: Spawn Settings button  → RegionSpawnSettingsGui
 *   Row 2, slot 23: Select Pokémon button  → RegionPokemonSelectionGui
 *   Row 5, slot 49: Back                   → RegionSettingsGui
 */
object RegionUnnaturalSpawnGui {

    private const val SETTINGS_BTN = 21
    private const val POKEMON_BTN  = 23
    private const val BACK_SLOT    = 49

    private object Textures {
        const val SETTINGS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1ZWU0MTlhZDljMDYwYzE2Y2I1M2IxZGNmZmFjOGJhY2EwYjJhMjI2NWIxYjZjN2U4ZTc4MGMzN2IxMDRjMCJ9fX0="
        const val MON      = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
        const val BACK     = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String) {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            player.sendMessage(Text.literal("§c[CSR] Region not found."), false)
            return
        }
        CustomGui.openGui(
            player,
            "${region.regionName} — Unnatural Spawns",
            buildLayout(regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            {}
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        when (ctx.slotIndex) {
            SETTINGS_BTN -> RegionSpawnSettingsGui.open(player, regionId)
            POKEMON_BTN  -> RegionPokemonSelectionGui.open(player, regionId)
            BACK_SLOT    -> RegionSettingsGui.open(player, regionId, subRegionId = null)
        }
    }

    private fun buildLayout(regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val region = RegionsConfig.getRegion(regionId)
        val ticks  = region?.spawnTimerTicks ?: 200L
        val total  = region?.maxTotalSpawns  ?: 20
        val count  = region?.selectedPokemon?.size ?: 0

        for (i in 0..8) layout[i] = glass()

        layout[SETTINGS_BTN] = spawnSettingsBtn(ticks, total)
        layout[POKEMON_BTN]  = pokemonBtn(count)
        layout[BACK_SLOT]    = backBtn()

        return layout
    }

    private fun spawnSettingsBtn(ticks: Long, total: Int) = CustomGui.createPlayerHeadButton(
        "SpawnSettings",
        Text.literal("Spawn Settings").formatted(Formatting.YELLOW),
        listOf(
            Text.literal("§7Configure the spawn timer and"),
            Text.literal("§7the max Pokémon population."),
            Text.literal(""),
            Text.literal("§7Timer:      §f$ticks ticks §8(${ticks / 20.0}s)"),
            Text.literal("§7Max Spawns: §f$total §8(0 = unlimited)"),
            Text.literal(""),
            Text.literal("§eClick §7to edit")
        ),
        Textures.SETTINGS
    )

    private fun pokemonBtn(count: Int) = CustomGui.createPlayerHeadButton(
        "SelectPokemon",
        Text.literal("Select Pokémon").formatted(Formatting.LIGHT_PURPLE),
        listOf(
            Text.literal("§7Choose which Pokémon this region"),
            Text.literal("§7will spawn on its timer."),
            Text.literal(""),
            Text.literal("§7Currently configured: §f$count §7Pokémon"),
            Text.literal(""),
            Text.literal("§eLeft-click §7to select   §eRight-click §7entry to edit")
        ),
        Textures.MON
    )

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back", Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to region settings")), Textures.BACK
    )

    private fun glass()  = ItemStack(Items.CYAN_STAINED_GLASS_PANE).apply  { setCustomName(Text.literal(" ")) }
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply  { setCustomName(Text.literal(" ")) }
}