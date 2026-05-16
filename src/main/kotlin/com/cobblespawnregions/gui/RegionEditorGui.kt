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

/**
 * Top-level editor for one priority region.
 * Detailed natural and custom-spawn settings live in their own screens.
 */
object RegionEditorGui {

    private object Slots {
        const val SUMMARY = 4

        const val PRIORITY = 22

        const val NATURAL = 30
        const val CUSTOM = 32

        const val BACK = 49
    }

    private object Limits {
        const val MIN_PRIORITY = -1_000
        const val MAX_PRIORITY = 1_000
    }

    private object Textures {
        const val REGION = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
        const val NATURAL = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTdiMjE4OTMwMGYzMzliYTA1MGUwMWFlMmE1NDBiN2U4OWVmODk2YTU1Yzc5MTZkY2M5ZTU4NTFhZjg2NDExZSJ9fX0="
        const val CUSTOM = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String) {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            player.sendMessage(Text.literal("§c[CSR] Region '$regionId' not found."), false)
            return
        }
        CustomGui.openGui(
            player,
            "Region - ${region.regionName}",
            buildLayout(region),
            { ctx -> handleClick(ctx, player, region.regionId) },
            {}
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        when (ctx.slotIndex) {
            Slots.PRIORITY -> {
                val delta = if (ctx.clickType == ClickType.RIGHT) -1 else 1
                adjustPriority(player, regionId, delta)
            }

            Slots.NATURAL -> RegionNaturalSpawnGui.open(player, regionId)
            Slots.CUSTOM -> RegionUnnaturalSpawnGui.open(player, regionId)

            Slots.BACK -> RegionListGui.open(player)
        }
    }

    private fun buildLayout(region: RegionData): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        for (i in 0..8) layout[i] = glass()

        layout[Slots.SUMMARY] = summaryItem(region)

        layout[Slots.PRIORITY] = priorityItem(region)

        layout[Slots.NATURAL] = naturalSettingsItem(region)
        layout[Slots.CUSTOM] = customSpawnItem(region)

        layout[Slots.BACK] = backBtn()
        return layout
    }

    private fun refresh(player: ServerPlayerEntity, regionId: String) {
        val region = RegionsConfig.getRegion(regionId) ?: return
        CustomGui.refreshGui(player, buildLayout(region))
    }

    private fun adjustPriority(player: ServerPlayerEntity, regionId: String, delta: Int) {
        RegionsConfig.updateRegion(regionId) {
            it.priority = (it.priority + delta).coerceIn(Limits.MIN_PRIORITY, Limits.MAX_PRIORITY)
        }
        refresh(player, regionId)
    }

    private fun summaryItem(region: RegionData) = CustomGui.createPlayerHeadButton(
        "region_${region.regionId}",
        Text.literal(region.regionName).formatted(Formatting.YELLOW),
        listOf(
            Text.literal("§8${region.regionId}  [${region.mode}]"),
            Text.literal("§7Dimension: §f${region.dimension}"),
            Text.literal("§7Priority: §f${region.priority}"),
            Text.literal(""),
            Text.literal("§8Higher priority controls overlapping positions.")
        ),
        Textures.REGION
    )

    private fun priorityItem(region: RegionData): ItemStack {
        val overlaps = RegionsConfig.regions.values
            .filter { it.regionId != region.regionId && it.dimension == region.dimension && regionsOverlap(region, it) }
            .sortedWith(compareByDescending<RegionData> { it.priority }.thenBy { it.regionId })

        return ItemStack(Items.COMPARATOR).apply {
            setCustomName(Text.literal("Priority: ${region.priority}").formatted(Formatting.GOLD))
            CustomGui.setItemLore(this, buildList {
                add("§7Higher priority controls overlapping areas.")
                add("§8Tie-breaker: smaller region, then region id.")
                add("")
                add("§7Left-click: §a+1")
                add("§7Right-click: §c-1")
                add("")
                if (overlaps.isEmpty()) {
                    add("§7Overlaps: §fnone")
                } else {
                    add("§7Overlaps:")
                    overlaps.take(5).forEach {
                        add("§8- §f${it.regionName} §7priority §f${it.priority}")
                    }
                    if (overlaps.size > 5) add("§8...and ${overlaps.size - 5} more")
                }
            })
        }
    }

    private fun naturalSettingsItem(region: RegionData) = CustomGui.createPlayerHeadButton(
        "NaturalSettings",
        Text.literal("Natural Spawn Settings").formatted(Formatting.GREEN),
        listOf(
            Text.literal("§7Controls wild/natural Pokemon where"),
            Text.literal("§7this region wins priority."),
            Text.literal(""),
            Text.literal("§7Disable All: ${flag(region.spawnRestrictions.disableAll)}"),
            Text.literal("§7Blocked Species: §f${region.spawnRestrictions.disallowedSpecies.size}"),
            Text.literal("§7Labels: §f${region.spawnRestrictions.disallowedLabels.size}"),
            Text.literal("§7Conditions: §f${region.spawnRestrictions.exclusionConditions.size}"),
            Text.literal(""),
            Text.literal("§eClick §7to configure")
        ),
        Textures.NATURAL
    )

    private fun customSpawnItem(region: RegionData) = CustomGui.createPlayerHeadButton(
        "CustomSpawns",
        Text.literal("Custom Spawns").formatted(Formatting.LIGHT_PURPLE),
        listOf(
            Text.literal("§7Pokemon this region spawns itself"),
            Text.literal("§7where it wins priority."),
            Text.literal(""),
            Text.literal("§7Configured Pokemon: §f${region.selectedPokemon.size}"),
            Text.literal("§7Timer: §f${region.spawnTimerTicks} ticks §8(${region.spawnTimerTicks / 20.0}s)"),
            Text.literal("§7Max Alive: §f${region.maxTotalSpawns} §8(0 = unlimited)"),
            Text.literal(""),
            Text.literal("§eClick §7to configure")
        ),
        Textures.CUSTOM
    )


    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back",
        Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to region list")),
        Textures.BACK
    )

    private fun regionsOverlap(a: RegionData, b: RegionData): Boolean {
        val aMinX = minOf(a.pos1.x, a.pos2.x); val aMaxX = maxOf(a.pos1.x, a.pos2.x)
        val aMinY = minOf(a.pos1.y, a.pos2.y); val aMaxY = maxOf(a.pos1.y, a.pos2.y)
        val aMinZ = minOf(a.pos1.z, a.pos2.z); val aMaxZ = maxOf(a.pos1.z, a.pos2.z)
        val bMinX = minOf(b.pos1.x, b.pos2.x); val bMaxX = maxOf(b.pos1.x, b.pos2.x)
        val bMinY = minOf(b.pos1.y, b.pos2.y); val bMaxY = maxOf(b.pos1.y, b.pos2.y)
        val bMinZ = minOf(b.pos1.z, b.pos2.z); val bMaxZ = maxOf(b.pos1.z, b.pos2.z)
        return aMinX <= bMaxX && aMaxX >= bMinX &&
                aMinY <= bMaxY && aMaxY >= bMinY &&
                aMinZ <= bMaxZ && aMaxZ >= bMinZ
    }

    private fun flag(b: Boolean) = if (b) "§atrue" else "§cfalse"
    private fun glass() = ItemStack(Items.CYAN_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
