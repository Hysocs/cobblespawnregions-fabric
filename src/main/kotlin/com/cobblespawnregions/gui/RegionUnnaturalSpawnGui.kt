package com.cobblespawnregions.gui

import com.cobblespawnregions.utils.RegionsConfig
import com.everlastingutils.gui.CustomGui
import com.everlastingutils.gui.InteractionContext
import com.everlastingutils.gui.setCustomName
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ClickType
import net.minecraft.util.Formatting

/**
 * Custom/unnatural spawn settings for a priority region.
 */
object RegionUnnaturalSpawnGui {

    private object Slots {
        const val POKEMON = 20
        const val TIMER = 22
        const val MAX_TOTAL = 24
        const val BACK = 49
    }

    private object Limits {
        const val MIN_TICKS = 20L
        const val MAX_TICKS = 72_000L
        const val MIN_TOTAL = 0
        const val MAX_TOTAL = 500
    }

    private object Textures {
        const val MON = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
        const val TIME = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1ZWU0MTlhZDljMDYwYzE2Y2I1M2IxZGNmZmFjOGJhY2EwYjJhMjI2NWIxYjZjN2U4ZTc4MGMzN2IxMDRjMCJ9fX0="
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String) {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            player.sendMessage(Text.literal("§c[CSR] Region not found."), false)
            return
        }
        CustomGui.openGui(
            player,
            "Custom Spawns - ${region.regionName}",
            buildLayout(regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            {}
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        when (ctx.slotIndex) {
            Slots.POKEMON -> RegionPokemonSelectionGui.open(player, regionId)
            Slots.TIMER -> adjustTimer(player, regionId, ctx.clickType)
            Slots.MAX_TOTAL -> adjustMaxTotal(player, regionId, ctx.clickType)
            Slots.BACK -> RegionEditorGui.open(player, regionId)
        }
    }

    private fun buildLayout(regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val region = RegionsConfig.getRegion(regionId) ?: return layout

        for (i in 0..8) layout[i] = glass()

        layout[Slots.POKEMON] = pokemonItem(region.selectedPokemon.size)
        layout[Slots.TIMER] = timerItem(region.spawnTimerTicks)
        layout[Slots.MAX_TOTAL] = maxTotalItem(region.maxTotalSpawns)
        layout[Slots.BACK] = backBtn()
        return layout
    }

    private fun refresh(player: ServerPlayerEntity, regionId: String) {
        CustomGui.refreshGui(player, buildLayout(regionId))
    }

    private fun adjustTimer(player: ServerPlayerEntity, regionId: String, clickType: ClickType) {
        val delta = when (clickType) {
            ClickType.RIGHT -> -20L
            else -> 20L
        }
        RegionsConfig.updateRegion(regionId) {
            it.spawnTimerTicks = (it.spawnTimerTicks + delta).coerceIn(Limits.MIN_TICKS, Limits.MAX_TICKS)
        }
        refresh(player, regionId)
    }

    private fun adjustMaxTotal(player: ServerPlayerEntity, regionId: String, clickType: ClickType) {
        val delta = when (clickType) {
            ClickType.RIGHT -> -1
            else -> 1
        }
        RegionsConfig.updateRegion(regionId) {
            it.maxTotalSpawns = (it.maxTotalSpawns + delta).coerceIn(Limits.MIN_TOTAL, Limits.MAX_TOTAL)
        }
        refresh(player, regionId)
    }

    private fun pokemonItem(count: Int) = CustomGui.createPlayerHeadButton(
        "SelectPokemon",
        Text.literal("Configured Pokemon").formatted(Formatting.LIGHT_PURPLE),
        listOf(
            Text.literal("§7Pokemon this region spawns"),
            Text.literal("§7where it controls the position."),
            Text.literal(""),
            Text.literal("§7Configured: §f$count"),
            Text.literal(""),
            Text.literal("§eClick §7to manage")
        ),
        Textures.MON
    )

    private fun timerItem(ticks: Long) = CustomGui.createPlayerHeadButton(
        "SpawnTimer",
        Text.literal("Spawn Timer").formatted(Formatting.YELLOW),
        listOf(
            Text.literal("§7Custom spawn interval."),
            Text.literal(""),
            Text.literal("§eCurrent: §f$ticks ticks §8(${ticks / 20.0}s)"),
            Text.literal("§7Left-click: §a+20 ticks"),
            Text.literal("§7Right-click: §c-20 ticks")
        ),
        Textures.TIME
    )

    private fun maxTotalItem(total: Int) = CustomGui.createPlayerHeadButton(
        "MaxTotalSpawns",
        Text.literal("Max Total Spawns").formatted(Formatting.AQUA),
        listOf(
            Text.literal("§7Max custom-spawned Pokemon alive."),
            Text.literal(""),
            Text.literal("§eCurrent: §f$total §8(0 = unlimited)"),
            Text.literal("§7Left-click: §a+1"),
            Text.literal("§7Right-click: §c-1")
        ),
        Textures.MON
    )

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back",
        Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to region settings")),
        Textures.BACK
    )

    private fun glass() = ItemStack(Items.CYAN_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
