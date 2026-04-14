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
 * Natural spawn restriction toggles — shared by main regions and sub-regions.
 * [subRegionId] == null → editing the parent region's own restrictions.
 * [subRegionId] != null → editing that sub-region's restrictions.
 *
 * Layout:
 *   Row 2: Global toggles (disable all / exclude owned)
 *   Row 4: Three exclusion type buttons (species / labels / conditions)
 *   Row 6: Back button
 */
object RegionNaturalSpawnGui {

    // ── Slot positions ──────────────────────────────────────────────────────
    // Row 2 (indices 18–26):  Global ON/OFF toggles
    // Row 4 (indices 36–44):  Exclusion list buttons (species, labels, conditions)
    // Row 6 (indices 45–53):  Navigation / back

    private const val DISABLE_ALL_SLOT    = 20    // row 2, left of center
    private const val EXCLUDE_OWNED_SLOT  = 24    // row 2, right of center
    private const val SPECIES_LIST_SLOT   = 28    // row 4, left
    private const val LABEL_LIST_SLOT     = 31    // row 4, center
    private const val CONDITIONS_SLOT     = 34    // row 4, right  ← renamed from scanner
    private const val BACK_SLOT           = 49    // bottom center

    private object Textures {
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String, subRegionId: String?) {
        val label = RegionsConfig.scopeLabel(regionId, subRegionId)
        CustomGui.openGui(
            player,
            "Natural Spawns — $label",
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
        val restr = RegionsConfig.getRestriction(regionId, subRegionId) ?: return

        when (ctx.slotIndex) {
            DISABLE_ALL_SLOT -> {
                restr.disableAll = !restr.disableAll
                RegionsConfig.saveRegion(regionId)
                CustomGui.refreshGui(player, buildLayout(regionId, subRegionId))
            }
            EXCLUDE_OWNED_SLOT -> {
                restr.excludeOwnedPokemon = !restr.excludeOwnedPokemon
                RegionsConfig.saveRegion(regionId)
                CustomGui.refreshGui(player, buildLayout(regionId, subRegionId))
            }
            SPECIES_LIST_SLOT -> RegionSpeciesBlocklistGui.open(player, regionId, subRegionId)
            LABEL_LIST_SLOT   -> RegionLabelSelectorGui.open(player, regionId, subRegionId)
            CONDITIONS_SLOT    -> RegionConditionScannerGui.open(player, regionId, subRegionId)
            BACK_SLOT         -> RegionSettingsGui.open(player, regionId, subRegionId)
        }
    }

    private fun buildLayout(regionId: String, subRegionId: String?): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val restr  = RegionsConfig.getRestriction(regionId, subRegionId) ?: return layout

        // ════════════════════════════════════════════════════════════════════
        // ROW 2 — GLOBAL TOGGLES (top section)
        // ════════════════════════════════════════════════════════════════════
        layout[DISABLE_ALL_SLOT]   = toggleItem(
            "Disable All Spawns",
            restr.disableAll,
            listOf(
                "§7Blocks every natural Pokémon spawn",
                "§7inside this area.",
                "",
                "§eClick §7to toggle"
            )
        )
        layout[EXCLUDE_OWNED_SLOT] = toggleItem(
            "Exclude Owned Pokémon",
            restr.excludeOwnedPokemon,
            listOf(
                "§7When ON, player-owned Pokémon",
                "§7bypass all spawn restrictions.",
                "",
                "§eClick §7to toggle"
            )
        )

        // ════════════════════════════════════════════════════════════════════
        // ROW 4 — EXCLUSION TYPE BUTTONS (lower section)
        // ════════════════════════════════════════════════════════════════════
        layout[SPECIES_LIST_SLOT] = speciesListBtn(restr.disallowedSpecies.size)
        layout[LABEL_LIST_SLOT]   = labelListBtn(restr.disallowedLabels.size)
        layout[CONDITIONS_SLOT]    = excludeConditionsBtn()

        // ════════════════════════════════════════════════════════════════════
        // ROW 6 — BACK BUTTON
        // ════════════════════════════════════════════════════════════════════
        layout[BACK_SLOT] = backBtn()

        return layout
    }

    // ── Item builders ────────────────────────────────────────────────────────

    /**
     * Red concrete (OFF) or Lime concrete (ON) toggle.
     */
    private fun toggleItem(label: String, enabled: Boolean, lore: List<String>): ItemStack {
        val item = ItemStack(if (enabled) Items.LIME_CONCRETE else Items.RED_CONCRETE)
        item.setCustomName(
            Text.literal("$label: ").formatted(Formatting.WHITE)
                .append(
                    if (enabled) Text.literal("ON").formatted(Formatting.GREEN, Formatting.BOLD)
                    else         Text.literal("OFF").formatted(Formatting.RED, Formatting.BOLD)
                )
        )
        CustomGui.setItemLore(item, lore)
        return item
    }

    /**
     * Book icon — opens species blocklist GUI.
     */
    private fun speciesListBtn(count: Int): ItemStack {
        val item = ItemStack(Items.BOOK)
        item.setCustomName(Text.literal("Blocked Species").formatted(Formatting.AQUA))
        CustomGui.setItemLore(item, listOf(
            "§7Currently blocking §f$count §7species.",
            "§8Exact match by resource ID.",
            "",
            "§eClick §7to manage"
        ))
        return item
    }

    /**
     * Name Tag icon — opens label selector GUI.
     */
    private fun labelListBtn(count: Int): ItemStack {
        val item = ItemStack(Items.NAME_TAG)
        item.setCustomName(Text.literal("Excluded Labels").formatted(Formatting.LIGHT_PURPLE))
        CustomGui.setItemLore(item, listOf(
            "§7Currently blocking §f$count §7label(s).",
            "§8(e.g. legendary, mythical, ultra_beast)",
            "",
            "§eClick §7to manage"
        ))
        return item
    }

    /**
     * Spyglass icon — opens condition scanner GUI.
     * Renamed to "Exclude Conditions" with alpha warning + usage instructions.
     */
    private fun excludeConditionsBtn(): ItemStack {
        val item = ItemStack(Items.SPYGLASS)
        item.setCustomName(Text.literal("Exclude Conditions").formatted(Formatting.YELLOW))
        CustomGui.setItemLore(item, listOf(
            "",
            "§c§l⚠ ALPHA FEATURE ⚠",
            "§cThis tool is experimental.",
            "§cNot all properties may display correctly.",
            "§cReport bugs if you encounter them.",
            "",
            "§7How it works:",
            "§7  1. Click to open the species picker",
            "§7  2. Click any Pokémon species",
            "§7  3. All extractable properties are shown",
            "§7  4. Toggle any property to block spawns",
            "§7     that match it as a substring",
            "",
            "§8Example: blocking §f\"species=abra\" §8prevents",
            "§8Abra from spawning. Blocking §f\"shiny=true\" §8",
            "§8prevents any shiny from spawning.",
            "",
            "§eClick §7to open scanner"
        ))
        return item
    }

    /**
     * Player head — back button.
     */
    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back",
        Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to region settings")),
        Textures.BACK
    )

    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
        setCustomName(Text.literal(" "))
    }
}