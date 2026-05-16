package com.cobblespawnregions.gui

import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
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
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * Paginated label selector GUI.
 * Dynamically reads every label from [CobblemonPokemonLabels] via Java reflection,
 * so it stays up-to-date as Cobblemon adds or removes labels.
 *
 * Click a label to toggle it in [RegionRestrictionConfig.disallowedLabels].
 */
object RegionLabelSelectorGui {

    private const val PAGE_SIZE = 45

    private val playerPages = ConcurrentHashMap<ServerPlayerEntity, Int>()

    // ── Dynamic label list ──────────────────────────────────────────────────
    // Reflected from CobblemonPokemonLabels via Java reflection (not kotlin.reflect)
    // because const val compiles to static final fields — kotlin.reflect can miss them.

    @Volatile
    private var cachedLabels: List<String> = emptyList()

    /**
     * Returns all known Cobblemon labels, reflected from the API object.
     * Sorted alphabetically for consistent pagination.
     */
    fun getAllLabels(): List<String> {
        if (cachedLabels.isEmpty()) refreshCache()
        return cachedLabels
    }

    /**
     * Re-reads every field from [CobblemonPokemonLabels] via Java reflection.
     * Safe to call anytime — e.g., after a mod reload or config reload.
     *
     * Uses [Field.get] on the singleton INSTANCE so we read actual values,
     * not just field names.
     */
    fun refreshCache() {
        cachedLabels = try {
            CobblemonPokemonLabels::class.java.declaredFields
                .filter { it.type == String::class.java }
                .mapNotNull { field ->
                    try {
                        field.isAccessible = true
                        field.get(CobblemonPokemonLabels) as? String
                    } catch (_: Exception) { null }
                }
                .filter { it.isNotBlank() }
                .sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Slots & Textures ────────────────────────────────────────────────────

    private object Slots {
        const val PREV = 45
        const val BACK = 49
        const val NEXT = 53
    }

    private object Textures {
        const val PREV = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val NEXT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        const val LABEL_ON  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmViNTg4YjIxYTZmOThhZDFmZjRlMDg1YzU1MmRjYjA1MGVmYzljYWI0MjdmNDcyNjkwOTYxMjg5N2I5Zjk3In19fQ=="
        const val LABEL_OFF = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjYyZTBiMjU2Yzg1ZTY2NjFmMzk5YjVjNjE5OWZjNDVmMWYzZDJiZjI0ZTM1N2EyMzI3MWRkMzBhNjczM2E1ZiJ9fX0="
    }

    // ── Open ────────────────────────────────────────────────────────────────

    fun open(player: ServerPlayerEntity, regionId: String, page: Int = 0) {
        playerPages[player] = page
        val label = RegionsConfig.scopeLabel(regionId)

        CustomGui.openGui(
            player,
            "Excluded Labels — $label",
            buildLayout(player, regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            {}
        )
    }

    // ── Handle clicks ───────────────────────────────────────────────────────

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        val restr = RegionsConfig.getRestriction(regionId) ?: return
        val page  = playerPages[player] ?: 0
        val labels = getAllLabels()

        when (ctx.slotIndex) {
            Slots.PREV -> if (page > 0) {
                playerPages[player] = page - 1
                refresh(player, regionId)
            }
            Slots.NEXT -> if ((page + 1) * PAGE_SIZE < labels.size) {
                playerPages[player] = page + 1
                refresh(player, regionId)
            }
            Slots.BACK -> RegionNaturalSpawnGui.open(player, regionId)
            in 0 until PAGE_SIZE -> {
                val idx = page * PAGE_SIZE + ctx.slotIndex
                if (idx < labels.size) toggleLabel(restr, regionId, labels[idx], player)
            }
        }
    }

    private fun toggleLabel(
        restr: RegionRestrictionConfig,
        regionId: String,
        label: String,
        player: ServerPlayerEntity
    ) {
        if (label in restr.disallowedLabels) {
            restr.disallowedLabels.remove(label)
        } else {
            restr.disallowedLabels.add(label)
        }
        RegionsConfig.saveRegion(regionId)
        refresh(player, regionId)
    }

    private fun refresh(player: ServerPlayerEntity, regionId: String) {
        CustomGui.refreshGui(player, buildLayout(player, regionId))
    }

    // ── Build layout ─────────────────────────────────────────────────────────

    private fun buildLayout(player: ServerPlayerEntity, regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val restr  = RegionsConfig.getRestriction(regionId) ?: return layout
        val page   = playerPages[player] ?: 0
        val labels = getAllLabels()
        val blocked = restr.disallowedLabels

        val start = minOf(page * PAGE_SIZE, labels.size)
        val end   = minOf(start + PAGE_SIZE, labels.size)

        for (i in start until end) {
            val lbl = labels[i]
            val isBlocked = lbl in blocked
            layout[i - start] = labelItem(lbl, isBlocked)
        }

        // Nav buttons
        if (page > 0)                                layout[Slots.PREV] = navBtn("Previous Page", Textures.PREV)
        if ((page + 1) * PAGE_SIZE < labels.size)    layout[Slots.NEXT] = navBtn("Next Page", Textures.NEXT)
        layout[Slots.BACK] = navBtn("Back to Spawn Settings", Textures.BACK)

        return layout
    }

    // ── Item builders ────────────────────────────────────────────────────────

    private fun labelItem(label: String, isExcluded: Boolean): ItemStack {
        val item = CustomGui.createPlayerHeadButton(
            "lbl_$label",
            Text.literal(label).formatted(if (isExcluded) Formatting.RED else Formatting.GREEN),
            listOf(
                Text.literal(""),
                Text.literal(if (isExcluded) "§c§lEXCLUDED" else "§a§lALLOWED"),
                Text.literal("§7Click to toggle this label in"),
                Text.literal("§7disallowedLabels.")
            ),
            AlphabetHeadTextures.forFirstLetter(label, if (isExcluded) Textures.LABEL_ON else Textures.LABEL_OFF)
        )
        if (isExcluded) CustomGui.addEnchantmentGlint(item)
        return item
    }

    private fun navBtn(label: String, texture: String) = CustomGui.createPlayerHeadButton(
        label.replace(" ", ""),
        Text.literal(label).formatted(Formatting.AQUA),
        emptyList(),
        texture
    )

    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
        setCustomName(Text.literal(" "))
    }
}
