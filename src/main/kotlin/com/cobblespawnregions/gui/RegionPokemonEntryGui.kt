package com.cobblespawnregions.gui

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblespawnregions.gui.pokemonsettings.RegionPokemonSettingsGui
import com.cobblespawnregions.utils.RegionEntityTracker
import com.cobblespawnregions.utils.RegionWanderingGoalManager
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
import org.joml.Vector4f

object RegionPokemonEntryGui {

    private object Slots {
        const val MON_DISPLAY = 4

        const val SPAWN_LEVEL = 10
        const val IVS = 11
        const val EVS = 12
        const val SIZE = 13
        const val MOVES = 14
        const val CAPTURE = 15
        const val OTHER = 16

        const val BLOCKS = 21
        const val MAX_COUNT = 22
        const val WANDER_TOGGLE = 23

        const val WANDER_TARGET = 30
        const val WANDER_SPEED = 31
        const val WANDER_DELAY = 32

        const val BACK = 49
    }

    private object Textures {
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        const val SPAWN_BLOCKS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTNlOTlhNmZkMDQ2NGUwNjhjZDY5ZjNmZGRkMDNiYmFiOTA5YWNlNGY5YzNjNmFmYTFmOTQ3ZWNmODVjMjRmYiJ9fX0="
        const val RETURN_TARGET = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTJiOWFiZmM4ODE4MzUzNWE1ZGUwNjcxNTY3ZGJhMGY3ZmM4YzI3MzM4OGVmN2FjMjhiNmRjMzBiZDUxZmI3In19fQ=="
        const val MAX_COUNT_ICON = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWZhMWM2YzdlYWQ3NWEwNDU4NTM5NWY2MzEzNWRjOTZmYTA3OGZiOTIwNDg0Njk5ZWY4ZTU2NGUxNDJkNjRjYiJ9fX0="
        const val RETURN_SPEED = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGExZDU1YjNmOTg5NDEwYTM0NzUyNjUwZTI0OGM5YjZjMTc4M2E3ZWMyYWEzZmQ3Nzg3YmRjNGQwZTYzN2QzOSJ9fX0="
        const val STAY_IN_REGION = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2VkMWFiYTczZjYzOWY0YmM0MmJkNDgxOTZjNzE1MTk3YmUyNzEyYzNiOTYyYzk3ZWJmOWU5ZWQ4ZWZhMDI1In19fQ=="
        const val DELAY = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2NjNmUxMGRiNDBiNGU4MzM0MTdkZmQ1NzZiOWE4MGZhMzY2NjI1MTFhMmY2Y2U0Y2IwY2YyZWY3NmI3N2ZlMyJ9fX0="
        const val IV = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDg4M2Q2NTZlNDljMzhjNmI1Mzc4NTcyZjMxYzYzYzRjN2E1ZGQ0Mzc1YjZlY2JjYTQzZjU5NzFjMmNjNGZmIn19fQ=="
        const val EV = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODM0NTI5NjRmMWNiYjg5MTQ2Njg0YWE1NTYzOTBhOThjZjM0MmNhOTdjZWZhNmE5Mjk0YTVkMzZlZGQ5MzBmOSJ9fX0="
        const val SPAWN = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjdkNmJlMWRjYTUzNTJhNTY5M2UyOWVhMzVkODA2YjJhMjdjNGE5N2I2NGVlYmJmNjMyYzk5OGQ1OTQ4ZjFjNCJ9fX0="
        const val SIZE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMmI5MmFiZWI0NGMzNGI5OThhMDE4ZWM1YjYwMjJlOGZjMTU4ZWU4YjEzNDA0YzBmZTZkZDA5MTdmZWQ4NDRlYiJ9fX0="
        const val CAPTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTY0YzQ0ODZmOTIzNmY5YTFmYjRiMjFiZjgyM2M1NTZkNmUxNWJmNjg4Yzk2ZDZlZjBkMTc1NTNkYjUwNWIifX19"
        const val OTHER = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWEwMWQxNTZiMTcyMTVjZWYzMzZhZjRjNDRlNmNjOGNjYjI4NWZiMDViYzNmZWI2MmQzMzdmZWIxZjA5MjkwYSJ9fX0="
        const val MOVES = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzJlYmJkYjE4ZDc0NzI4MWI1NDYyZjg1N2VlOTg0Njc1YTM5ZDVhMDI3NDQ0NmEyMmY2NjI2NGE1M2QyYjAzNCJ9fX0="
    }

    private const val MIN_COUNT = 0
    private const val MAX_COUNT = 100
    private const val MIN_DELAY = 1
    private const val MAX_DELAY = 200
    private const val MIN_SPEED = 0.1
    private const val MAX_SPEED = 4.0

    fun open(
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>
    ) {
        if (RegionsConfig.getPokemonFromRegion(regionId, pokemonName, formName, aspects) == null) {
            player.sendMessage(Text.literal("CSR entry not found."), false)
            RegionPokemonSelectionGui.open(player, regionId)
            return
        }

        CustomGui.openGui(
            player,
            "${buildDisplayName(pokemonName, formName, aspects)} Settings",
            buildLayout(regionId, pokemonName, formName, aspects),
            { ctx -> handleClick(ctx, player, regionId, pokemonName, formName, aspects) },
            {}
        )
    }

    private fun handleClick(
        ctx: InteractionContext,
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>
    ) {
        when (ctx.slotIndex) {
            Slots.SPAWN_LEVEL -> RegionPokemonSettingsGui.openSpawnLevel(player, regionId, pokemonName, formName, aspects)
            Slots.IVS -> RegionPokemonSettingsGui.openIvs(player, regionId, pokemonName, formName, aspects)
            Slots.EVS -> RegionPokemonSettingsGui.openEvs(player, regionId, pokemonName, formName, aspects)
            Slots.SIZE -> RegionPokemonSettingsGui.openSize(player, regionId, pokemonName, formName, aspects)
            Slots.MOVES -> RegionPokemonSettingsGui.openMoves(player, regionId, pokemonName, formName, aspects)
            Slots.CAPTURE -> RegionPokemonSettingsGui.openCapture(player, regionId, pokemonName, formName, aspects)
            Slots.OTHER -> RegionPokemonSettingsGui.openOther(player, regionId, pokemonName, formName, aspects)
            Slots.BLOCKS -> RegionSpawnBlocksGui.open(player, regionId, pokemonName, formName, aspects)

            Slots.MAX_COUNT -> {
                val delta = if (ctx.clickType == ClickType.RIGHT) -1 else 1
                adjustMaxSpawnCount(player, regionId, pokemonName, formName, aspects, delta)
            }

            Slots.WANDER_TOGGLE -> updateWandering(player, regionId, pokemonName, formName, aspects) {
                it.enabled = !it.enabled
            }
            Slots.WANDER_TARGET -> updateWandering(player, regionId, pokemonName, formName, aspects) {
                it.returnTarget = when (it.returnTarget.uppercase()) {
                    "RANDOM" -> "CENTER"
                    "CENTER" -> "CLOSEST"
                    else -> "RANDOM"
                }
            }
            Slots.WANDER_SPEED -> updateWandering(player, regionId, pokemonName, formName, aspects) {
                val delta = if (ctx.clickType == ClickType.RIGHT) -0.1 else 0.1
                it.speed = roundOneDecimal((it.speed + delta).coerceIn(MIN_SPEED, MAX_SPEED))
            }
            Slots.WANDER_DELAY -> updateWandering(player, regionId, pokemonName, formName, aspects) {
                val delta = if (ctx.clickType == ClickType.RIGHT) -1 else 1
                it.tickDelay = (it.tickDelay + delta).coerceIn(MIN_DELAY, MAX_DELAY)
            }

            Slots.BACK -> RegionPokemonSelectionGui.open(player, regionId)
        }
    }

    private fun adjustMaxSpawnCount(
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>,
        delta: Int
    ) {
        RegionsConfig.updatePokemonInRegion(regionId, pokemonName, formName, aspects) { entry ->
            entry.maxSpawnCount = (entry.maxSpawnCount + delta).coerceIn(MIN_COUNT, MAX_COUNT)
        }
        refresh(player, regionId, pokemonName, formName, aspects)
    }

    private fun updateWandering(
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>,
        update: (com.cobblespawnregions.utils.RegionWanderingSettings) -> Unit
    ) {
        val entry = RegionsConfig.updatePokemonInRegion(regionId, pokemonName, formName, aspects) { entry ->
            update(entry.wanderingSettings)
        }
        if (entry?.wanderingSettings?.enabled == true) {
            RegionWanderingGoalManager.attachLoadedForEntry(player.server, regionId, RegionEntityTracker.entryKey(entry))
        }
        refresh(player, regionId, pokemonName, formName, aspects)
    }

    private fun refresh(player: ServerPlayerEntity, regionId: String, pokemonName: String, formName: String?, aspects: Set<String>) {
        CustomGui.refreshGui(player, buildLayout(regionId, pokemonName, formName, aspects))
    }

    private fun buildLayout(regionId: String, pokemonName: String, formName: String?, aspects: Set<String>): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val entry = RegionsConfig.getPokemonFromRegion(regionId, pokemonName, formName, aspects)
        for (i in 0..8) layout[i] = purpleGlass()

        layout[Slots.MON_DISPLAY] = monDisplayItem(pokemonName, formName, aspects)
        layout[Slots.SPAWN_LEVEL] = menuButton("Spawn / Level", Formatting.DARK_AQUA, listOf("Edit spawn chance, chance type, and levels."), Textures.SPAWN)
        layout[Slots.IVS] = menuButton("IVs", Formatting.GREEN, listOf("Edit custom IV ranges."), Textures.IV)
        layout[Slots.EVS] = menuButton("EVs", Formatting.BLUE, listOf("Edit EVs awarded when defeated."), Textures.EV)
        layout[Slots.SIZE] = menuButton("Size", Formatting.GOLD, listOf("Edit min/max spawned scale."), Textures.SIZE)
        layout[Slots.MOVES] = menuButton("Moves", Formatting.YELLOW, listOf("Edit initial move selection."), Textures.MOVES)
        layout[Slots.CAPTURE] = menuButton("Capture", Formatting.AQUA, listOf("Edit catchability and allowed balls."), Textures.CAPTURE)
        layout[Slots.OTHER] = menuButton("Time / Weather", Formatting.LIGHT_PURPLE, listOf("Edit time and weather spawn rules."), Textures.OTHER)

        layout[Slots.BLOCKS] = spawnBlocksBtn(entry)
        layout[Slots.MAX_COUNT] = maxSpawnCountBtn(entry?.maxSpawnCount ?: 0, pokemonName)
        layout[Slots.WANDER_TOGGLE] = wanderToggleBtn(entry)
        layout[Slots.WANDER_TARGET] = wanderTargetBtn(entry)
        layout[Slots.WANDER_SPEED] = wanderSpeedBtn(entry)
        layout[Slots.WANDER_DELAY] = wanderDelayBtn(entry)
        layout[Slots.BACK] = backBtn()

        return layout
    }

    private fun menuButton(title: String, color: Formatting, lore: List<String>, texture: String): ItemStack =
        CustomGui.createPlayerHeadButton(
            title.replace(" ", ""),
            Text.literal(title).formatted(color),
            lore.map { Text.literal("§7$it") } + Text.literal("§eClick to edit"),
            texture
        )

    private fun monDisplayItem(pokemonName: String, formName: String?, aspects: Set<String>): ItemStack {
        return try {
            val pokemon = PokemonProperties.parse(buildPropsString(pokemonName, formName, aspects)).create()
            val item = PokemonItem.from(pokemon, tint = Vector4f(1f, 1f, 1f, 1f))
            item.setCustomName(Text.literal("§f§l${buildDisplayName(pokemonName, formName, aspects)}"))
            CustomGui.setItemLore(item, listOf(
                "§7Species: §f${pokemonName.replaceFirstChar(Char::titlecase)}",
                if (!formName.isNullOrEmpty() && !formName.equals("normal", ignoreCase = true)) "§7Form: §f$formName" else "",
                if (aspects.isNotEmpty()) "§7Aspects: §f${aspects.joinToString(", ") { it.replaceFirstChar(Char::titlecase) }}" else ""
            ).filter(String::isNotEmpty))
            item
        } catch (_: Exception) {
            filler()
        }
    }

    private fun maxSpawnCountBtn(count: Int, pokemonName: String): ItemStack =
        CustomGui.createPlayerHeadButton(
            "MaxSpawnCount",
            Text.literal("Max Spawn Count").formatted(Formatting.AQUA),
            listOf(
                Text.literal("§7Max live ${pokemonName.replaceFirstChar(Char::titlecase)} in this region."),
                Text.literal("§eCurrent: §f$count §8(0 = unlimited)"),
                Text.literal("§7Left-click: §a+1"),
                Text.literal("§7Right-click: §c-1")
            ),
            Textures.MAX_COUNT_ICON
        )

    private fun spawnBlocksBtn(entry: com.cobblespawnregions.utils.PokemonSpawnEntry?): ItemStack =
        CustomGui.createPlayerHeadButton(
            "SpawnBlocks",
            Text.literal("Spawn Blocks").formatted(Formatting.GREEN),
            listOf(
                Text.literal("§7Allowed floor blocks for this Pokemon."),
                Text.literal("§eCurrent: §f${entry?.spawnSettings?.allowedBlocks?.size ?: 0} §7block(s)"),
                Text.literal("§8(0 = any block)"),
                Text.literal("§eClick to edit")
            ),
            Textures.SPAWN_BLOCKS
        )

    private fun wanderToggleBtn(entry: com.cobblespawnregions.utils.PokemonSpawnEntry?): ItemStack =
        CustomGui.createPlayerHeadButton(
            "RegionWanderToggle",
            Text.literal("Stay In Region").formatted(Formatting.GOLD),
            listOf(
                Text.literal("§7Paths back if this Pokemon leaves its region."),
                Text.literal("§eCurrent: ${if (entry?.wanderingSettings?.enabled != false) "§aON" else "§cOFF"}"),
                Text.literal("§eClick to toggle")
            ),
            Textures.STAY_IN_REGION
        )

    private fun wanderTargetBtn(entry: com.cobblespawnregions.utils.PokemonSpawnEntry?): ItemStack =
        CustomGui.createPlayerHeadButton(
            "RegionWanderTarget",
            Text.literal("Return Target").formatted(Formatting.YELLOW),
            listOf(
                Text.literal("§7Where it paths when returning."),
                Text.literal("§eCurrent: §f${entry?.wanderingSettings?.returnTarget ?: "RANDOM"}"),
                Text.literal("§eClick to switch")
            ),
            Textures.RETURN_TARGET
        )

    private fun wanderSpeedBtn(entry: com.cobblespawnregions.utils.PokemonSpawnEntry?): ItemStack =
        CustomGui.createPlayerHeadButton(
            "RegionWanderSpeed",
            Text.literal("Return Speed").formatted(Formatting.AQUA),
            listOf(
                Text.literal("§eCurrent: §f${entry?.wanderingSettings?.speed ?: 1.0}"),
                Text.literal("§7Left-click: §a+0.1"),
                Text.literal("§7Right-click: §c-0.1")
            ),
            Textures.RETURN_SPEED
        )

    private fun wanderDelayBtn(entry: com.cobblespawnregions.utils.PokemonSpawnEntry?): ItemStack =
        CustomGui.createPlayerHeadButton(
            "RegionWanderDelay",
            Text.literal("Check Delay").formatted(Formatting.LIGHT_PURPLE),
            listOf(
                Text.literal("§eCurrent: §f${entry?.wanderingSettings?.tickDelay ?: 10} ticks"),
                Text.literal("§7Left-click: §a+1"),
                Text.literal("§7Right-click: §c-1")
            ),
            Textures.DELAY
        )

    private fun backBtn(): ItemStack =
        CustomGui.createPlayerHeadButton("Back", Text.literal("Back").formatted(Formatting.RED), listOf(Text.literal("§7Return to Pokemon list")), Textures.BACK)

    private fun buildDisplayName(pokemonName: String, formName: String?, aspects: Set<String>): String {
        val parts = mutableListOf<String>()
        if (!formName.isNullOrEmpty() && !formName.equals("normal", ignoreCase = true)) parts.add(formName)
        parts.addAll(aspects.map { it.replaceFirstChar(Char::titlecase) })
        return if (parts.isNotEmpty()) "${pokemonName.replaceFirstChar(Char::titlecase)} (${parts.joinToString(", ")})"
        else pokemonName.replaceFirstChar(Char::titlecase)
    }

    private fun buildPropsString(pokemonName: String, formName: String?, aspects: Set<String>): String =
        buildString {
            append(pokemonName.lowercase())
            if (!formName.isNullOrEmpty()
                && !formName.equals("normal", ignoreCase = true)
                && !formName.equals("default", ignoreCase = true)
            ) append(" form=${formName.lowercase()}")
            aspects.forEach { aspect ->
                if (aspect.contains("=")) append(" ${aspect.lowercase()}")
                else append(" aspect=${aspect.lowercase()}")
            }
        }

    private fun roundOneDecimal(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0
    private fun purpleGlass(): ItemStack = ItemStack(Items.PURPLE_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
    private fun filler(): ItemStack = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
