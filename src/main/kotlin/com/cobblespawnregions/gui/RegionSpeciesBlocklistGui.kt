package com.cobblespawnregions.gui

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Species
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

enum class BlocklistSortMethod { ALPHABETICAL, TYPE, BLOCKED, SEARCH }

object RegionSpeciesBlocklistGui {

    private val logger = LoggerFactory.getLogger("RegionSpeciesBlocklistGui")
    private const val PAGE_SIZE = 45

    // Per-player state
    private val playerPages       = ConcurrentHashMap<ServerPlayerEntity, Int>()
    private val playerSortMethods = ConcurrentHashMap<ServerPlayerEntity, BlocklistSortMethod>()
    private val playerSearchTerms = ConcurrentHashMap<ServerPlayerEntity, String>()

    // Sorted once at startup — filtering/reordering happens on top of this
    private val allSpecies: List<Species> by lazy {
        PokemonSpecies.species.filter { it.implemented }.sortedBy { it.name }
    }

    private object Slots {
        const val PREV = 45
        const val SORT = 48
        const val BACK = 49
        const val NEXT = 53
    }

    private object Textures {
        const val PREV   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val NEXT   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val SORT   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1ZWU0MTlhZDljMDYwYzE2Y2I1M2IxZGNmZmFjOGJhY2EwYjJhMjI2NWIxYjZjN2U4ZTc4MGMzN2IxMDRjMCJ9fX0="
        const val BACK   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun open(player: ServerPlayerEntity, regionId: String, page: Int = 0) {
        playerPages[player] = page
        val label = RegionsConfig.scopeLabel(regionId)

        CustomGui.openGui(
            player,
            "Blocked Species — $label",
            buildLayout(player, regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            {
                playerPages.remove(player)
                playerSortMethods.remove(player)
                playerSearchTerms.remove(player)
            }
        )
    }

    /** Called by [RegionSpeciesSearchGui] after the player confirms a search term. */
    fun applySearch(player: ServerPlayerEntity, term: String) {
        playerSearchTerms[player] = term.trim()
        playerSortMethods[player] = BlocklistSortMethod.SEARCH
        playerPages[player] = 0
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    private fun handleClick(
        ctx: InteractionContext,
        player: ServerPlayerEntity,
        regionId: String
    ) {
        when (ctx.slotIndex) {
            Slots.PREV -> {
                val page = (playerPages[player] ?: 0) - 1
                if (page >= 0) { playerPages[player] = page; refresh(player, regionId) }
            }
            Slots.NEXT -> {
                val page  = (playerPages[player] ?: 0) + 1
                val total = getSpeciesForPlayer(player, regionId).size
                if (page * PAGE_SIZE < total) { playerPages[player] = page; refresh(player, regionId) }
            }
            Slots.SORT -> when (ctx.button) {
                // Left-click: cycle sort method
                0 -> {
                    val next = when (playerSortMethods.getOrDefault(player, BlocklistSortMethod.ALPHABETICAL)) {
                        BlocklistSortMethod.ALPHABETICAL -> BlocklistSortMethod.TYPE
                        BlocklistSortMethod.TYPE         -> BlocklistSortMethod.BLOCKED
                        BlocklistSortMethod.BLOCKED      -> BlocklistSortMethod.ALPHABETICAL
                        BlocklistSortMethod.SEARCH       -> BlocklistSortMethod.ALPHABETICAL
                    }
                    playerSortMethods[player] = next
                    if (next != BlocklistSortMethod.SEARCH) playerSearchTerms.remove(player)
                    playerPages[player] = 0
                    refresh(player, regionId)
                }
                // Right-click: open search
                1 -> RegionSpeciesSearchGui.open(player, regionId)
            }
            Slots.BACK -> RegionNaturalSpawnGui.open(player, regionId)

            in 0 until PAGE_SIZE -> toggleSpecies(ctx.slotIndex, player, regionId)
        }
    }

    private fun toggleSpecies(slot: Int, player: ServerPlayerEntity, regionId: String) {
        val page    = playerPages[player] ?: 0
        val species = getSpeciesForPlayer(player, regionId)
        val idx     = page * PAGE_SIZE + slot
        if (idx >= species.size) return

        val id    = species[idx].resourceIdentifier.toString()
        val restr = RegionsConfig.getRestriction(regionId) ?: return

        if (restr.disallowedSpecies.contains(id)) restr.disallowedSpecies.remove(id)
        else restr.disallowedSpecies.add(id)

        RegionsConfig.saveRegion(regionId)
        refresh(player, regionId)
    }

    private fun refresh(player: ServerPlayerEntity, regionId: String) {
        CustomGui.refreshGui(player, buildLayout(player, regionId))
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(player: ServerPlayerEntity, regionId: String): List<ItemStack> {
        val layout  = MutableList(54) { filler() }
        val page    = playerPages[player] ?: 0
        val blocked = RegionsConfig.getRestriction(regionId)?.disallowedSpecies ?: return layout
        val species = getSpeciesForPlayer(player, regionId)
        val total   = species.size

        val start = page * PAGE_SIZE
        val end   = minOf(start + PAGE_SIZE, total)
        for (i in start until end) {
            val sp        = species[i]
            val isBlocked = sp.resourceIdentifier.toString() in blocked
            layout[i - start] = speciesItem(sp, isBlocked)
        }

        if (page > 0)                layout[Slots.PREV] = navBtn("Previous Page", Textures.PREV)
        if ((page + 1) * PAGE_SIZE < total) layout[Slots.NEXT] = navBtn("Next Page", Textures.NEXT)
        layout[Slots.SORT] = sortBtn(player, blocked.size)
        layout[Slots.BACK] = navBtn("Back", Textures.BACK)

        return layout
    }

    // ── Species list computation ──────────────────────────────────────────────

    private fun getSpeciesForPlayer(
        player: ServerPlayerEntity,
        regionId: String
    ): List<Species> {
        val sort   = playerSortMethods.getOrDefault(player, BlocklistSortMethod.ALPHABETICAL)
        val search = playerSearchTerms.getOrDefault(player, "")
        val blocked = RegionsConfig.getRestriction(regionId)?.disallowedSpecies ?: return emptyList()

        return when (sort) {
            BlocklistSortMethod.ALPHABETICAL -> allSpecies
            BlocklistSortMethod.TYPE         -> allSpecies.sortedBy { it.primaryType.name }
            BlocklistSortMethod.BLOCKED      -> allSpecies.filter { it.resourceIdentifier.toString() in blocked }
            BlocklistSortMethod.SEARCH       -> {
                if (search.isBlank()) allSpecies
                else allSpecies.filter { it.name.contains(search, ignoreCase = true) }
            }
        }
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private fun speciesItem(species: Species, isBlocked: Boolean): ItemStack {
        val tint = if (isBlocked) Vector4f(1f, 1f, 1f, 1f) else Vector4f(0.4f, 0.4f, 0.4f, 1f)
        val item = try {
            val pokemon = PokemonProperties.parse(species.name.lowercase()).create()
            PokemonItem.from(pokemon, tint = tint)
        } catch (e: Exception) {
            RegionsConfig.debugError(logger, "Failed to build blocklist item for ${species.name}", e)
            ItemStack(Items.BARRIER)
        }

        item.setCustomName(
            Text.literal(species.name).formatted(if (isBlocked) Formatting.RED else Formatting.WHITE)
        )
        CustomGui.setItemLore(item, buildList {
            add("§8${species.resourceIdentifier}")
            add("§7Type: §f${species.primaryType.name}")
            add("")
            add(if (isBlocked) "§c§lBLOCKED §r§7— click to unblock" else "§7Click to block this species")
        })
        if (isBlocked) CustomGui.addEnchantmentGlint(item)
        return item
    }

    private fun sortBtn(player: ServerPlayerEntity, blockedCount: Int): ItemStack {
        val sort   = playerSortMethods.getOrDefault(player, BlocklistSortMethod.ALPHABETICAL)
        val search = playerSearchTerms.getOrDefault(player, "")

        val title = when (sort) {
            BlocklistSortMethod.SEARCH ->
                "Searching: ${if (search.length > 12) search.take(9) + "..." else search}"
            else -> "Sort: ${sort.name.lowercase().replaceFirstChar { it.uppercase() }}"
        }

        return CustomGui.createPlayerHeadButton(
            "SortMethod",
            Text.literal(title).formatted(Formatting.AQUA),
            listOf(
                "§7Blocked count: §f$blockedCount",
                "",
                "§eLeft-click §7to cycle sort",
                "§eRight-click §7to search by name"
            ).map { Text.literal(it) },
            Textures.SORT
        )
    }

    private fun navBtn(label: String, texture: String) = CustomGui.createPlayerHeadButton(
        label.replace(" ", ""), Text.literal(label).formatted(Formatting.GREEN), emptyList(), texture
    )

    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
