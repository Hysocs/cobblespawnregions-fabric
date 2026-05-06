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
 * Hub GUI for a region (or sub-region) scope.
 *
 * Layout (54 slots — row 0 is the header bar):
 *   Row 0  (0–8)  : cyan glass header bar
 *   Row 2  (18–26): two hub buttons, symmetrical around the centre column
 *                     Slot 21 — Natural Spawn Restrictions  (col 3)
 *                     Slot 23 — Unnatural Spawns hub        (col 5)
 *   Row 5  (45–53): filler + back button at slot 49
 */
object RegionSettingsGui {

    private const val NATURAL_SLOT   = 21   // row 2, col 3
    private const val UNNATURAL_SLOT = 23   // row 2, col 5
    private const val BACK_SLOT      = 49

    private object Textures {
        // Gear / settings head
        const val NATURAL   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTdiMjE4OTMwMGYzMzliYTA1MGUwMWFlMmE1NDBiN2U4OWVmODk2YTU1Yzc5MTZkY2M5ZTU4NTFhZjg2NDExZSJ9fX0="
        // Globe head
        const val UNNATURAL = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
        // Red X back
        const val BACK      = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    // ── Open ──────────────────────────────────────────────────────────────────

    fun open(player: ServerPlayerEntity, regionId: String, subRegionId: String?) {
        val title = RegionsConfig.scopeLabel(regionId, subRegionId) + " — Settings"
        CustomGui.openGui(
            player,
            title,
            buildLayout(regionId, subRegionId),
            { ctx -> handleClick(ctx, player, regionId, subRegionId) },
            {}
        )
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    private fun handleClick(
        ctx: InteractionContext,
        player: ServerPlayerEntity,
        regionId: String,
        subRegionId: String?
    ) {
        when (ctx.slotIndex) {
            NATURAL_SLOT   -> RegionNaturalSpawnGui.open(player, regionId, subRegionId)
            UNNATURAL_SLOT -> RegionUnnaturalSpawnGui.open(player, regionId)
            BACK_SLOT      -> RegionEditorGui.open(player, regionId)
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(regionId: String, subRegionId: String?): List<ItemStack> {
        val layout = MutableList(54) { filler() }

        // Row 0 — decorative header bar
        for (i in 0..8) layout[i] = glass()

        layout[NATURAL_SLOT]   = naturalBtn(regionId, subRegionId)
        layout[UNNATURAL_SLOT] = unnaturalBtn(regionId)
        layout[BACK_SLOT]      = backBtn()

        return layout
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private fun naturalBtn(regionId: String, subRegionId: String?): ItemStack {
        val restr = RegionsConfig.getRestriction(regionId, subRegionId)
        return CustomGui.createPlayerHeadButton(
            "NaturalSpawns",
            Text.literal("Natural Spawn Restrictions").formatted(Formatting.GREEN),
            listOf(
                Text.literal("§7Control which Pokémon are allowed"),
                Text.literal("§7to spawn naturally in this scope."),
                Text.literal(""),
                Text.literal("§7Disable All Natural Spawns: ${flag(restr?.disableAll ?: false)}"),
                Text.literal("§7Blocked Species: §f${restr?.disallowedSpecies?.size ?: 0}"),
                Text.literal(""),
                Text.literal("§eClick §7to configure")
            ),
            Textures.NATURAL
        )
    }

    private fun unnaturalBtn(regionId: String): ItemStack {
        val region = RegionsConfig.getRegion(regionId)
        val count  = region?.selectedPokemon?.size ?: 0
        val ticks  = region?.spawnTimerTicks ?: 200L
        return CustomGui.createPlayerHeadButton(
            "UnnaturalSpawns",
            Text.literal("Unnatural Spawns").formatted(Formatting.AQUA),
            listOf(
                Text.literal("§7Configure Pokémon that this region"),
                Text.literal("§7spawns on its own timer."),
                Text.literal(""),
                Text.literal("§7Spawn Timer: §f${ticks} ticks §8(${ticks / 20}s)"),
                Text.literal("§7Configured Pokémon: §f$count"),
                Text.literal(""),
                Text.literal("§eClick §7to configure")
            ),
            Textures.UNNATURAL
        )
    }

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back",
        Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to region overview")),
        Textures.BACK
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun flag(b: Boolean) = if (b) "§atrue" else "§cfalse"
    private fun glass()  = ItemStack(Items.CYAN_STAINED_GLASS_PANE).apply  { setCustomName(Text.literal(" ")) }
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply  { setCustomName(Text.literal(" ")) }
}