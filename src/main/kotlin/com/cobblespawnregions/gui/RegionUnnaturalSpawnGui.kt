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
        const val POKEMON = 19
        const val TIMER = 21
        const val AMOUNT = 23
        const val MAX_TOTAL = 25
        const val REQUIRE_PLAYER = 30
        const val PLAYER_RANGE = 32
        const val BACK = 49
    }

    private object Limits {
        const val MIN_TICKS = 20L
        const val MAX_TICKS = 72_000L
        const val MIN_TOTAL = 0
        const val MAX_TOTAL = 500
        const val MIN_AMOUNT = 1
        const val MAX_AMOUNT = 100
        const val MIN_RANGE = 0.0
        const val MAX_RANGE = 512.0
    }

    private object Textures {
        const val MON = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
        const val TIME = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1ZWU0MTlhZDljMDYwYzE2Y2I1M2IxZGNmZmFjOGJhY2EwYjJhMjI2NWIxYjZjN2U4ZTc4MGMzN2IxMDRjMCJ9fX0="
        const val SPAWN_AMOUNT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODg5ZWUwYjdmZWY5NTdlZDliNDY0NzU2ZTllNTYxNTQ2OGE5YzQwYzZjMGIxM2Y0NTFmMzNiNDEwMzg5MWVhYiJ9fX0="
        const val MAX_TOTAL = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZWFlMzc0ZWE3MzJiMjE3M2UyMDk5Mzc3Nzk1MDVkNjJiM2FlOWY5ZDFhNjg1MDllNjk2NjBiNWQ4YTQ0OTNiNCJ9fX0="
        const val PLAYER_RANGE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmIyMWY3ODZjOWNjMmU4MzRhYTY3OTc5NWNhNmI0ZGJlYzQ3ZDM3MWQ3MjgwMDFmOTkzNTU4MDZiZWZhMWRmMiJ9fX0="
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String) {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            player.sendMessage(Text.literal("\u00A7c[CSR] Region not found."), false)
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
            Slots.AMOUNT -> adjustAmount(player, regionId, ctx.clickType)
            Slots.MAX_TOTAL -> adjustMaxTotal(player, regionId, ctx.clickType)
            Slots.REQUIRE_PLAYER -> toggleRequirePlayer(player, regionId)
            Slots.PLAYER_RANGE -> adjustPlayerRange(player, regionId, ctx.clickType)
            Slots.BACK -> RegionEditorGui.open(player, regionId)
        }
    }

    private fun buildLayout(regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val region = RegionsConfig.getRegion(regionId) ?: return layout

        for (i in 0..8) layout[i] = glass()

        layout[Slots.POKEMON] = pokemonItem(region.selectedPokemon.size)
        layout[Slots.TIMER] = timerItem(region.spawnTimerTicks)
        layout[Slots.AMOUNT] = amountItem(region.spawnAmountPerSpawn)
        layout[Slots.MAX_TOTAL] = maxTotalItem(region.maxTotalSpawns)
        layout[Slots.REQUIRE_PLAYER] = requirePlayerItem(region.requirePlayerInRange)
        layout[Slots.PLAYER_RANGE] = playerRangeItem(region.playerActivationRange)
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

    private fun adjustAmount(player: ServerPlayerEntity, regionId: String, clickType: ClickType) {
        val delta = if (clickType == ClickType.RIGHT) -1 else 1
        RegionsConfig.updateRegion(regionId) {
            it.spawnAmountPerSpawn = (it.spawnAmountPerSpawn + delta).coerceIn(Limits.MIN_AMOUNT, Limits.MAX_AMOUNT)
        }
        refresh(player, regionId)
    }

    private fun toggleRequirePlayer(player: ServerPlayerEntity, regionId: String) {
        RegionsConfig.updateRegion(regionId) {
            it.requirePlayerInRange = !it.requirePlayerInRange
        }
        refresh(player, regionId)
    }

    private fun adjustPlayerRange(player: ServerPlayerEntity, regionId: String, clickType: ClickType) {
        val delta = if (clickType == ClickType.RIGHT) -8.0 else 8.0
        RegionsConfig.updateRegion(regionId) {
            it.playerActivationRange = (it.playerActivationRange + delta).coerceIn(Limits.MIN_RANGE, Limits.MAX_RANGE)
        }
        refresh(player, regionId)
    }

    private fun pokemonItem(count: Int) = CustomGui.createPlayerHeadButton(
        "SelectPokemon",
        Text.literal("Configured Pokemon").formatted(Formatting.LIGHT_PURPLE),
        listOf(
            Text.literal("\u00A77Pokemon this region spawns"),
            Text.literal("\u00A77where it controls the position."),
            Text.literal(""),
            Text.literal("\u00A77Configured: \u00A7f$count"),
            Text.literal(""),
            Text.literal("\u00A7eClick \u00A77to manage")
        ),
        Textures.MON
    )

    private fun timerItem(ticks: Long) = CustomGui.createPlayerHeadButton(
        "SpawnTimer",
        Text.literal("Spawn Timer").formatted(Formatting.YELLOW),
        listOf(
            Text.literal("\u00A77Custom spawn interval."),
            Text.literal(""),
            Text.literal("\u00A7eCurrent: \u00A7f$ticks ticks \u00A78(${ticks / 20.0}s)"),
            Text.literal("\u00A77Left-click: \u00A7a+20 ticks"),
            Text.literal("\u00A77Right-click: \u00A7c-20 ticks")
        ),
        Textures.TIME
    )

    private fun maxTotalItem(total: Int) = CustomGui.createPlayerHeadButton(
        "MaxTotalSpawns",
        Text.literal("Max Total Spawns").formatted(Formatting.AQUA),
        listOf(
            Text.literal("\u00A77Max custom-spawned Pokemon alive."),
            Text.literal(""),
            Text.literal("\u00A7eCurrent: \u00A7f$total \u00A78(0 = unlimited)"),
            Text.literal("\u00A77Left-click: \u00A7a+1"),
            Text.literal("\u00A77Right-click: \u00A7c-1")
        ),
        Textures.SPAWN_AMOUNT
    )

    private fun amountItem(amount: Int) = CustomGui.createPlayerHeadButton(
        "SpawnAmount",
        Text.literal("Spawn Amount").formatted(Formatting.GREEN),
        listOf(
            Text.literal("\u00A77Pokemon attempted each timer cycle."),
            Text.literal(""),
            Text.literal("\u00A7eCurrent: \u00A7f$amount"),
            Text.literal("\u00A77Left-click: \u00A7a+1"),
            Text.literal("\u00A77Right-click: \u00A7c-1")
        ),
        Textures.MAX_TOTAL
    )

    private fun requirePlayerItem(enabled: Boolean): ItemStack {
        return ItemStack(if (enabled) Items.LIME_CONCRETE else Items.RED_CONCRETE).apply {
            setCustomName(
                Text.literal("Require Player Nearby: ").formatted(Formatting.WHITE)
                    .append(
                        if (enabled) Text.literal("ON").formatted(Formatting.GREEN, Formatting.BOLD)
                        else Text.literal("OFF").formatted(Formatting.RED, Formatting.BOLD)
                    )
            )
            CustomGui.setItemLore(this, listOf(
                "\u00A77When ON, custom spawning pauses",
                "\u00A77unless a player is near this region.",
                "",
                "\u00A7eClick \u00A77to toggle"
            ))
        }
    }

    private fun playerRangeItem(range: Double) = CustomGui.createPlayerHeadButton(
        "PlayerActivationRange",
        Text.literal("Player Range").formatted(Formatting.YELLOW),
        listOf(
            Text.literal("\u00A77Range around the region bounds."),
            Text.literal(""),
            Text.literal("\u00A7eCurrent: \u00A7f${range.toInt()} blocks"),
            Text.literal("\u00A77Left-click: \u00A7a+8"),
            Text.literal("\u00A77Right-click: \u00A7c-8")
        ),
        Textures.PLAYER_RANGE
    )

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back",
        Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("\u00A77Return to region settings")),
        Textures.BACK
    )

    private fun glass() = ItemStack(Items.CYAN_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
