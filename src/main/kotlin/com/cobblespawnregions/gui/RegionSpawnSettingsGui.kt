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
 * Spawn Settings GUI — timer + max total spawns.
 *
 * Layout (54 slots):
 *   Row 0  (0–8)  : cyan glass header
 *
 *   Row 2 — Spawn Timer controls, centred on slot 22
 *     19=-100  20=-10  21=-1  [22=DISPLAY]  23=+1  24=+10  25=+100
 *
 *   Row 3 — Max Total Spawns controls, centred on slot 31
 *     29=-10  30=-1  [31=DISPLAY]  32=+1  33=+10
 *
 *   Row 5, slot 49: Back → RegionUnnaturalSpawnGui
 */
object RegionSpawnSettingsGui {

    // Timer row
    private const val T_DEC_100  = 19
    private const val T_DEC_10   = 20
    private const val T_DEC_1    = 21
    private const val T_DISPLAY  = 22
    private const val T_INC_1    = 23
    private const val T_INC_10   = 24
    private const val T_INC_100  = 25

    // Max total spawns row
    private const val M_DEC_10   = 29
    private const val M_DEC_1    = 30
    private const val M_DISPLAY  = 31
    private const val M_INC_1    = 32
    private const val M_INC_10   = 33

    private const val BACK_SLOT  = 49

    private const val MIN_TICKS  = 20L
    private const val MAX_TICKS  = 72_000L
    private const val MIN_TOTAL  = 0
    private const val MAX_TOTAL  = 500

    private object Textures {
        const val DEC  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val INC  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val TIME = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1ZWU0MTlhZDljMDYwYzE2Y2I1M2IxZGNmZmFjOGJhY2EwYjJhMjI2NWIxYjZjN2U4ZTc4MGMzN2IxMDRjMCJ9fX0="
        const val MON  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    // ── Open ──────────────────────────────────────────────────────────────────

    fun open(player: ServerPlayerEntity, regionId: String) {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            player.sendMessage(Text.literal("§c[CSR] Region not found."), false)
            return
        }
        CustomGui.openGui(
            player,
            "${region.regionName} — Spawn Settings",
            buildLayout(regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            {}
        )
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        when (ctx.slotIndex) {
            T_DEC_100  -> adjustTimer(player, regionId, -100L)
            T_DEC_10   -> adjustTimer(player, regionId, -10L)
            T_DEC_1    -> adjustTimer(player, regionId, -1L)
            T_INC_1    -> adjustTimer(player, regionId, +1L)
            T_INC_10   -> adjustTimer(player, regionId, +10L)
            T_INC_100  -> adjustTimer(player, regionId, +100L)
            T_DISPLAY  -> if (ctx.clickType == ClickType.LEFT ) setTimer(player, regionId, 200L)

            M_DEC_10   -> adjustMaxTotal(player, regionId, -10)
            M_DEC_1    -> adjustMaxTotal(player, regionId, -1)
            M_INC_1    -> adjustMaxTotal(player, regionId, +1)
            M_INC_10   -> adjustMaxTotal(player, regionId, +10)

            BACK_SLOT  -> RegionUnnaturalSpawnGui.open(player, regionId)
        }
    }

    // ── Timer helpers ─────────────────────────────────────────────────────────

    private fun adjustTimer(player: ServerPlayerEntity, regionId: String, delta: Long) {
        val region = RegionsConfig.getRegion(regionId) ?: return
        setTimer(player, regionId, (region.spawnTimerTicks + delta).coerceIn(MIN_TICKS, MAX_TICKS))
    }

    private fun setTimer(player: ServerPlayerEntity, regionId: String, ticks: Long) {
        RegionsConfig.updateRegion(regionId) { it.spawnTimerTicks = ticks }
        CustomGui.refreshGui(player, buildLayout(regionId))
    }

    // ── Max total helpers ─────────────────────────────────────────────────────

    private fun adjustMaxTotal(player: ServerPlayerEntity, regionId: String, delta: Int) {
        val region = RegionsConfig.getRegion(regionId) ?: return
        val next = (region.maxTotalSpawns + delta).coerceIn(MIN_TOTAL, MAX_TOTAL)
        RegionsConfig.updateRegion(regionId) { it.maxTotalSpawns = next }
        CustomGui.refreshGui(player, buildLayout(regionId))
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val region = RegionsConfig.getRegion(regionId)
        val ticks  = region?.spawnTimerTicks ?: 200L
        val total  = region?.maxTotalSpawns  ?: 20

        for (i in 0..8) layout[i] = glass()

        // Row 2 — timer
        layout[T_DEC_100] = adjBtn("§c-100", Textures.DEC)
        layout[T_DEC_10]  = adjBtn("§c-10",  Textures.DEC)
        layout[T_DEC_1]   = adjBtn("§c-1",   Textures.DEC)
        layout[T_DISPLAY] = timerDisplayBtn(ticks)
        layout[T_INC_1]   = adjBtn("§a+1",   Textures.INC)
        layout[T_INC_10]  = adjBtn("§a+10",  Textures.INC)
        layout[T_INC_100] = adjBtn("§a+100", Textures.INC)

        // Row 3 — max total spawns
        layout[M_DEC_10]  = adjBtn("§c-10",  Textures.DEC)
        layout[M_DEC_1]   = adjBtn("§c-1",   Textures.DEC)
        layout[M_DISPLAY] = maxTotalDisplayBtn(total)
        layout[M_INC_1]   = adjBtn("§a+1",   Textures.INC)
        layout[M_INC_10]  = adjBtn("§a+10",  Textures.INC)

        layout[BACK_SLOT] = backBtn()
        return layout
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private fun timerDisplayBtn(ticks: Long) = CustomGui.createPlayerHeadButton(
        "SpawnTimer",
        Text.literal("Spawn Timer").formatted(Formatting.YELLOW),
        listOf(
            Text.literal("§7How often this region attempts"),
            Text.literal("§7to spawn its configured Pokémon."),
            Text.literal(""),
            Text.literal("§eCurrent: §f$ticks ticks §8(${ticks / 20.0}s)"),
            Text.literal("§8Min: 20 (1s)  •  Max: 72 000 (1h)"),
            Text.literal(""),
            Text.literal("§8Shift+Left-click to reset to §f200 §8ticks.")
        ),
        Textures.TIME
    )

    private fun maxTotalDisplayBtn(total: Int) = CustomGui.createPlayerHeadButton(
        "MaxTotalSpawns",
        Text.literal("Max Total Spawns").formatted(Formatting.AQUA),
        listOf(
            Text.literal("§7Max Pokémon from this region alive"),
            Text.literal("§7at the same time."),
            Text.literal(""),
            Text.literal("§eCurrent: §f$total §8(0 = unlimited)")
        ),
        Textures.MON
    )

    private fun adjBtn(label: String, texture: String) = CustomGui.createPlayerHeadButton(
        "Adj${label.replace("§c", "").replace("§a", "")}",
        Text.literal(label).styled { it.withItalic(false) },
        emptyList(),
        texture
    )

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back", Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to Unnatural Spawns")), Textures.BACK
    )

    private fun glass()  = ItemStack(Items.CYAN_STAINED_GLASS_PANE).apply  { setCustomName(Text.literal(" ")) }
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply  { setCustomName(Text.literal(" ")) }
}