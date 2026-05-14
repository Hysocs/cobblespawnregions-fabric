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
import java.util.concurrent.ConcurrentHashMap

/**
 * Paginated list of currently-excluded conditions.
 * Click any entry to REMOVE it from [RegionRestrictionConfig.exclusionConditions].
 *
 * Opens from the Condition Scanner GUI's "Excluded Conditions" button.
 */
object RegionExcludedConditionsListGui {

    private const val PAGE_SIZE = 45

    private val playerPages = ConcurrentHashMap<ServerPlayerEntity, Int>()

    private object Slots {
        const val PREV = 45
        const val BACK = 49
        const val NEXT = 53
    }

    private object Textures {
        const val PREV  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val NEXT  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val BACK  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        const val ENTRY = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmViNTg4YjIxYTZmOThhZDFmZjRlMDg1YzU1MmRjYjA1MGVmYzljYWI0MjdmNDcyNjkwOTYxMjg5N2I5Zjk3In19fQ=="
    }

    fun open(player: ServerPlayerEntity, regionId: String, page: Int = 0) {
        playerPages[player] = page
        val label = RegionsConfig.scopeLabel(regionId)

        CustomGui.openGui(
            player,
            "Excluded Conditions — $label",
            buildLayout(player, regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            {}
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        val restr = RegionsConfig.getRestriction(regionId) ?: return
        val conditions = restr.exclusionConditions
        val page = playerPages[player] ?: 0

        when (ctx.slotIndex) {
            Slots.PREV -> if (page > 0) {
                playerPages[player] = page - 1
                refresh(player, regionId)
            }
            Slots.NEXT -> if ((page + 1) * PAGE_SIZE < conditions.size) {
                playerPages[player] = page + 1
                refresh(player, regionId)
            }
            Slots.BACK -> RegionConditionScannerGui.open(player, regionId)
            in 0 until PAGE_SIZE -> {
                val idx = page * PAGE_SIZE + ctx.slotIndex
                if (idx < conditions.size) {
                    val condition = conditions[idx]
                    if (condition is String) {                                    // ← FIX: safe cast
                        removeCondition(restr, regionId, condition, player)
                    }
                }
            }
        }
    }


    private fun removeCondition(
        restr: RegionRestrictionConfig,
        regionId: String,
        condition: String,
        player: ServerPlayerEntity
    ) {
        restr.exclusionConditions.remove(condition)
        RegionsConfig.saveRegion(regionId)
        refresh(player, regionId)
    }

    private fun refresh(player: ServerPlayerEntity, regionId: String) {
        CustomGui.refreshGui(player, buildLayout(player, regionId))
    }

    private fun buildLayout(player: ServerPlayerEntity, regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val restr  = RegionsConfig.getRestriction(regionId) ?: return layout
        val page   = playerPages[player] ?: 0
        val conditions = restr.exclusionConditions

        val start = minOf(page * PAGE_SIZE, conditions.size)
        val end   = minOf(start + PAGE_SIZE, conditions.size)

        for (i in start until end) {
            val raw = conditions[i]
            if (raw is String) {                                                // ← FIX: safe cast
                layout[i - start] = conditionEntry(raw)
            }
        }

        // Nav buttons...
        if (page > 0)                                layout[Slots.PREV] = navBtn("Previous Page", Textures.PREV)
        if ((page + 1) * PAGE_SIZE < conditions.size) layout[Slots.NEXT] = navBtn("Next Page", Textures.NEXT)
        layout[Slots.BACK] = navBtn("Back to Scanner", Textures.BACK)

        return layout
    }

    // ── Item builders ────────────────────────────────────────────────────────

    /** Red-tinted head with enchant glint — shows it's actively blocking something */
    private fun conditionEntry(condition: String): ItemStack {
        val item = CustomGui.createPlayerHeadButton(
            "exc_$condition",
            Text.literal(condition).formatted(Formatting.RED),
            listOf(
                Text.literal(""),
                Text.literal("§c§lEXCLUDED").formatted(Formatting.BOLD),
                Text.literal("§7Spawns matching this string"),
                Text.literal("§7will be blocked."),
                Text.literal(""),
                Text.literal("§eClick §7to remove")
            ),
            Textures.ENTRY
        )
        CustomGui.addEnchantmentGlint(item)
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
