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
 * Dedicated timer-adjustment GUI.
 *
 * Layout (54 slots):
 *   Row 0  (0–8)  : cyan glass header
 *   Row 2  (18–26): seven controls centred on slot 22
 *     19 = -100   20 = -10   21 = -1   [22 = DISPLAY]   23 = +1   24 = +10   25 = +100
 *   Row 5, slot 49: Back → RegionUnnaturalSpawnGui
 *
 * Shift+Left on the display head resets the timer to 200 ticks.
 */
object RegionSpawnTimerGui {

    private const val DEC_100    = 19
    private const val DEC_10     = 20
    private const val DEC_1      = 21
    private const val DISPLAY    = 22
    private const val INC_1      = 23
    private const val INC_10     = 24
    private const val INC_100    = 25
    private const val BACK_SLOT  = 49

    private const val MIN_TICKS  = 20L
    private const val MAX_TICKS  = 72_000L

    private object Textures {
        const val DEC  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val INC  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val TIME = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1ZWU0MTlhZDljMDYwYzE2Y2I1M2IxZGNmZmFjOGJhY2EwYjJhMjI2NWIxYjZjN2U4ZTc4MGMzN2IxMDRjMCJ9fX0="
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
            "${region.regionName} — Spawn Timer",
            buildLayout(regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            {}
        )
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        when (ctx.slotIndex) {
            DEC_100   -> adjust(player, regionId, -100L)
            DEC_10    -> adjust(player, regionId, -10L)
            DEC_1     -> adjust(player, regionId, -1L)
            INC_1     -> adjust(player, regionId, +1L)
            INC_10    -> adjust(player, regionId, +10L)
            INC_100   -> adjust(player, regionId, +100L)
            DISPLAY   -> if (ctx.clickType == ClickType.LEFT) set(player, regionId, 200L)
            BACK_SLOT -> RegionUnnaturalSpawnGui.open(player, regionId)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun adjust(player: ServerPlayerEntity, regionId: String, delta: Long) {
        val region = RegionsConfig.getRegion(regionId) ?: return
        set(player, regionId, (region.spawnTimerTicks + delta).coerceIn(MIN_TICKS, MAX_TICKS))
    }

    private fun set(player: ServerPlayerEntity, regionId: String, ticks: Long) {
        RegionsConfig.updateRegion(regionId) { it.spawnTimerTicks = ticks }
        CustomGui.refreshGui(player, buildLayout(regionId))
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val ticks  = RegionsConfig.getRegion(regionId)?.spawnTimerTicks ?: 200L

        for (i in 0..8) layout[i] = glass()

        layout[DEC_100]  = adjBtn("-100 ticks §8(−5s)",   Textures.DEC)
        layout[DEC_10]   = adjBtn("-10 ticks §8(−0.5s)",  Textures.DEC)
        layout[DEC_1]    = adjBtn("-1 tick",               Textures.DEC)
        layout[DISPLAY]  = displayBtn(ticks)
        layout[INC_1]    = adjBtn("+1 tick",               Textures.INC)
        layout[INC_10]   = adjBtn("+10 ticks §8(+0.5s)",  Textures.INC)
        layout[INC_100]  = adjBtn("+100 ticks §8(+5s)",   Textures.INC)

        layout[BACK_SLOT] = backBtn()
        return layout
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private fun displayBtn(ticks: Long) = CustomGui.createPlayerHeadButton(
        "SpawnTimer",
        Text.literal("Spawn Timer").formatted(Formatting.YELLOW),
        listOf(
            Text.literal("§7How often this region attempts"),
            Text.literal("§7to spawn its configured Pokémon."),
            Text.literal(""),
            Text.literal("§eCurrent: §f$ticks ticks §8(${ticks / 20.0}s)"),
            Text.literal("§8Min: 20 ticks (1s)  •  Max: 72 000 ticks (1h)"),
            Text.literal(""),
            Text.literal("§8Shift+Left-click to reset to §f200 §8ticks.")
        ),
        Textures.TIME
    )

    private fun adjBtn(label: String, texture: String) = CustomGui.createPlayerHeadButton(
        "Adj$label",
        Text.literal(label.substringBefore("§").trim()).formatted(Formatting.WHITE),
        listOf(Text.literal("§7$label")),
        texture
    )

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back", Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to Unnatural Spawns")), Textures.BACK
    )

    private fun glass()  = ItemStack(Items.CYAN_STAINED_GLASS_PANE).apply  { setCustomName(Text.literal(" ")) }
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply  { setCustomName(Text.literal(" ")) }
}