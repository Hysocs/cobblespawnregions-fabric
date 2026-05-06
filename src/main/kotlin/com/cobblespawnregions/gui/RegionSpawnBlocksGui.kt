package com.cobblespawnregions.gui

import com.cobblespawnregions.utils.RegionsConfig
import com.everlastingutils.gui.CustomGui
import com.everlastingutils.gui.InteractionContext
import com.everlastingutils.gui.setCustomName
import net.minecraft.block.Blocks
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * Spawn-blocks picker for a single [PokemonSpawnEntry].
 *
 * Special wildcard tokens (stored as strings in allowedBlocks):
 *   "#solid"  — any non-air, non-water block
 *   "#water"  — minecraft:water
 *   "#air"    — any air block
 *
 * Default set (set in RegionsConfig.createDefaultPokemonEntry):
 *   ["#solid", "#water", "#air"]  — spawns anywhere
 *
 * Mechanics (mirrors LootPoolSelectionGui drag-and-drop pattern):
 *   • Content slots (0–44) are EMPTY so the player's cursor item passes through.
 *   • Drag a block item into any empty content slot → adds its block ID.
 *   • Left-click a filled slot with an empty hand → removes that entry.
 *   • Bottom buttons add the three wildcard tokens.
 *
 * Layout (54 slots):
 *   Slots  0–44 : drag-and-drop block grid   (ItemStack.EMPTY when vacant)
 *   Slot  45    : info / hint button
 *   Slot  46    : Add Solid button
 *   Slot  47    : Add Air button
 *   Slot  48    : Add Water button
 *   Slot  49    : Back → RegionPokemonEntryGui
 *   Slots 50–53 : black glass filler
 */
object RegionSpawnBlocksGui {

    private const val CONTENT_SIZE     = 45   // slots 0–44
    private const val INFO_SLOT        = 45
    private const val ADD_SOLID_SLOT   = 46
    private const val ADD_AIR_SLOT     = 47
    private const val ADD_WATER_SLOT   = 48
    private const val BACK_SLOT        = 49

    // Wildcard token constants — must match RegionSpawnHelper
    const val TOKEN_SOLID = "#solid"
    const val TOKEN_WATER = "#water"
    const val TOKEN_AIR   = "#air"

    private object Textures {
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        const val INFO = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1ZWU0MTlhZDljMDYwYzE2Y2I1M2IxZGNmZmFjOGJhY2EwYjJhMjI2NWIxYjZjN2U4ZTc4MGMzN2IxMDRjMCJ9fX0="
    }

    // ── Open ──────────────────────────────────────────────────────────────────

    fun open(
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>
    ) {
        if (RegionsConfig.getPokemonFromRegion(regionId, pokemonName, formName, aspects) == null) {
            player.sendMessage(Text.literal("§c[CSR] Entry not found — was it removed?"), false)
            RegionPokemonSelectionGui.open(player, regionId)
            return
        }
        CustomGui.openGui(
            player,
            "${pokemonName.replaceFirstChar(Char::titlecase)} — Spawn Blocks",
            buildLayout(regionId, pokemonName, formName, aspects),
            { ctx -> handleClick(ctx, player, regionId, pokemonName, formName, aspects) },
            {}
        )
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    private fun handleClick(
        ctx: InteractionContext,
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>
    ) {
        when (ctx.slotIndex) {
            ADD_SOLID_SLOT -> addToken(player, regionId, pokemonName, formName, aspects, TOKEN_SOLID)
            ADD_AIR_SLOT   -> addToken(player, regionId, pokemonName, formName, aspects, TOKEN_AIR)
            ADD_WATER_SLOT -> addToken(player, regionId, pokemonName, formName, aspects, TOKEN_WATER)
            BACK_SLOT      -> RegionPokemonEntryGui.open(player, regionId, pokemonName, formName, aspects)
            INFO_SLOT      -> { /* read-only info */ }

            in 0 until CONTENT_SIZE -> {
                val cursor = player.currentScreenHandler.getCursorStack()
                when {
                    // ── Drag-and-drop: player holds a block item, slot is empty ──
                    !cursor.isEmpty && ctx.clickedStack.isEmpty ->
                        addBlockFromItem(player, regionId, pokemonName, formName, aspects, cursor)

                    // ── Left-click filled slot with empty hand: remove entry ──
                    cursor.isEmpty && !ctx.clickedStack.isEmpty ->
                        removeAtIndex(player, regionId, pokemonName, formName, aspects, ctx.slotIndex)
                }
            }
        }
    }

    // ── Block / token manipulation ────────────────────────────────────────────

    private fun addBlockFromItem(
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>,
        cursor: ItemStack
    ) {
        val itemId  = Registries.ITEM.getId(cursor.item)
        val blockId = itemId.toString()

        // Verify a real (non-air) block exists with this ID
        val block = Registries.BLOCK.get(Identifier.of(itemId.namespace, itemId.path))
        if (block == Blocks.AIR) {
            player.sendMessage(
                Text.literal("§c[CSR] §f${cursor.item.name.string} §cisn't a placeable block. " +
                        "Use the buttons below for Air/Water/Solid."),
                false
            )
            return
        }
        player.currentScreenHandler.setCursorStack(ItemStack.EMPTY)
        addEntry(player, regionId, pokemonName, formName, aspects, blockId)
    }

    private fun addToken(
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>,
        token: String
    ) = addEntry(player, regionId, pokemonName, formName, aspects, token)

    private fun addEntry(
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>,
        blockId: String
    ) {
        var alreadyPresent = false
        var full = false
        RegionsConfig.updatePokemonInRegion(regionId, pokemonName, formName, aspects) { entry ->
            val list = entry.spawnSettings.allowedBlocks.toMutableList()
            when {
                blockId in list           -> alreadyPresent = true
                list.size >= CONTENT_SIZE -> full = true
                else -> {
                    list.add(blockId)
                    entry.spawnSettings.allowedBlocks = list
                }
            }
        }
        when {
            alreadyPresent -> player.sendMessage(Text.literal("§7[CSR] §f$blockId §7is already in the list."), false)
            full           -> player.sendMessage(Text.literal("§c[CSR] Block list is full (max $CONTENT_SIZE)."), false)
            else           -> player.sendMessage(Text.literal("§a[CSR] Added §f$blockId§a."), false)
        }
        CustomGui.refreshGui(player, buildLayout(regionId, pokemonName, formName, aspects))
    }

    private fun removeAtIndex(
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>,
        slotIndex: Int
    ) {
        val entry  = RegionsConfig.getPokemonFromRegion(regionId, pokemonName, formName, aspects) ?: return
        val blocks = entry.spawnSettings.allowedBlocks
        if (slotIndex >= blocks.size) return

        val removed = blocks[slotIndex]
        RegionsConfig.updatePokemonInRegion(regionId, pokemonName, formName, aspects) { e ->
            val list = e.spawnSettings.allowedBlocks.toMutableList()
            list.removeAt(slotIndex)
            e.spawnSettings.allowedBlocks = list
        }
        player.sendMessage(Text.literal("§c[CSR] Removed §f$removed§c."), false)
        CustomGui.refreshGui(player, buildLayout(regionId, pokemonName, formName, aspects))
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>
    ): List<ItemStack> {
        // IMPORTANT: content slots (0–44) MUST be ItemStack.EMPTY so that the
        // player's cursor item is not blocked by a glass pane.
        val layout = MutableList(54) { slot ->
            if (slot < CONTENT_SIZE) ItemStack.EMPTY else bottomFiller()
        }

        val entry  = RegionsConfig.getPokemonFromRegion(regionId, pokemonName, formName, aspects)
        val blocks = entry?.spawnSettings?.allowedBlocks ?: emptyList()

        // Render each allowed block / token into the grid
        blocks.forEachIndexed { i, blockId ->
            if (i < CONTENT_SIZE) layout[i] = entryItem(blockId)
        }

        // Bottom bar
        layout[INFO_SLOT]      = infoBtn(blocks.size)
        layout[ADD_SOLID_SLOT] = addSolidBtn()
        layout[ADD_AIR_SLOT]   = addAirBtn()
        layout[ADD_WATER_SLOT] = addWaterBtn()
        layout[BACK_SLOT]      = backBtn()

        return layout
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    /** Renders a single entry (token or literal block ID) as a clickable item. */
    private fun entryItem(blockId: String): ItemStack {
        val (item, coloredName) = when (blockId) {
            TOKEN_SOLID -> ItemStack(Items.STONE)               to "§7All Solid Blocks"
            TOKEN_WATER -> ItemStack(Items.WATER_BUCKET)        to "§9All Water Blocks"
            TOKEN_AIR   -> ItemStack(Items.WHITE_STAINED_GLASS) to "§fAll Air Blocks"
            else -> {
                val id    = Identifier.tryParse(blockId)
                val block = if (id != null) Registries.BLOCK.get(id) else null
                val bItem = block?.asItem()?.let { if (it == Items.AIR) null else it }
                (if (bItem != null) ItemStack(bItem) else ItemStack(Items.BARRIER)) to
                        "§f${block?.name?.string ?: blockId}"
            }
        }
        item.setCustomName(
            Text.literal("$coloredName §8($blockId)").styled { it.withItalic(false) }
        )
        CustomGui.setItemLore(item, listOf(Text.literal("§eLeft-click §7to remove")))
        return item
    }

    private fun addSolidBtn() = ItemStack(Items.STONE).apply {
        setCustomName(Text.literal("§7Add: All Solid Blocks").styled { it.withItalic(false) })
        CustomGui.setItemLore(this, listOf(
            Text.literal("§7Allows spawning on any solid"),
            Text.literal("§7(non-air, non-water) block."),
            Text.literal(""),
            Text.literal("§eClick §7to add")
        ))
    }

    private fun addAirBtn() = ItemStack(Items.WHITE_STAINED_GLASS).apply {
        setCustomName(Text.literal("§fAdd: All Air Blocks").styled { it.withItalic(false) })
        CustomGui.setItemLore(this, listOf(
            Text.literal("§7Allows spawning above air"),
            Text.literal("§7(e.g. floating platforms)."),
            Text.literal(""),
            Text.literal("§eClick §7to add")
        ))
    }

    private fun addWaterBtn() = ItemStack(Items.WATER_BUCKET).apply {
        setCustomName(Text.literal("§9Add: All Water Blocks").styled { it.withItalic(false) })
        CustomGui.setItemLore(this, listOf(
            Text.literal("§7Allows spawning on water surfaces."),
            Text.literal(""),
            Text.literal("§eClick §7to add")
        ))
    }

    private fun infoBtn(currentCount: Int) = CustomGui.createPlayerHeadButton(
        "SpawnBlocksInfo",
        Text.literal("Spawn Blocks").formatted(Formatting.YELLOW),
        listOf(
            Text.literal("§7Set which blocks this Pokémon"),
            Text.literal("§7is allowed to spawn on top of."),
            Text.literal(""),
            Text.literal("§7Currently set: §f$currentCount §7block(s)"),
            Text.literal("§8(0 = any block — same as all three wildcards)"),
            Text.literal(""),
            Text.literal("§eDrag §7a block from inventory into"),
            Text.literal("§7an empty slot to add a specific block."),
            Text.literal("§eLeft-click §7a block to remove it.")
        ),
        Textures.INFO
    )

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back", Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to Pokémon entry settings")), Textures.BACK
    )

    private fun bottomFiller() = ItemStack(Items.BLACK_STAINED_GLASS_PANE).apply {
        setCustomName(Text.literal(" "))
    }
}