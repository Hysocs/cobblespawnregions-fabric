package com.cobblespawnregions.gui

import com.everlastingutils.gui.CustomScreenHandler
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity

fun ServerPlayerEntity.refreshGuiSlots(vararg updates: Pair<Int, ItemStack>) {
    val current = currentScreenHandler as? CustomScreenHandler ?: return
    val syncId = current.syncId

    server.execute {
        val handler = currentScreenHandler as? CustomScreenHandler ?: return@execute
        if (handler.syncId != syncId) return@execute

        updates.forEach { (slot, stack) ->
            if (slot in 0 until handler.rows * 9) {
                handler.getSlot(slot).setStack(stack.copy())
            }
        }
        handler.sendContentUpdates()
    }
}
