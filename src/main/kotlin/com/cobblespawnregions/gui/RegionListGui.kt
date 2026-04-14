package com.cobblespawnregions.gui

import com.cobblespawnregions.utils.RegionData
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

object RegionListGui {

    private const val PAGE_SIZE = 45
    private val playerPages = ConcurrentHashMap<ServerPlayerEntity, Int>()

    private object Slots {
        const val PREV = 45
        const val NEXT = 53
    }

    private object Textures {
        const val PREV   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val NEXT   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val REGION = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
    }

    fun open(player: ServerPlayerEntity, page: Int = 0) {
        playerPages[player] = page
        CustomGui.openGui(
            player,
            "Spawn Regions — Edit",
            buildLayout(page),
            { ctx -> handleClick(ctx, player) },
            { playerPages.remove(player) }
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity) {
        val page = playerPages[player] ?: 0
        val regions = RegionsConfig.regions.values.toList()

        when (ctx.slotIndex) {
            Slots.PREV -> if (page > 0) open(player, page - 1)
            Slots.NEXT -> if ((page + 1) * PAGE_SIZE < regions.size) open(player, page + 1)
            in 0 until PAGE_SIZE -> {
                val idx = page * PAGE_SIZE + ctx.slotIndex
                if (idx < regions.size) RegionEditorGui.open(player, regions[idx].regionId)
            }
        }
    }

    private fun buildLayout(page: Int): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val regions = RegionsConfig.regions.values.toList()
        val start = page * PAGE_SIZE
        val end   = minOf(start + PAGE_SIZE, regions.size)

        for (i in start until end) {
            layout[i - start] = regionItem(regions[i])
        }

        if (page > 0)                              layout[Slots.PREV] = navBtn("Previous Page", Textures.PREV)
        if ((page + 1) * PAGE_SIZE < regions.size) layout[Slots.NEXT] = navBtn("Next Page",     Textures.NEXT)

        return layout
    }

    private fun regionItem(r: RegionData): ItemStack {
        val subs = r.subRegions.size
        val restr = r.spawnRestrictions
        return CustomGui.createPlayerHeadButton(
            "region_${r.regionId}",
            Text.literal(r.regionName).formatted(Formatting.YELLOW),
            listOf(
                Text.literal("§8${r.regionId}  [${r.mode}]"),
                Text.literal("§7Dimension: §f${r.dimension}"),
                Text.literal("§7Sub-regions: §f$subs"),
                Text.literal("§7Disable All: ${flag(restr.disableAll)}"),
                Text.literal(""),
                Text.literal("§eClick §7to edit")
            ),
            Textures.REGION
        )
    }

    private fun navBtn(label: String, texture: String) = CustomGui.createPlayerHeadButton(
        label.replace(" ", ""),
        Text.literal(label).formatted(Formatting.GREEN),
        emptyList(), texture
    )

    private fun flag(b: Boolean) = if (b) "§atrue" else "§cfalse"
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}