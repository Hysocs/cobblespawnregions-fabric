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
import net.minecraft.util.ClickType
import net.minecraft.util.Formatting
import java.util.concurrent.ConcurrentHashMap

object RegionListGui {

    private const val PAGE_SIZE = 45
    private val playerPages = ConcurrentHashMap<ServerPlayerEntity, Int>()

    private object Slots {
        const val PREV = 45
        const val SETTINGS = 49
        const val NEXT = 53
    }

    private object Textures {
        const val PREV   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val NEXT   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val REGION = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
        const val SETTINGS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZWI2Y2VlOGZkYTdlZjBiM2FlMGViMDU3OWQ1Njc2Y2UzNmFmN2VmYzU3NGQ4ODcyOGYzODk0ZjZiMTY2NTM4In19fQ=="
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
        val regions = RegionsConfig.regionsInPriorityOrder()

        when (ctx.slotIndex) {
            Slots.PREV -> if (page > 0) open(player, page - 1)
            Slots.SETTINGS -> RegionGlobalSettingsGui.open(player, page)
            Slots.NEXT -> if ((page + 1) * PAGE_SIZE < regions.size) open(player, page + 1)
            in 0 until PAGE_SIZE -> {
                val idx = page * PAGE_SIZE + ctx.slotIndex
                if (idx < regions.size) {
                    val regionId = regions[idx].regionId
                    when (ctx.clickType) {
                        ClickType.RIGHT -> RegionDeleteGui.open(player, regionId, page)
                        else -> RegionEditorGui.open(player, regionId)
                    }
                }
            }
        }
    }

    private fun buildLayout(page: Int): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val regions = RegionsConfig.regionsInPriorityOrder()
        val start = page * PAGE_SIZE
        val end   = minOf(start + PAGE_SIZE, regions.size)

        for (i in start until end) {
            layout[i - start] = regionItem(regions[i])
        }

        if (page > 0)                              layout[Slots.PREV] = navBtn("Previous Page", Textures.PREV)
        layout[Slots.SETTINGS] = settingsBtn()
        if ((page + 1) * PAGE_SIZE < regions.size) layout[Slots.NEXT] = navBtn("Next Page",     Textures.NEXT)

        return layout
    }

    private fun regionItem(r: RegionData): ItemStack {
        val restr = r.spawnRestrictions
        return CustomGui.createPlayerHeadButton(
            "region_${r.regionId}",
            Text.literal(r.regionName).formatted(Formatting.YELLOW),
            listOf(
                Text.literal("§8${r.regionId}  [${r.mode}]"),
                Text.literal("§7Dimension: §f${r.dimension}"),
                Text.literal("§7Priority: §f${r.priority}"),
                Text.literal("§8Higher priority controls overlaps."),
                Text.literal("§7Disable All: ${flag(restr.disableAll)}"),
                Text.literal("§7Custom Spawns: §f${r.selectedPokemon.size}"),
                Text.literal(""),
                Text.literal("§eLeft-click §7to edit"),
                Text.literal("§cRight-click §7to delete")
            ),
            Textures.REGION
        )
    }

    private fun navBtn(label: String, texture: String) = CustomGui.createPlayerHeadButton(
        label.replace(" ", ""),
        Text.literal(label).formatted(Formatting.GREEN),
        emptyList(), texture
    )

    private fun settingsBtn() = CustomGui.createPlayerHeadButton(
        "GlobalSettings",
        Text.literal("Global Settings").formatted(Formatting.GOLD),
        listOf(
            Text.literal("§7Controls region picker display options."),
            Text.literal("§7Forms, aspects, unimplemented Pokemon, and debug logging."),
            Text.literal(""),
            Text.literal("§eClick §7to configure")
        ),
        Textures.SETTINGS
    )

    private fun flag(b: Boolean) = if (b) "§atrue" else "§cfalse"
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
