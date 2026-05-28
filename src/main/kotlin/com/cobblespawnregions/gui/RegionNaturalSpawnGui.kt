package com.cobblespawnregions.gui

import com.cobblespawnregions.utils.RegionRestrictionConfig
import com.cobblespawnregions.utils.RegionsConfig
import com.everlastingutils.gui.CustomGui
import com.everlastingutils.gui.InteractionContext
import com.everlastingutils.gui.setCustomName
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting




object RegionNaturalSpawnGui {

    private object Slots {
        const val DISABLE_ALL = 20
        const val EXCLUDE_OWNED = 24
        const val SPECIES = 28
        const val LABELS = 31
        const val CONDITIONS = 34
        const val BACK = 49
    }

    private object Textures {
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String) {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            player.sendMessage(Text.literal("§c[CSR] Region not found."), false)
            return
        }
        CustomGui.openGui(
            player,
            "Natural - ${region.regionName}",
            buildLayout(regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            {}
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        when (ctx.slotIndex) {
            Slots.DISABLE_ALL -> toggleRestriction(player, regionId) { it.disableAll = !it.disableAll }
            Slots.EXCLUDE_OWNED -> toggleRestriction(player, regionId) { it.excludeOwnedPokemon = !it.excludeOwnedPokemon }
            Slots.SPECIES -> RegionSpeciesBlocklistGui.open(player, regionId)
            Slots.LABELS -> RegionLabelSelectorGui.open(player, regionId)
            Slots.CONDITIONS -> RegionConditionScannerGui.open(player, regionId)
            Slots.BACK -> RegionEditorGui.open(player, regionId)
        }
    }

    private fun buildLayout(regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val restr = RegionsConfig.getRestriction(regionId) ?: return layout

        for (i in 0..8) layout[i] = glass()

        layout[Slots.DISABLE_ALL] = toggleItem("Disable Natural Spawns", restr.disableAll, listOf(
            "§7Blocks every natural Pokemon spawn",
            "§7where this region controls the position.",
            "",
            "§eClick §7to toggle"
        ))
        layout[Slots.EXCLUDE_OWNED] = toggleItem("Exclude Owned Pokemon", restr.excludeOwnedPokemon, listOf(
            "§7When ON, player-owned Pokemon",
            "§7bypass this region's restrictions.",
            "",
            "§eClick §7to toggle"
        ))
        layout[Slots.SPECIES] = listItem(Items.BOOK, "Blocked Species", Formatting.AQUA, restr.disallowedSpecies.size)
        layout[Slots.LABELS] = listItem(Items.NAME_TAG, "Excluded Labels", Formatting.LIGHT_PURPLE, restr.disallowedLabels.size)
        layout[Slots.CONDITIONS] = conditionItem(restr.exclusionConditions.size)
        layout[Slots.BACK] = backBtn()
        return layout
    }

    private fun refresh(player: ServerPlayerEntity, regionId: String) {
        CustomGui.refreshGui(player, buildLayout(regionId))
    }

    private fun toggleRestriction(
        player: ServerPlayerEntity,
        regionId: String,
        update: (RegionRestrictionConfig) -> Unit
    ) {
        val restr = RegionsConfig.getRestriction(regionId) ?: return
        update(restr)
        RegionsConfig.saveRegion(regionId)
        refresh(player, regionId)
    }

    private fun toggleItem(label: String, enabled: Boolean, lore: List<String>): ItemStack {
        val item = ItemStack(if (enabled) Items.LIME_CONCRETE else Items.RED_CONCRETE)
        item.setCustomName(
            Text.literal("$label: ").formatted(Formatting.WHITE)
                .append(
                    if (enabled) Text.literal("ON").formatted(Formatting.GREEN, Formatting.BOLD)
                    else Text.literal("OFF").formatted(Formatting.RED, Formatting.BOLD)
                )
        )
        CustomGui.setItemLore(item, lore)
        return item
    }

    private fun listItem(item: net.minecraft.item.Item, label: String, color: Formatting, count: Int): ItemStack {
        return ItemStack(item).apply {
            setCustomName(Text.literal(label).formatted(color))
            CustomGui.setItemLore(this, listOf(
                "§7Currently blocking §f$count§7.",
                "",
                "§eClick §7to manage"
            ))
        }
    }

    private fun conditionItem(count: Int): ItemStack {
        return ItemStack(Items.SPYGLASS).apply {
            setCustomName(Text.literal("Excluded Conditions").formatted(Formatting.YELLOW))
            CustomGui.setItemLore(this, listOf(
                "§7Currently blocking §f$count §7condition(s).",
                "§8Experimental property scanner.",
                "",
                "§eClick §7to scan or manage"
            ))
        }
    }

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back",
        Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to region settings")),
        Textures.BACK
    )

    private fun glass() = ItemStack(Items.CYAN_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
