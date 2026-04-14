package com.cobblespawnregions.gui

import com.everlastingutils.gui.AnvilGuiManager
import com.everlastingutils.gui.CustomGui
import com.everlastingutils.gui.FullyModularAnvilScreenHandler
import com.everlastingutils.gui.setCustomName
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

/**
 * Anvil search GUI for the species block-list.
 * Mirrors the pattern used by CobbleSpawners' SearchGui:
 *   Left slot  = cancel button
 *   Right slot = blocked input (glass pane)
 *   Result     = dynamic search button that confirms on click
 *
 * On confirm or cancel, returns to [RegionSpeciesBlocklistGui] with the
 * correct [regionId] / [subRegionId] scope preserved.
 */
object RegionSpeciesSearchGui {

    private object Textures {
        const val CANCEL = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        const val SEARCH = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTY4M2RjN2JjNmRiZGI1ZGM0MzFmYmUyOGRjNGI5YWU2MjViOWU1MzE3YTI5ZjJjNGVjZmU3YmY1YWU1NmMzOCJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String, subRegionId: String?) {
        val guiId = "csr_species_search_${regionId}_${subRegionId ?: "main"}"

        AnvilGuiManager.openAnvilGui(
            player       = player,
            id           = guiId,
            title        = "Search Species",
            initialText  = "",
            leftItem     = cancelBtn(),
            rightItem    = blockedPane(),
            resultItem   = placeholderOutput(),

            onLeftClick  = {
                // Cancel — return without changing the search term
                goBack(player, regionId, subRegionId)
            },

            onRightClick = null,

            onResultClick = { context ->
                val text = context.handler.currentText.trim()
                if (text.isNotBlank()) {
                    RegionSpeciesBlocklistGui.applySearch(player, text)
                }
                goBack(player, regionId, subRegionId)
            },

            onTextChange = { text ->
                val handler = player.currentScreenHandler as? FullyModularAnvilScreenHandler
                handler?.updateSlot(2, if (text.isNotEmpty()) dynamicSearchBtn(text) else placeholderOutput())
            },

            onClose = {
                // Guard against double-open: only navigate back if no other GUI is already open
                player.server.execute {
                    if (player.currentScreenHandler !is FullyModularAnvilScreenHandler) {
                        goBack(player, regionId, subRegionId)
                    }
                }
            }
        )

        // Clear any leftover text from a previous search session
        player.server.execute {
            (player.currentScreenHandler as? FullyModularAnvilScreenHandler)?.clearTextField()
        }
        player.sendMessage(Text.literal("§7Type a Pokémon name then click the green button, or click §cX §7to cancel."), false)
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun goBack(player: ServerPlayerEntity, regionId: String, subRegionId: String?) {
        player.server.execute {
            RegionSpeciesBlocklistGui.open(player, regionId, subRegionId, page = 0)
        }
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private fun cancelBtn() = CustomGui.createPlayerHeadButton(
        textureName  = "CancelSearch",
        title        = Text.literal("§cCancel Search").styled { it.withBold(true).withItalic(false) },
        lore         = listOf(Text.literal("§7Return to species list without searching")),
        textureValue = Textures.CANCEL
    )

    private fun dynamicSearchBtn(term: String) = CustomGui.createPlayerHeadButton(
        textureName  = "ConfirmSearch",
        title        = Text.literal("§aSearch: §f$term").styled { it.withBold(true).withItalic(false) },
        lore         = listOf(
            Text.literal("§aClick to search for this term"),
            Text.literal("§7Keep typing to refine")
        ),
        textureValue = Textures.SEARCH
    )

    private fun placeholderOutput() = ItemStack(Items.LIGHT_GRAY_STAINED_GLASS_PANE).apply {
        setCustomName(Text.literal(" "))
    }

    private fun blockedPane() = ItemStack(Items.LIGHT_GRAY_STAINED_GLASS_PANE).apply {
        setCustomName(Text.literal(" "))
    }
}