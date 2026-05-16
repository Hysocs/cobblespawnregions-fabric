package com.cobblespawnregions.gui

import com.cobblespawnregions.CobbleSpawnRegions
import com.cobblespawnregions.utils.RegionsConfig
import com.cobblespawnregions.utils.SpawnPointStore
import com.everlastingutils.gui.CustomGui
import com.everlastingutils.gui.InteractionContext
import com.everlastingutils.gui.setCustomName
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RegionDeleteGui {

    private val confirming = ConcurrentHashMap<UUID, String>()

    private object Slots {
        const val SUMMARY = 13
        const val DELETE = 31
        const val BACK = 49
    }

    private object Textures {
        const val REGION = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    fun open(player: ServerPlayerEntity, regionId: String, returnPage: Int = 0) {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            player.sendMessage(Text.literal("§c[CSR] Region '$regionId' not found."), false)
            RegionListGui.open(player, returnPage)
            return
        }

        CustomGui.openGui(
            player,
            "Delete Region - ${region.regionName}",
            buildLayout(player, regionId),
            { ctx -> handleClick(ctx, player, regionId, returnPage) },
            { confirming.remove(player.uuid, regionId) }
        )
    }

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String, returnPage: Int) {
        when (ctx.slotIndex) {
            Slots.DELETE -> handleDelete(player, regionId, returnPage)
            Slots.BACK -> {
                confirming.remove(player.uuid, regionId)
                RegionListGui.open(player, returnPage)
            }
        }
    }

    private fun handleDelete(player: ServerPlayerEntity, regionId: String, returnPage: Int) {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            confirming.remove(player.uuid, regionId)
            RegionListGui.open(player, returnPage)
            return
        }

        if (confirming[player.uuid] != regionId) {
            confirming[player.uuid] = regionId
            CustomGui.refreshGui(player, buildLayout(player, regionId))
            return
        }

        confirming.remove(player.uuid, regionId)
        if (RegionsConfig.removeRegion(regionId)) {
            val affectedPlayers = mutableListOf<java.util.UUID>()
            CobbleSpawnRegions.activeVisualizations.entries.removeIf { entry ->
                if (entry.value.remove(regionId)) affectedPlayers.add(entry.key)
                entry.value.isEmpty()
            }
            affectedPlayers.forEach(CobbleSpawnRegions::requestParticleUpdate)
            SpawnPointStore.clearRegion(regionId)
            player.sendMessage(Text.literal("§a[CSR] §fDeleted region §e${region.regionName}§f."), false)
        } else {
            player.sendMessage(Text.literal("§c[CSR] Region '$regionId' was already deleted."), false)
        }
        RegionListGui.open(player, returnPage)
    }

    private fun buildLayout(player: ServerPlayerEntity, regionId: String): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val region = RegionsConfig.getRegion(regionId)

        if (region != null) {
            layout[Slots.SUMMARY] = CustomGui.createPlayerHeadButton(
                "delete_region_${region.regionId}",
                Text.literal(region.regionName).formatted(Formatting.YELLOW),
                listOf(
                    Text.literal("§8${region.regionId}  [${region.mode}]"),
                    Text.literal("§7Dimension: §f${region.dimension}"),
                    Text.literal("§7Priority: §f${region.priority}"),
                    Text.literal("§7Custom Spawns: §f${region.selectedPokemon.size}")
                ),
                Textures.REGION
            )
        }

        layout[Slots.DELETE] = deleteBtn(confirming[player.uuid] == regionId)
        layout[Slots.BACK] = backBtn()
        return layout
    }

    private fun deleteBtn(isConfirming: Boolean): ItemStack {
        val label = if (isConfirming) "Click Again to Confirm" else "Delete Region"
        val lore = if (isConfirming) {
            listOf("§cThis cannot be undone.", "§eClick again §7to permanently delete.")
        } else {
            listOf("§7Removes the region config and cached spawn points.", "§eClick §7to arm deletion.")
        }

        return ItemStack(if (isConfirming) Items.REDSTONE_BLOCK else Items.BARRIER).apply {
            setCustomName(Text.literal(label).formatted(Formatting.RED))
            CustomGui.setItemLore(this, lore)
        }
    }

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back",
        Text.literal("Back").formatted(Formatting.GREEN),
        listOf(Text.literal("§7Return to region list")),
        Textures.BACK
    )

    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
