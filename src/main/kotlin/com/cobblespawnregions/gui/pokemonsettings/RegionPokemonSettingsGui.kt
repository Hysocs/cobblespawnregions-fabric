package com.cobblespawnregions.gui.pokemonsettings

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.item.PokeBallItem
import com.cobblespawnregions.gui.RegionPokemonEntryGui
import com.cobblespawnregions.gui.refreshGuiSlots
import com.cobblespawnregions.utils.EVSettings
import com.cobblespawnregions.utils.IVSettings
import com.cobblespawnregions.utils.LeveledMove
import com.cobblespawnregions.utils.MovesSettings
import com.cobblespawnregions.utils.PokemonSpawnEntry
import com.cobblespawnregions.utils.RegionsConfig
import com.cobblespawnregions.utils.SizeSettings
import com.cobblespawnregions.utils.SpawnChanceType
import com.everlastingutils.gui.AnvilGuiManager
import com.everlastingutils.gui.CustomGui
import com.everlastingutils.gui.FullyModularAnvilScreenHandler
import com.everlastingutils.gui.InteractionContext
import com.everlastingutils.gui.setCustomName
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ClickType
import net.minecraft.util.Formatting
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

object RegionPokemonSettingsGui {

    private object Textures {
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        const val PREV = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val NEXT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val DISPLAY = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjZkZDg5MTlmZThmNzUwN2I0NjQxYmYzYWE3MmIwNTZlMDg1N2NjMjAyYThlNWViNjZjOWMyMWFhNzNjMzg3NiJ9fX0="
        const val ADJUST = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjZmZjBhYTQ4NTQ0N2JiOGRjZjQ1OTkyM2I0OWY5MWM0M2IwNDBiZDU2ZTYzMTVkYWE4YjZmODNiNGMzZWI1MSJ9fX0="
        const val TOGGLE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTI1YjhlZWQ1YzU2NWJkNDQwZWM0N2M3OWMyMGQ1Y2YzNzAxNjJiMWQ5YjVkZDMxMDBlZDYyODNmZTAxZDZlIn19fQ=="
        const val TOGGLE_OFF = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjNmNzliMjA3ZDYxZTEyMjUyM2I4M2Q2MTUwOGQ5OWNmYTA3OWQ0NWJmMjNkZjJhOWE1MTI3ZjkwNzFkNGIwMCJ9fX0="
        const val HP = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWRiMDJiMDQwYzM3MDE1ODkyYTNhNDNkM2IxYmZkYjJlMDFhMDJlZGNjMmY1YjgyMjUwZGNlYmYzZmY0ZjAxZSJ9fX0="
        const val ATTACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTFkMzgzNDAxZjc3YmVmZmNiOTk4YzJjZjc5YjdhZmVlMjNmMThjNDFkOGE1NmFmZmVkNzliYjU2ZTIyNjdhMyJ9fX0="
        const val DEFENSE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjU1NTFmMzRjNDVmYjE4MTFlNGNjMmZhOGVjMzcxZTQ1YmEwOTc3ZTFkMTUyMTEyMGYwZjU3NTYwZjczZjU5MCJ9fX0="
        const val SP_ATTACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzhmZTcwYjc3MzFhYzJmNWIzZDAyNmViMWFiNmE5MjNhOGM1OGI0YmY2ZDNhY2JlMTQ1YjEwYzM2ZTZjZjg5OCJ9fX0="
        const val SP_DEFENSE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvN2VhMmI1MTE4MWFlMTlkMzMzMTNjNmY0YThlOTA2NjU3MDU1NzM2MzliM2RmNzA5NTE0YmQ5NzA5ODUzMzBkZCJ9fX0="
        const val SPEED = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDcxMDEzODQxNjUyODg4OTgxNTU0OGI0NjIzZDI4ZDg2YmJiYWU1NjE5ZDY5Y2Q5ZGJjNWFkNmI0Mzc0NCJ9fX0="
    }

    private val movePages = ConcurrentHashMap<ServerPlayerEntity, Int>()
    private val ballPages = ConcurrentHashMap<ServerPlayerEntity, Int>()
    private val defaultMovesCache = ConcurrentHashMap<String, List<LeveledMove>>()

    private val availablePokeBalls: List<ItemStack> by lazy {
        Registries.ITEM.stream()
            .filter { it is PokeBallItem }
            .map { ItemStack(it) }
            .sorted(compareBy { it.name.string })
            .toList()
    }

    private data class Target(
        val regionId: String,
        val pokemonName: String,
        val formName: String?,
        val aspects: Set<String>
    )

    fun openSpawnLevel(player: ServerPlayerEntity, regionId: String, pokemonName: String, formName: String?, aspects: Set<String>) {
        val target = Target(regionId, pokemonName, formName, aspects)
        val entry = getEntry(player, target) ?: return
        CustomGui.openGui(player, "Spawn / Level: ${entry.pokemonName}", spawnLayout(entry), { ctx -> handleSpawn(ctx, player, target) }, {})
    }

    fun openIvs(player: ServerPlayerEntity, regionId: String, pokemonName: String, formName: String?, aspects: Set<String>) {
        val target = Target(regionId, pokemonName, formName, aspects)
        val entry = getEntry(player, target) ?: return
        CustomGui.openGui(player, "IVs: ${entry.pokemonName}", ivLayout(entry), { ctx -> handleIvs(ctx, player, target) }, {})
    }

    fun openEvs(player: ServerPlayerEntity, regionId: String, pokemonName: String, formName: String?, aspects: Set<String>) {
        val target = Target(regionId, pokemonName, formName, aspects)
        val entry = getEntry(player, target) ?: return
        CustomGui.openGui(player, "EVs: ${entry.pokemonName}", evLayout(entry), { ctx -> handleEvs(ctx, player, target) }, {})
    }

    fun openSize(player: ServerPlayerEntity, regionId: String, pokemonName: String, formName: String?, aspects: Set<String>) {
        val target = Target(regionId, pokemonName, formName, aspects)
        val entry = getEntry(player, target) ?: return
        CustomGui.openGui(player, "Size: ${entry.pokemonName}", sizeLayout(entry), { ctx -> handleSize(ctx, player, target) }, {})
    }

    fun openCapture(player: ServerPlayerEntity, regionId: String, pokemonName: String, formName: String?, aspects: Set<String>) {
        val target = Target(regionId, pokemonName, formName, aspects)
        val entry = getEntry(player, target) ?: return
        CustomGui.openGui(player, "Capture: ${entry.pokemonName}", captureLayout(entry), { ctx -> handleCapture(ctx, player, target) }, {})
    }

    fun openOther(player: ServerPlayerEntity, regionId: String, pokemonName: String, formName: String?, aspects: Set<String>) {
        val target = Target(regionId, pokemonName, formName, aspects)
        val entry = getEntry(player, target) ?: return
        CustomGui.openGui(player, "Time / Weather: ${entry.pokemonName}", otherLayout(entry), { ctx -> handleOther(ctx, player, target) }, {})
    }

    fun openMoves(player: ServerPlayerEntity, regionId: String, pokemonName: String, formName: String?, aspects: Set<String>) {
        val target = Target(regionId, pokemonName, formName, aspects)
        val entry = getEntry(player, target) ?: return
        cacheDefaultMoves(entry.pokemonName)
        val page = movePages.getOrDefault(player, 0)
        CustomGui.openGui(
            player,
            "Moves: ${entry.pokemonName}",
            movesLayout(player, entry, page),
            { ctx -> handleMoves(ctx, player, target) },
            { movePages.remove(player) }
        )
    }

    private fun openCaptureBalls(player: ServerPlayerEntity, target: Target) {
        val entry = getEntry(player, target) ?: return
        val page = ballPages.getOrDefault(player, 0)
        CustomGui.openGui(
            player,
            "Allowed Balls: ${entry.pokemonName}",
            captureBallsLayout(entry.captureSettings.requiredPokeBalls, page),
            { ctx -> handleCaptureBalls(ctx, player, target) },
            { ballPages.remove(player) }
        )
    }

    private fun handleSpawn(ctx: InteractionContext, player: ServerPlayerEntity, target: Target) {
        data class ChanceButton(val left: Double, val right: Double)
        val chanceButtons = mapOf(
            10 to ChanceButton(-0.01, -0.05),
            11 to ChanceButton(-0.1, -0.5),
            12 to ChanceButton(-1.0, -5.0),
            14 to ChanceButton(0.01, 0.05),
            15 to ChanceButton(0.1, 0.5),
            16 to ChanceButton(1.0, 5.0)
        )
        val levelButtons = mapOf(
            19 to (true to -1),
            21 to (true to 1),
            23 to (false to -1),
            25 to (false to 1)
        )

        when (ctx.slotIndex) {
            in chanceButtons -> {
                val button = chanceButtons.getValue(ctx.slotIndex)
                val delta = if (ctx.clickType == ClickType.LEFT) button.left else button.right
                update(target) { it.spawnChance = (it.spawnChance + delta).coerceIn(0.0, 100.0) }
                refreshSlots(player, target, ::spawnLayout, 13)
            }
            in levelButtons -> {
                val (isMin, baseDelta) = levelButtons.getValue(ctx.slotIndex)
                val delta = if (ctx.clickType == ClickType.RIGHT) baseDelta * 5 else baseDelta
                update(target) {
                    if (isMin) it.minLevel = (it.minLevel + delta).coerceIn(1, it.maxLevel)
                    else it.maxLevel = (it.maxLevel + delta).coerceIn(it.minLevel, 100)
                }
                refreshSlots(player, target, ::spawnLayout, if (isMin) 20 else 24)
            }
            31 -> {
                update(target) {
                    it.spawnChanceType = if (it.spawnChanceType == SpawnChanceType.COMPETITIVE) SpawnChanceType.INDEPENDENT else SpawnChanceType.COMPETITIVE
                }
                refreshSlots(player, target, ::spawnLayout, 31)
            }
            49 -> back(player, target)
        }
    }

    private fun spawnLayout(entry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        listOf(10, 11, 12, 14, 15, 16).forEach { slot ->
            val positive = slot > 13
            layout[slot] = head(
                if (positive) "Increase Spawn Chance" else "Decrease Spawn Chance",
                if (positive) Formatting.GREEN else Formatting.RED,
                listOf("Left-click: ${if (positive) "+" else "-"}small", "Right-click: ${if (positive) "+" else "-"}large"),
                Textures.ADJUST
            )
        }
        layout[13] = head("Spawn Chance", Formatting.AQUA, listOf("Current: %.2f%%".format(entry.spawnChance)), Textures.DISPLAY)
        layout[19] = head("Decrease Min Level", Formatting.RED, listOf("Left-click: -1", "Right-click: -5"), Textures.ADJUST)
        layout[20] = head("Min Level", Formatting.AQUA, listOf("Current: ${entry.minLevel}"), Textures.DISPLAY)
        layout[21] = head("Increase Min Level", Formatting.GREEN, listOf("Left-click: +1", "Right-click: +5"), Textures.ADJUST)
        layout[23] = head("Decrease Max Level", Formatting.RED, listOf("Left-click: -1", "Right-click: -5"), Textures.ADJUST)
        layout[24] = head("Max Level", Formatting.AQUA, listOf("Current: ${entry.maxLevel}"), Textures.DISPLAY)
        layout[25] = head("Increase Max Level", Formatting.GREEN, listOf("Left-click: +1", "Right-click: +5"), Textures.ADJUST)
        layout[31] = head(
            "Chance Type",
            Formatting.LIGHT_PURPLE,
            listOf("Current: ${entry.spawnChanceType.name.lowercase().replaceFirstChar(Char::titlecase)}", "Click to toggle"),
            Textures.TOGGLE
        )
        layout[49] = backButton()
        return layout
    }

    private data class IvStat(
        val name: String,
        val texture: String,
        val minSlot: Int,
        val maxSlot: Int,
        val minGet: (IVSettings) -> Int,
        val minSet: (IVSettings, Int) -> Unit,
        val maxGet: (IVSettings) -> Int,
        val maxSet: (IVSettings, Int) -> Unit
    )

    private val ivStats = listOf(
        IvStat("HP", Textures.HP, 10, 11, { it.minIVHp }, { s, v -> s.minIVHp = v }, { it.maxIVHp }, { s, v -> s.maxIVHp = v }),
        IvStat("Attack", Textures.ATTACK, 13, 14, { it.minIVAttack }, { s, v -> s.minIVAttack = v }, { it.maxIVAttack }, { s, v -> s.maxIVAttack = v }),
        IvStat("Defense", Textures.DEFENSE, 16, 17, { it.minIVDefense }, { s, v -> s.minIVDefense = v }, { it.maxIVDefense }, { s, v -> s.maxIVDefense = v }),
        IvStat("Sp. Atk", Textures.SP_ATTACK, 19, 20, { it.minIVSpecialAttack }, { s, v -> s.minIVSpecialAttack = v }, { it.maxIVSpecialAttack }, { s, v -> s.maxIVSpecialAttack = v }),
        IvStat("Sp. Def", Textures.SP_DEFENSE, 22, 23, { it.minIVSpecialDefense }, { s, v -> s.minIVSpecialDefense = v }, { it.maxIVSpecialDefense }, { s, v -> s.maxIVSpecialDefense = v }),
        IvStat("Speed", Textures.SPEED, 25, 26, { it.minIVSpeed }, { s, v -> s.minIVSpeed = v }, { it.maxIVSpeed }, { s, v -> s.maxIVSpeed = v })
    )
    private val ivSlotMap = ivStats.flatMap { listOf(it.minSlot to it, it.maxSlot to it) }.toMap()

    private fun handleIvs(ctx: InteractionContext, player: ServerPlayerEntity, target: Target) {
        when (val slot = ctx.slotIndex) {
            31 -> {
                update(target) { it.ivSettings.allowCustomIvs = !it.ivSettings.allowCustomIvs }
                return refreshSlots(player, target, ::ivLayout, 31)
            }
            49 -> return back(player, target)
            in ivSlotMap -> {
                val stat = ivSlotMap.getValue(slot)
                val isMin = slot == stat.minSlot
                val delta = if (ctx.clickType == ClickType.LEFT) -1 else 1
                update(target) {
                    val ivs = it.ivSettings
                    if (isMin) stat.minSet(ivs, (stat.minGet(ivs) + delta).coerceIn(0, stat.maxGet(ivs)))
                    else stat.maxSet(ivs, (stat.maxGet(ivs) + delta).coerceIn(stat.minGet(ivs), 31))
                }
                return refreshSlots(player, target, ::ivLayout, slot)
            }
        }
    }

    private fun ivLayout(entry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        ivStats.forEach { stat ->
            layout[stat.minSlot] = head("${stat.name} Min", Formatting.WHITE, statLore(stat.minGet(entry.ivSettings)), stat.texture)
            layout[stat.maxSlot] = head("${stat.name} Max", Formatting.WHITE, statLore(stat.maxGet(entry.ivSettings)), stat.texture)
        }
        layout[31] = toggleHead("Allow Custom IVs", entry.ivSettings.allowCustomIvs)
        layout[49] = backButton()
        return layout
    }

    private data class EvStat(val name: String, val texture: String, val get: (EVSettings) -> Int, val set: (EVSettings, Int) -> Unit)
    private val evStats = mapOf(
        10 to EvStat("HP", Textures.HP, { it.evHp }, { s, v -> s.evHp = v }),
        12 to EvStat("Attack", Textures.ATTACK, { it.evAttack }, { s, v -> s.evAttack = v }),
        14 to EvStat("Defense", Textures.DEFENSE, { it.evDefense }, { s, v -> s.evDefense = v }),
        16 to EvStat("Sp. Atk", Textures.SP_ATTACK, { it.evSpecialAttack }, { s, v -> s.evSpecialAttack = v }),
        20 to EvStat("Sp. Def", Textures.SP_DEFENSE, { it.evSpecialDefense }, { s, v -> s.evSpecialDefense = v }),
        24 to EvStat("Speed", Textures.SPEED, { it.evSpeed }, { s, v -> s.evSpeed = v })
    )

    private fun handleEvs(ctx: InteractionContext, player: ServerPlayerEntity, target: Target) {
        when (val slot = ctx.slotIndex) {
            31 -> {
                update(target) { it.evSettings.allowCustomEvsOnDefeat = !it.evSettings.allowCustomEvsOnDefeat }
                return refreshSlots(player, target, ::evLayout, 31)
            }
            49 -> return back(player, target)
            in evStats -> {
                val stat = evStats.getValue(slot)
                val delta = if (ctx.clickType == ClickType.LEFT) -1 else 1
                update(target) {
                    val evs = it.evSettings
                    stat.set(evs, (stat.get(evs) + delta).coerceIn(0, 252))
                }
                return refreshSlots(player, target, ::evLayout, slot)
            }
        }
    }

    private fun evLayout(entry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        evStats.forEach { (slot, stat) ->
            layout[slot] = head("${stat.name} EV", Formatting.WHITE, statLore(stat.get(entry.evSettings)), stat.texture)
        }
        layout[31] = toggleHead("Allow Custom EVs", entry.evSettings.allowCustomEvsOnDefeat)
        layout[49] = backButton()
        return layout
    }

    private data class SizeAdjustment(val left: Float, val right: Float)
    private val sizeAdjustments = mapOf(
        11 to SizeAdjustment(-1.0f, -5.0f),
        12 to SizeAdjustment(-0.1f, -0.5f),
        14 to SizeAdjustment(0.1f, 0.5f),
        15 to SizeAdjustment(1.0f, 5.0f),
        20 to SizeAdjustment(-1.0f, -5.0f),
        21 to SizeAdjustment(-0.1f, -0.5f),
        23 to SizeAdjustment(0.1f, 0.5f),
        24 to SizeAdjustment(1.0f, 5.0f)
    )

    private fun handleSize(ctx: InteractionContext, player: ServerPlayerEntity, target: Target) {
        when (val slot = ctx.slotIndex) {
            40 -> {
                update(target) { it.sizeSettings.allowCustomSize = !it.sizeSettings.allowCustomSize }
                return refreshSlots(player, target, ::sizeLayout, 40)
            }
            49 -> return back(player, target)
            in sizeAdjustments -> {
                val adjustment = sizeAdjustments.getValue(slot)
                val delta = if (ctx.clickType == ClickType.LEFT) adjustment.left else adjustment.right
                val isMin = slot < 20
                update(target) {
                    val size = it.sizeSettings
                    val next = if (isMin) {
                        (size.minSize + delta).coerceIn(0.1f, size.maxSize)
                    } else {
                        (size.maxSize + delta).coerceIn(size.minSize, 50.0f)
                    }
                    val rounded = (next * 100).roundToInt() / 100f
                    if (isMin) size.minSize = rounded else size.maxSize = rounded
                }
                return refreshSlots(player, target, ::sizeLayout, if (isMin) 13 else 22)
            }
        }
    }

    private fun sizeLayout(entry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        sizeAdjustments.forEach { (slot, adj) ->
            val isMin = slot < 20
            val label = if (adj.left > 0) "Increase" else "Decrease"
            val type = if (isMin) "Min" else "Max"
            layout[slot] = head(
                "$label $type Size",
                if (adj.left > 0) Formatting.GREEN else Formatting.RED,
                listOf("Left-click: ${adj.left.format()}", "Right-click: ${adj.right.format()}"),
                Textures.ADJUST
            )
        }
        layout[13] = head("Min Size", Formatting.WHITE, listOf("Current: %.2f".format(entry.sizeSettings.minSize)), Textures.DISPLAY)
        layout[22] = head("Max Size", Formatting.WHITE, listOf("Current: %.2f".format(entry.sizeSettings.maxSize)), Textures.DISPLAY)
        layout[40] = toggleHead("Allow Custom Size", entry.sizeSettings.allowCustomSize)
        layout[49] = backButton()
        return layout
    }

    private fun handleCapture(ctx: InteractionContext, player: ServerPlayerEntity, target: Target) {
        when (ctx.slotIndex) {
            21 -> {
                update(target) { it.captureSettings.isCatchable = !it.captureSettings.isCatchable }
                refreshSlots(player, target, ::captureLayout, 21)
            }
            23 -> {
                if (ctx.clickType == ClickType.RIGHT) openCaptureBalls(player, target)
                else {
                    update(target) { it.captureSettings.restrictCaptureToLimitedBalls = !it.captureSettings.restrictCaptureToLimitedBalls }
                    refreshSlots(player, target, ::captureLayout, 23)
                }
            }
            49 -> back(player, target)
        }
    }

    private fun captureLayout(entry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val settings = entry.captureSettings
        layout[21] = toggleHead("Catchable", settings.isCatchable, listOf("If OFF, this Pokemon cannot be caught."))
        layout[23] = toggleHead(
            "Restrict Capture Balls",
            settings.restrictCaptureToLimitedBalls,
            listOf("If ON, only selected Poke Balls work.", "Right-click to edit ball list.")
        )
        layout[49] = backButton()
        return layout
    }

    private fun handleCaptureBalls(ctx: InteractionContext, player: ServerPlayerEntity, target: Target) {
        val page = ballPages.getOrDefault(player, 0)
        when (ctx.slotIndex) {
            45 -> if (page > 0) ballPages[player] = page - 1
            53 -> if ((page + 1) * 45 < availablePokeBalls.size) ballPages[player] = page + 1
            49 -> return openCapture(player, target.regionId, target.pokemonName, target.formName, target.aspects)
            in 0 until 45 -> {
                val ballName = (ctx.clickedStack.item as? PokeBallItem)?.let { Registries.ITEM.getId(it).path } ?: return
                update(target) {
                    val balls = it.captureSettings.requiredPokeBalls.toMutableList()
                    if (ballName in balls) balls.remove(ballName) else balls.add(ballName)
                    it.captureSettings.requiredPokeBalls = balls
                }
            }
        }
        val entry = getEntry(player, target) ?: return
        CustomGui.refreshGui(player, captureBallsLayout(entry.captureSettings.requiredPokeBalls, ballPages.getOrDefault(player, 0)))
    }

    private fun captureBallsLayout(selected: List<String>, page: Int): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        availablePokeBalls.drop(page * 45).take(45).forEachIndexed { index, stack ->
            val item = stack.copy()
            val ballName = (item.item as? PokeBallItem)?.let { Registries.ITEM.getId(it).path } ?: ""
            val isSelected = ballName in selected
            val status = if (isSelected) Text.literal("Selected").formatted(Formatting.GREEN) else Text.literal("Not Selected").formatted(Formatting.RED)
            item.set(DataComponentTypes.LORE, LoreComponent(listOf(Text.literal("Status: ").append(status))))
            if (isSelected) item.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
            layout[index] = item
        }
        layout[45] = if (page > 0) head("Previous Page", Formatting.GREEN, emptyList(), Textures.PREV) else filler()
        layout[49] = backButton()
        layout[53] = if ((page + 1) * 45 < availablePokeBalls.size) head("Next Page", Formatting.GREEN, emptyList(), Textures.NEXT) else filler()
        return layout
    }

    private val timeCycle = listOf("ALL", "DAY", "NIGHT")
    private val weatherCycle = listOf("ALL", "CLEAR", "RAIN", "THUNDER")

    private fun handleOther(ctx: InteractionContext, player: ServerPlayerEntity, target: Target) {
        when (ctx.slotIndex) {
            20 -> cycle(target, weatherCycle) { it.spawnSettings.spawnWeather = next(it.spawnSettings.spawnWeather, weatherCycle) }
            24 -> cycle(target, timeCycle) { it.spawnSettings.spawnTime = next(it.spawnSettings.spawnTime, timeCycle) }
            49 -> return back(player, target)
        }
        refreshSlots(player, target, ::otherLayout, ctx.slotIndex)
    }

    private fun otherLayout(entry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        layout[20] = head("Spawn Weather", Formatting.WHITE, listOf("Current: ${entry.spawnSettings.spawnWeather}", "Click to cycle"), Textures.DISPLAY)
        layout[24] = head("Spawn Time", Formatting.WHITE, listOf("Current: ${entry.spawnSettings.spawnTime}", "Click to cycle"), Textures.DISPLAY)
        layout[49] = backButton()
        return layout
    }

    private object MoveSlots {
        const val HELP = 4
        const val PREV = 45
        const val TOGGLE = 48
        const val BACK = 49
        const val ADD = 50
        const val NEXT = 53
        val MOVE_SLOTS = (9..44).toList()
        const val PAGE_SIZE = 36
    }

    private fun handleMoves(ctx: InteractionContext, player: ServerPlayerEntity, target: Target) {
        when (ctx.slotIndex) {
            MoveSlots.BACK -> return back(player, target)
            MoveSlots.ADD -> return openAddCustomMoveAnvil(player, target)
            MoveSlots.TOGGLE -> update(target) {
                val settings = it.moves ?: MovesSettings()
                it.moves = settings.copy(allowCustomInitialMoves = !settings.allowCustomInitialMoves)
            }
            MoveSlots.PREV -> movePages[player] = (movePages.getOrDefault(player, 0) - 1).coerceAtLeast(0)
            MoveSlots.NEXT -> movePages[player] = movePages.getOrDefault(player, 0) + 1
            in MoveSlots.MOVE_SLOTS -> handleMoveClick(ctx, player, target)
        }
        refreshMoves(player, target)
    }

    private fun handleMoveClick(ctx: InteractionContext, player: ServerPlayerEntity, target: Target) {
        val entry = getEntry(player, target) ?: return
        val page = movePages.getOrDefault(player, 0)
        val combined = combinedMoves(entry.pokemonName, entry.moves?.selectedMoves ?: emptyList())
        val clickedIndex = page * MoveSlots.PAGE_SIZE + MoveSlots.MOVE_SLOTS.indexOf(ctx.slotIndex)
        val move = combined.getOrNull(clickedIndex) ?: return

        update(target) {
            val settings = it.moves ?: MovesSettings()
            val selected = settings.selectedMoves.toMutableList()
            val existing = selected.find { selectedMove -> selectedMove.level == move.level && selectedMove.moveId.equals(move.moveId, true) }
            if (existing != null) {
                if (ctx.button == 1) selected[selected.indexOf(existing)] = existing.copy(forced = !existing.forced)
                else selected.remove(existing)
            } else {
                selected.add(move)
            }
            it.moves = settings.copy(selectedMoves = selected)
        }
    }

    private fun movesLayout(player: ServerPlayerEntity, entry: PokemonSpawnEntry, page: Int): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val settings = entry.moves ?: MovesSettings()
        val combined = combinedMoves(entry.pokemonName, settings.selectedMoves)
        val totalPages = ceil(combined.size.toDouble() / MoveSlots.PAGE_SIZE).toInt().coerceAtLeast(1)
        val currentPage = page.coerceIn(0, totalPages - 1)
        movePages[player] = currentPage

        layout[MoveSlots.HELP] = itemButton(ItemStack(Items.BOOK), "Move Selection", Formatting.GOLD, listOf("Left-click selected moves to remove.", "Right-click selected moves to force."))
        layout[MoveSlots.TOGGLE] = itemButton(
            ItemStack(if (settings.allowCustomInitialMoves) Items.LIME_CONCRETE else Items.RED_CONCRETE),
            "Custom Moves: ${if (settings.allowCustomInitialMoves) "ON" else "OFF"}",
            if (settings.allowCustomInitialMoves) Formatting.GREEN else Formatting.RED,
            listOf("Click to toggle custom initial moves.")
        )
        layout[MoveSlots.ADD] = itemButton(ItemStack(Items.WRITABLE_BOOK), "Add Custom Move", Formatting.YELLOW, listOf("Type a move id/name."))
        layout[MoveSlots.BACK] = backButton()
        if (currentPage > 0) layout[MoveSlots.PREV] = head("Previous Page", Formatting.GREEN, emptyList(), Textures.PREV)
        if (currentPage < totalPages - 1) layout[MoveSlots.NEXT] = head("Next Page", Formatting.GREEN, emptyList(), Textures.NEXT)

        combined.drop(currentPage * MoveSlots.PAGE_SIZE).take(MoveSlots.PAGE_SIZE).forEachIndexed { index, move ->
            val slot = MoveSlots.MOVE_SLOTS[index]
            val selected = settings.selectedMoves.any { it.level == move.level && it.moveId.equals(move.moveId, true) }
            layout[slot] = moveButton(move, selected, entry.pokemonName)
        }
        return layout
    }

    private fun openAddCustomMoveAnvil(player: ServerPlayerEntity, target: Target) {
        val reopen = { player.server.execute { openMoves(player, target.regionId, target.pokemonName, target.formName, target.aspects) } }
        AnvilGuiManager.openAnvilGui(
            player = player,
            id = "csr_add_custom_move_${target.regionId}_${target.pokemonName}",
            title = "Enter Move Name",
            initialText = "",
            leftItem = itemButton(ItemStack(Items.BARRIER), "Cancel", Formatting.RED),
            resultItem = itemButton(ItemStack(Items.PAPER), "Type a move name...", Formatting.GRAY),
            onLeftClick = { reopen() },
            onResultClick = { context ->
                val moveName = context.handler.currentText.trim().lowercase().replace(Regex("\\s+"), "_")
                if (moveName.isNotBlank() && Moves.getByName(moveName) != null) {
                    update(target) {
                        val settings = it.moves ?: MovesSettings()
                        val selected = settings.selectedMoves.toMutableList()
                        if (selected.none { move -> move.moveId.equals(moveName, true) }) {
                            selected.add(LeveledMove(1, moveName, false))
                            it.moves = settings.copy(selectedMoves = selected)
                        }
                    }
                    reopen()
                } else {
                    player.sendMessage(Text.literal("Invalid move name.").formatted(Formatting.RED), false)
                }
            },
            onTextChange = onTextChange@{ text ->
                val handler = player.currentScreenHandler as? FullyModularAnvilScreenHandler ?: return@onTextChange
                val button = if (text.isNotBlank()) itemButton(ItemStack(Items.PAPER), "Add: $text", Formatting.GREEN)
                else itemButton(ItemStack(Items.PAPER), "Type a move name...", Formatting.GRAY)
                handler.updateSlot(2, button)
            },
            onClose = { reopen() }
        )
    }

    private fun moveButton(move: LeveledMove, selected: Boolean, pokemonName: String): ItemStack {
        val name = move.moveId.replace("_", " ").replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        val isDefault = defaultMoves(pokemonName).any { it.moveId.equals(move.moveId, true) }
        return if (selected) {
            val suffix = if (move.forced) " (Forced)" else ""
            val item = if (isDefault) ItemStack(Items.PAPER) else ItemStack(Items.FILLED_MAP)
            itemButton(item, "${if (isDefault) "" else "[Custom] "}$name$suffix", if (move.forced) Formatting.GOLD else Formatting.WHITE, listOf("Level: ${move.level}", "Left-click to remove", "Right-click to toggle forced"))
        } else {
            itemButton(ItemStack(Items.BLUE_STAINED_GLASS_PANE), name, Formatting.AQUA, listOf("Level: ${move.level}", "Click to add"))
        }
    }

    private fun combinedMoves(pokemonName: String, selectedMoves: List<LeveledMove>): List<LeveledMove> {
        val defaults = defaultMoves(pokemonName)
        val available = defaults.filterNot { default -> selectedMoves.any { it.moveId.equals(default.moveId, true) } }
        return (selectedMoves + available).sortedWith(compareBy({ it.level }, { it.moveId }))
    }

    private fun cacheDefaultMoves(pokemonName: String) {
        defaultMovesCache.computeIfAbsent(pokemonName) {
            PokemonSpecies.getByName(pokemonName.lowercase())?.let(RegionsConfig::getDefaultInitialMoves) ?: emptyList()
        }
    }

    private fun defaultMoves(pokemonName: String): List<LeveledMove> = defaultMovesCache[pokemonName] ?: emptyList()

    private fun refreshMoves(player: ServerPlayerEntity, target: Target) {
        val entry = getEntry(player, target) ?: return
        CustomGui.refreshGui(player, movesLayout(player, entry, movePages.getOrDefault(player, 0)))
    }

    private fun cycle(target: Target, cycle: List<String>, apply: (PokemonSpawnEntry) -> Unit) {
        update(target) { apply(it) }
    }

    private fun next(current: String, cycle: List<String>): String {
        val index = cycle.indexOf(current.uppercase()).takeIf { it >= 0 } ?: 0
        return cycle[(index + 1) % cycle.size]
    }

    private fun update(target: Target, update: (PokemonSpawnEntry) -> Unit): PokemonSpawnEntry? =
        RegionsConfig.updatePokemonInRegion(target.regionId, target.pokemonName, target.formName, target.aspects, update)

    private fun getEntry(player: ServerPlayerEntity, target: Target): PokemonSpawnEntry? {
        val entry = RegionsConfig.getPokemonFromRegion(target.regionId, target.pokemonName, target.formName, target.aspects)
        if (entry == null) {
            player.sendMessage(Text.literal("CSR entry not found."), false)
            RegionPokemonEntryGui.open(player, target.regionId, target.pokemonName, target.formName, target.aspects)
        }
        return entry
    }

    private fun refresh(player: ServerPlayerEntity, target: Target, layout: (PokemonSpawnEntry) -> List<ItemStack>) {
        val entry = getEntry(player, target) ?: return
        CustomGui.refreshGui(player, layout(entry))
    }

    private fun refreshSlots(
        player: ServerPlayerEntity,
        target: Target,
        layout: (PokemonSpawnEntry) -> List<ItemStack>,
        vararg slots: Int
    ) {
        val entry = getEntry(player, target) ?: return
        val items = layout(entry)
        player.refreshGuiSlots(*slots.map { it to items[it] }.toTypedArray())
    }

    private fun back(player: ServerPlayerEntity, target: Target) {
        movePages.remove(player)
        ballPages.remove(player)
        RegionPokemonEntryGui.open(player, target.regionId, target.pokemonName, target.formName, target.aspects)
    }

    private fun statLore(value: Int): List<String> = listOf("Current: $value", "Left-click to decrease", "Right-click to increase")

    private fun toggleHead(title: String, enabled: Boolean, extraLore: List<String> = emptyList()): ItemStack {
        val status = if (enabled) "ON" else "OFF"
        val color = if (enabled) Formatting.GREEN else Formatting.RED
        return head("$title: $status", color, extraLore + listOf("Click to toggle"), if (enabled) Textures.TOGGLE else Textures.TOGGLE_OFF)
    }

    private fun head(title: String, color: Formatting, lore: List<String>, texture: String): ItemStack =
        CustomGui.createPlayerHeadButton(
            title.filter { !it.isWhitespace() },
            Text.literal(title).formatted(color),
            lore.map { Text.literal("§7$it") },
            texture
        )

    private fun itemButton(item: ItemStack, title: String, color: Formatting, lore: List<String> = emptyList()): ItemStack =
        item.apply {
            setCustomName(Text.literal(title).formatted(color))
            if (lore.isNotEmpty()) CustomGui.setItemLore(this, lore.map { Text.literal("§7$it") })
        }

    private fun backButton(): ItemStack = head("Back", Formatting.RED, listOf("Return to Pokemon settings."), Textures.BACK)
    private fun filler(): ItemStack = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
    private fun Float.format(): String = "%.1f".format(this)
}
