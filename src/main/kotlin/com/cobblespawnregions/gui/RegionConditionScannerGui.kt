package com.cobblespawnregions.gui

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Species
import com.cobblespawnregions.utils.PokemonConditionExtractor
import com.cobblespawnregions.utils.RegionsConfig
import com.everlastingutils.gui.CustomGui
import com.everlastingutils.gui.InteractionContext
import com.everlastingutils.gui.setCustomName
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.joml.Vector4f
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object RegionConditionScannerGui {

    private val logger = LoggerFactory.getLogger("RegionConditionScannerGui")
    private const val PAGE_SIZE = 45
    private val playerPages = ConcurrentHashMap<ServerPlayerEntity, Int>()

    private val allSpecies: List<Species> by lazy {
        com.cobblemon.mod.common.api.pokemon.PokemonSpecies.species
            .filter { it.implemented }.sortedBy { it.name }
    }

    private object Slots {
        const val PREV      = 45
        const val EXCLUDED  = 48   // ← NEW: left of back
        const val BACK      = 49
        const val NEXT      = 53
    }

    private object Textures {
        const val PREV    = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val NEXT    = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val BACK    = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        const val TRASH   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTk4MWMwNmE1MzA0YzFjOTg4NjI1MTM5YzljNjBhNjdmMGI0NGE0ODc4NjE0NjNhMjViMjFiNjAwMmEyMyJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String, page: Int = 0) {
        playerPages[player] = page
        val label = RegionsConfig.scopeLabel(regionId)

        CustomGui.openGui(
            player,
            "Scan Conditions — $label",
            buildLayout(player, regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            { playerPages.remove(player) }
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        when (ctx.slotIndex) {
            Slots.PREV -> {
                val page = (playerPages[player] ?: 0) - 1
                if (page >= 0) { playerPages[player] = page; refresh(player, regionId) }
            }
            Slots.NEXT -> {
                val page = (playerPages[player] ?: 0) + 1
                if (page * PAGE_SIZE < allSpecies.size) { playerPages[player] = page; refresh(player, regionId) }
            }
            Slots.EXCLUDED -> // ← NEW: open excluded conditions list
                RegionExcludedConditionsListGui.open(player, regionId)
            Slots.BACK -> RegionNaturalSpawnGui.open(player, regionId)
            in 0 until PAGE_SIZE -> scanSpecies(ctx.slotIndex, player, regionId)
        }
    }

    private fun scanSpecies(slot: Int, player: ServerPlayerEntity, regionId: String) {
        val page = playerPages[player] ?: 0
        val idx = page * PAGE_SIZE + slot
        if (idx >= allSpecies.size) return

        val species = allSpecies[idx]

        val filteredConditions = PokemonConditionExtractor.scanSpeciesForConditions(player, species.name)

        if (filteredConditions.isEmpty()) {
            player.sendMessage(
                Text.literal("§cFailed to scan ${species.name}. Check console for errors."),
                false
            )
            return
        }

        RegionConditionSelectorGui.open(player, regionId, filteredConditions)
    }

    private fun refresh(player: ServerPlayerEntity, regionId: String) {
        CustomGui.refreshGui(player, buildLayout(player, regionId))
    }

    private fun buildLayout(player: ServerPlayerEntity, regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val page = playerPages[player] ?: 0

        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, allSpecies.size)
        for (i in start until end) {
            layout[i - start] = speciesItem(allSpecies[i])
        }

        if (page > 0)                            layout[Slots.PREV]     = navBtn("Previous Page", Textures.PREV)
        if ((page + 1) * PAGE_SIZE < allSpecies.size) layout[Slots.NEXT]     = navBtn("Next Page", Textures.NEXT)
        layout[Slots.EXCLUDED] = excludedBtn(regionId)           // ← NEW
        layout[Slots.BACK]     = navBtn("Back", Textures.BACK)

        return layout
    }

    // ── Item builders ────────────────────────────────────────────────────────

    private fun speciesItem(species: Species): ItemStack {
        val item = try {
            val pokemon = PokemonProperties.parse(species.name.lowercase()).create()
            PokemonItem.from(pokemon, tint = Vector4f(1f, 1f, 1f, 1f))
        } catch (e: Exception) {
            RegionsConfig.debugError(logger, "Failed to build species item for ${species.name}", e)
            ItemStack(Items.BARRIER)
        }

        item.setCustomName(Text.literal(species.name).formatted(Formatting.GOLD))
        CustomGui.setItemLore(item, listOf(
            "§8${species.resourceIdentifier}",
            "",
            "§eClick to Scan:",
            "§7Finds every excludable tag",
            "§7for this Pokémon and opens",
            "§7the condition selector menu."
        ))
        return item
    }

    /** Shows current count, opens the excluded list GUI */
    private fun excludedBtn(regionId: String): ItemStack {
        val count = RegionsConfig.getRestriction(regionId)?.exclusionConditions?.size ?: 0
        val item = ItemStack(Items.BARRIER)
        item.setCustomName(Text.literal("Excluded Conditions").formatted(Formatting.RED))
        CustomGui.setItemLore(item, listOf(
            "§7Currently §f$count §7condition(s) excluded.",
            "",
            "§eClick §7to view & remove"
        ))
        return item
    }

    private fun navBtn(label: String, texture: String) = CustomGui.createPlayerHeadButton(
        label.replace(" ", ""), Text.literal(label).formatted(Formatting.GREEN), emptyList(), texture
    )

    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
