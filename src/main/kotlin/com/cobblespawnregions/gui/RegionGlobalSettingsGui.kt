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

object RegionGlobalSettingsGui {

    private object Slots {
        const val DEBUG = 11
        const val SHOW_UNIMPLEMENTED = 13
        const val SHOW_FORMS = 30
        const val SHOW_ASPECTS = 32
        const val BACK = 49
    }

    fun open(player: ServerPlayerEntity, returnPage: Int = 0) {
        CustomGui.openGui(
            player,
            "CSR Settings",
            buildLayout(),
            { ctx -> handleClick(ctx, player, returnPage) },
            {}
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, returnPage: Int) {
        val config = RegionsConfig.config
        var changed = true

        when (ctx.slotIndex) {
            Slots.DEBUG -> config.debugEnabled = !config.debugEnabled
            Slots.SHOW_UNIMPLEMENTED -> config.showUnimplementedPokemonInGui = !config.showUnimplementedPokemonInGui
            Slots.SHOW_FORMS -> config.showFormsInGui = !config.showFormsInGui
            Slots.SHOW_ASPECTS -> config.showAspectsInGui = !config.showAspectsInGui
            Slots.BACK -> {
                RegionListGui.open(player, returnPage)
                changed = false
            }
            else -> changed = false
        }

        if (changed) {
            RegionsConfig.saveConfigBlocking()
            RegionPokemonSelectionGui.invalidateCache()
            CustomGui.refreshGui(player, buildLayout())
        }
    }

    private fun buildLayout(): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val config = RegionsConfig.config

        layout[Slots.DEBUG] = toggleItem(
            "Debug Logging",
            config.debugEnabled,
            listOf("Enables detailed CSR logs.")
        )
        layout[Slots.SHOW_UNIMPLEMENTED] = toggleItem(
            "Show Unimplemented Pokemon",
            config.showUnimplementedPokemonInGui,
            listOf("Includes Pokemon not currently implemented by Cobblemon in picker lists.")
        )
        layout[Slots.SHOW_FORMS] = toggleItem(
            "Show Forms",
            config.showFormsInGui,
            listOf("Includes alternate forms in region Pokemon picker lists.")
        )
        layout[Slots.SHOW_ASPECTS] = toggleItem(
            "Show Aspects",
            config.showAspectsInGui,
            listOf("Includes extra aspect variants, such as gender or cosmetic aspects.")
        )
        layout[Slots.BACK] = CustomGui.createPlayerHeadButton(
            "Back",
            Text.literal("Back").formatted(Formatting.RED),
            listOf(Text.literal("§7Return to region list.")),
            Textures.BACK
        )

        return layout
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
        CustomGui.setItemLore(item, lore + listOf("", "§eClick §7to toggle"))
        return item
    }

    private object Textures {
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
