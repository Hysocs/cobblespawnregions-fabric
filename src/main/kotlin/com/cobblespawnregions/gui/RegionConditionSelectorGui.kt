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
import java.util.concurrent.ConcurrentHashMap

object RegionConditionSelectorGui {

    private const val PAGE_SIZE = 45
    private val playerPages = ConcurrentHashMap<ServerPlayerEntity, Int>()
    private val playerConditions = ConcurrentHashMap<ServerPlayerEntity, List<String>>()

    private object Slots {
        const val PREV = 45
        const val BACK = 49
        const val NEXT = 53
    }

    private object Textures {
        const val PREV   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val NEXT   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val BACK   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        const val COND   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmViNTg4YjIxYTZmOThhZDFmZjRlMDg1YzU1MmRjYjA1MGVmYzljYWI0MjdmNDcyNjkwOTYxMjg5N2I5Zjk3In19fQ=="
    }

    fun open(player: ServerPlayerEntity, regionId: String, conditions: List<String>, page: Int = 0) {
        playerPages[player] = page
        playerConditions[player] = conditions

        val label = RegionsConfig.scopeLabel(regionId)
        CustomGui.openGui(
            player,
            "Exclusion Conditions — $label",
            buildLayout(player, regionId),
            { ctx -> handleClick(ctx, player, regionId) },
            {}
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        val conditions = playerConditions[player] ?: return
        val page = playerPages[player] ?: 0

        when (ctx.slotIndex) {
            Slots.PREV -> if (page > 0) { playerPages[player] = page - 1; refresh(player, regionId) }
            Slots.NEXT -> if ((page + 1) * PAGE_SIZE < conditions.size) { playerPages[player] = page + 1; refresh(player, regionId) }
            Slots.BACK -> RegionConditionScannerGui.open(player, regionId, 0)
            in 0 until PAGE_SIZE -> {
                val idx = page * PAGE_SIZE + ctx.slotIndex
                if (idx < conditions.size) toggleCondition(player, regionId, conditions[idx])
            }
        }
    }

    private fun toggleCondition(player: ServerPlayerEntity, regionId: String, condition: String) {
        val restr = RegionsConfig.getRestriction(regionId) ?: return

        // TARGET: exclusionConditions instead of disallowedSpecies
        if (restr.exclusionConditions.contains(condition)) {
            restr.exclusionConditions.remove(condition)
        } else {
            restr.exclusionConditions.add(condition)
        }

        RegionsConfig.saveRegion(regionId)
        refresh(player, regionId)
    }

    private fun refresh(player: ServerPlayerEntity, regionId: String) {
        CustomGui.refreshGui(player, buildLayout(player, regionId))
    }

    private fun buildLayout(player: ServerPlayerEntity, regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val conditions = playerConditions[player] ?: return layout
        val page = playerPages[player] ?: 0

        // CHECK AGAINST: exclusionConditions
        val blocked = RegionsConfig.getRestriction(regionId)?.exclusionConditions ?: emptySet<String>()

        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, conditions.size)

        for (i in start until end) {
            val cond = conditions[i]
            val isBlocked = cond in blocked
            layout[i - start] = conditionItem(cond, isBlocked)
        }

        if (page > 0) layout[Slots.PREV] = navBtn("Previous Page", Textures.PREV)
        if ((page + 1) * PAGE_SIZE < conditions.size) layout[Slots.NEXT] = navBtn("Next Page", Textures.NEXT)
        layout[Slots.BACK] = navBtn("Back to Species List", Textures.BACK)

        return layout
    }

    private fun conditionItem(condition: String, isBlocked: Boolean): ItemStack {
        val item = CustomGui.createPlayerHeadButton(
            "cond_$condition",
            Text.literal(condition).formatted(if (isBlocked) Formatting.RED else Formatting.GREEN),
            listOf(
                Text.literal(""),
                Text.literal(if (isBlocked) "§c§lEXCLUDED" else "§a§lALLOWED"),
                Text.literal("§7Click to toggle this condition in"),
                Text.literal("§7exclusionConditions.")
            ),
            AlphabetHeadTextures.forFirstLetter(condition, Textures.COND)
        )
        if (isBlocked) CustomGui.addEnchantmentGlint(item)
        return item
    }

    private fun navBtn(label: String, texture: String) = CustomGui.createPlayerHeadButton(
        label.replace(" ", ""), Text.literal(label).formatted(Formatting.AQUA), emptyList(), texture
    )

    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
