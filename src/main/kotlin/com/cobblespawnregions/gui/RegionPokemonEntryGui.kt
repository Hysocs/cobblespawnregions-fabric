package com.cobblespawnregions.gui

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.item.PokemonItem
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

/**
 * Per-entry settings GUI, opened by right-clicking a selected Pokémon
 * in [RegionPokemonSelectionGui].
 *
 * Layout (54 slots):
 *   Row 0  (0–8) : purple glass header
 *   Slot   4     : Pokémon identity display (PokemonItem head)
 *
 *   Row 2, 20–24 : Max Spawn Count inline controls
 *     20 = −10   21 = −1   [22 = DISPLAY]   23 = +1   24 = +10
 *
 *   Row 5, slot 49: Back → RegionPokemonSelectionGui
 *
 * More settings will be added here in future iterations.
 */
object RegionPokemonEntryGui {

    private const val MON_DISPLAY  = 4
    private const val MSC_DEC_10   = 20
    private const val MSC_DEC_1    = 21
    private const val MSC_DISPLAY  = 22
    private const val MSC_INC_1    = 23
    private const val MSC_INC_10   = 24
    private const val BLOCKS_SLOT  = 31
    private const val BACK_SLOT    = 49

    private const val MIN_COUNT    = 0
    private const val MAX_COUNT    = 100

    private object Textures {
        const val DEC  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val INC  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val BACK   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        const val BLOCKS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ4YjUxZGM5NTljMzNjMjUxNWJhZDY1ODk5N2Y2Y2VlOWY4NmRmMGU3ODdiNmM2ZjhkNTA3MDY0N2JkYyJ9fX0="
    }

    // ── Open ──────────────────────────────────────────────────────────────────

    fun open(
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>
    ) {
        val entry = RegionsConfig.getPokemonFromRegion(regionId, pokemonName, formName, aspects)
        if (entry == null) {
            player.sendMessage(Text.literal("§c[CSR] Entry not found — was it deselected?"), false)
            RegionPokemonSelectionGui.open(player, regionId)
            return
        }

        val title = buildTitle(pokemonName, formName, aspects)
        CustomGui.openGui(
            player,
            title,
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
            MSC_DEC_10 -> adjustMaxSpawnCount(player, regionId, pokemonName, formName, aspects, -10)
            MSC_DEC_1  -> adjustMaxSpawnCount(player, regionId, pokemonName, formName, aspects, -1)
            MSC_INC_1  -> adjustMaxSpawnCount(player, regionId, pokemonName, formName, aspects, +1)
            MSC_INC_10 -> adjustMaxSpawnCount(player, regionId, pokemonName, formName, aspects, +10)
            BLOCKS_SLOT -> RegionSpawnBlocksGui.open(player, regionId, pokemonName, formName, aspects)
            BACK_SLOT  -> RegionPokemonSelectionGui.open(player, regionId)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun adjustMaxSpawnCount(
        player: ServerPlayerEntity,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>,
        delta: Int
    ) {
        RegionsConfig.updatePokemonInRegion(regionId, pokemonName, formName, aspects) { entry ->
            entry.maxSpawnCount = (entry.maxSpawnCount + delta).coerceIn(MIN_COUNT, MAX_COUNT)
        }
        CustomGui.refreshGui(player, buildLayout(regionId, pokemonName, formName, aspects))
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>
    ): List<ItemStack> {
        val layout = MutableList(54) { filler() }
        val entry  = RegionsConfig.getPokemonFromRegion(regionId, pokemonName, formName, aspects)
        val count  = entry?.maxSpawnCount ?: 0

        for (i in 0..8) layout[i] = purpleGlass()

        layout[MON_DISPLAY] = monDisplayItem(pokemonName, formName, aspects)
        layout[MSC_DEC_10]  = adjBtn("§c-10", Textures.DEC)
        layout[MSC_DEC_1]   = adjBtn("§c-1",  Textures.DEC)
        layout[MSC_DISPLAY] = maxSpawnCountBtn(count, pokemonName)
        layout[MSC_INC_1]   = adjBtn("§a+1",  Textures.INC)
        layout[MSC_INC_10]  = adjBtn("§a+10", Textures.INC)
        layout[BLOCKS_SLOT] = spawnBlocksBtn(entry)
        layout[BACK_SLOT]   = backBtn()

        return layout
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private fun monDisplayItem(
        pokemonName: String,
        formName: String?,
        aspects: Set<String>
    ): ItemStack {
        return try {
            val propsStr = buildPropsString(pokemonName, formName, aspects)
            val pokemon  = PokemonProperties.parse(propsStr).create()
            val item     = PokemonItem.from(pokemon, tint = Vector4f(1f, 1f, 1f, 1f))
            item.setCustomName(Text.literal("§f§l${buildDisplayName(pokemonName, formName, aspects)}"))
            CustomGui.setItemLore(item, listOf(
                "§7Species: §f${pokemonName.replaceFirstChar(Char::titlecase)}",
                if (!formName.isNullOrEmpty() && !formName.equals("normal", ignoreCase = true))
                    "§7Form: §f$formName" else "",
                if (aspects.isNotEmpty())
                    "§7Aspects: §f${aspects.joinToString(", ") { it.replaceFirstChar(Char::titlecase) }}" else ""
            ).filter(String::isNotEmpty))
            item
        } catch (e: Exception) {
            filler()
        }
    }

    private fun maxSpawnCountBtn(count: Int, pokemonName: String) = CustomGui.createPlayerHeadButton(
        "MaxSpawnCount",
        Text.literal("Max Spawn Count").formatted(Formatting.AQUA),
        listOf(
            Text.literal("§7Max number of §f${pokemonName.replaceFirstChar(Char::titlecase)}"),
            Text.literal("§7that can be alive in this region at once."),
            Text.literal(""),
            Text.literal("§eCurrent: §f$count §8(0 = unlimited)"),
            Text.literal(""),
            Text.literal("§8Use the arrows to adjust.")
        ),
        // reuse INC texture as a neutral "info" head
        Textures.INC
    )

    private fun adjBtn(label: String, texture: String) = CustomGui.createPlayerHeadButton(
        "Adj${label.replace("§", "")}",
        Text.literal(label).styled { it.withItalic(false) },
        emptyList(),
        texture
    )

    private fun spawnBlocksBtn(entry: com.cobblespawnregions.utils.PokemonSpawnEntry?) = CustomGui.createPlayerHeadButton(
        "SpawnBlocks",
        Text.literal("Spawn Blocks").formatted(Formatting.GREEN),
        listOf(
            Text.literal("§7Set which blocks this Pokémon"),
            Text.literal("§7is allowed to spawn on top of."),
            Text.literal(""),
            Text.literal("§7Currently set: §f${entry?.spawnSettings?.allowedBlocks?.size ?: 0} §7block(s)"),
            Text.literal("§8(0 = any block)"),
            Text.literal(""),
            Text.literal("§eClick §7to edit")
        ),
        Textures.BLOCKS
    )

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back", Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to Pokémon list")), Textures.BACK
    )

    // ── Name / props helpers ──────────────────────────────────────────────────

    private fun buildTitle(pokemonName: String, formName: String?, aspects: Set<String>): String {
        val parts = mutableListOf<String>()
        if (!formName.isNullOrEmpty() && !formName.equals("normal", ignoreCase = true)) parts.add(formName)
        aspects.forEach { if (!it.equals("shiny", ignoreCase = true)) parts.add(it.replaceFirstChar(Char::titlecase)) }
        if (aspects.any { it.equals("shiny", ignoreCase = true) }) parts.add(0, "✦ Shiny")
        val suffix = if (parts.isNotEmpty()) " (${parts.joinToString(", ")})" else ""
        return "${pokemonName.replaceFirstChar(Char::titlecase)}$suffix — Entry Settings"
    }

    private fun buildDisplayName(pokemonName: String, formName: String?, aspects: Set<String>): String {
        val parts = mutableListOf<String>()
        if (!formName.isNullOrEmpty() && !formName.equals("normal", ignoreCase = true)) parts.add(formName)
        parts.addAll(aspects.map { it.replaceFirstChar(Char::titlecase) })
        return if (parts.isNotEmpty())
            "${pokemonName.replaceFirstChar(Char::titlecase)} (${parts.joinToString(", ")})"
        else pokemonName.replaceFirstChar(Char::titlecase)
    }

    private fun buildPropsString(pokemonName: String, formName: String?, aspects: Set<String>): String =
        buildString {
            append(pokemonName.lowercase())
            if (!formName.isNullOrEmpty()
                && !formName.equals("normal", ignoreCase = true)
                && !formName.equals("default", ignoreCase = true)
            ) append(" form=${formName.lowercase()}")
            aspects.forEach { aspect ->
                if (aspect.contains("=")) append(" ${aspect.lowercase()}")
                else append(" aspect=${aspect.lowercase()}")
            }
        }

    private fun purpleGlass() = ItemStack(Items.PURPLE_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
    private fun filler()      = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply   { setCustomName(Text.literal(" ")) }
}