package com.cobblespawnregions.gui

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.feature.ChoiceSpeciesFeatureProvider
import com.cobblemon.mod.common.api.pokemon.feature.SpeciesFeatures
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Species
import com.cobblespawnregions.utils.PokemonSpawnEntry
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
import org.joml.Vector4f
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

enum class RegionSortMethod { ALPHABETICAL, TYPE, SELECTED, SEARCH }

/**
 * Full-screen Pokémon picker for a region's unnatural spawn list.
 *
 * Left-click  → toggle a Pokémon in / out of the region's [selectedPokemon].
 * Right-click → opens the selected Pokémon's per-entry settings.
 * Sort button → left-click cycles sort method; right-click opens search anvil.
 *
 * Navigation:
 *   Slot 45 — Prev page
 *   Slot 48 — Sort / Search
 *   Slot 49 — Back to [RegionUnnaturalSpawnGui]
 *   Slot 53 — Next page
 */
object RegionPokemonSelectionGui {

    // ── State ─────────────────────────────────────────────────────────────────

    var sortMethod = RegionSortMethod.ALPHABETICAL
    var searchTerm = ""

    private val playerPages       = ConcurrentHashMap<ServerPlayerEntity, Int>()
    private val playerComputations = ConcurrentHashMap<ServerPlayerEntity, CompletableFuture<Void>>()

    // Variant cache — invalidated on open / sort change
    private var cachedVariants:    List<SpeciesFormVariant>? = null
    private var cachedSortMethod:  RegionSortMethod?         = null
    private var cachedSearchTerm:  String?                   = null
    private var cachedConfigKey:   String?                   = null
    private val additionalAspectsCache = ConcurrentHashMap<String, List<Set<String>>>()

    // ── Slots ─────────────────────────────────────────────────────────────────

    private object Slots {
        const val PREV = 45
        const val SORT = 48
        const val BACK = 49
        const val NEXT = 53
    }

    // ── Textures ──────────────────────────────────────────────────────────────

    private object Textures {
        const val PREV = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTMzYWQ1YzIyZGIxNjQzNWRhYWQ2MTU5MGFiYTUxZDkzNzkxNDJkZDU1NmQ2YzQyMmE3MTEwY2EzYWJlYTUwIn19fQ=="
        const val NEXT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0MDNjYzdiYmFjNzM2NzBiZDU0M2Y2YjA5NTViYWU3YjhlOTEyM2Q4M2JkNzYwZjYyMDRjNWFmZDhiZTdlMSJ9fX0="
        const val SORT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1ZWU0MTlhZDljMDYwYzE2Y2I1M2IxZGNmZmFjOGJhY2EwYjJhMjI2NWIxYjZjN2U4ZTc4MGMzN2IxMDRjMCJ9fX0="
        const val BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    private object Constants {
        const val NORMAL_FORM   = "Normal"
        const val STANDARD_FORM = "Standard"
        const val SHINY_ASPECT  = "shiny"
        const val PAGE_SIZE     = 45
    }

    // ── Variant model ─────────────────────────────────────────────────────────

    data class SpeciesFormVariant(
        val species: Species,
        val form: FormData,
        val additionalAspects: Set<String>
    ) {
        fun toKey(): String {
            val fn = if (form.name.equals(Constants.STANDARD_FORM, ignoreCase = true))
                Constants.NORMAL_FORM else form.name
            return "${species.showdownId()}_${fn.lowercase()}_" +
                    additionalAspects.map { it.lowercase() }.sorted().joinToString(",")
        }
    }

    // ── Open ──────────────────────────────────────────────────────────────────

    fun open(player: ServerPlayerEntity, regionId: String, page: Int = 0) {
        invalidateCache()
        playerPages[player] = page
        val region = RegionsConfig.getRegion(regionId) ?: run {
            player.sendMessage(Text.literal("§c[CSR] Region not found."), false)
            return
        }

        CustomGui.openGui(
            player,
            "Select Pokémon — ${region.regionName}",
            buildLayout(regionId, page),
            { ctx -> handleClick(ctx, player, regionId) },
            { playerPages.remove(player) }
        )
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    private fun handleClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        val page   = playerPages[player] ?: 0
        val region = RegionsConfig.getRegion(regionId) ?: return

        when (ctx.slotIndex) {
            Slots.PREV -> if (page > 0) {
                playerPages[player] = page - 1
                refresh(player, regionId)
            }
            Slots.NEXT -> {
                val total = getTotalVariantsCount(region.selectedPokemon)
                if ((page + 1) * Constants.PAGE_SIZE < total) {
                    playerPages[player] = page + 1
                    refresh(player, regionId)
                }
            }
            Slots.SORT -> handleSortClick(ctx, player, regionId)
            Slots.BACK -> RegionUnnaturalSpawnGui.open(player, regionId)
            else       -> handlePokemonClick(ctx, player, regionId)
        }
    }

    private fun handleSortClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        when (ctx.clickType) {
            ClickType.LEFT -> {
                sortMethod = when (sortMethod) {
                    RegionSortMethod.ALPHABETICAL -> RegionSortMethod.TYPE
                    RegionSortMethod.TYPE         -> RegionSortMethod.SELECTED
                    else                          -> RegionSortMethod.ALPHABETICAL
                }
                if (sortMethod != RegionSortMethod.SEARCH) searchTerm = ""
                if (sortMethod != RegionSortMethod.SELECTED) invalidateCache()
                playerPages[player] = 0
                refresh(player, regionId)
                player.sendMessage(Text.literal("§7[CSR] Sort: ${sortMethod.name}"), false)
            }
            ClickType.RIGHT -> RegionPokemonSearchGui.open(player, regionId)
        }
    }

    private fun handlePokemonClick(ctx: InteractionContext, player: ServerPlayerEntity, regionId: String) {
        val item = ctx.clickedStack
        if (item.item !is PokemonItem) return

        val (species, formName, aspects) =
            parsePokemonName(CustomGui.stripFormatting(item.name.string)) ?: return

        when (ctx.clickType) {
            ClickType.LEFT  -> togglePokemon(player, regionId, species, formName, aspects)
            ClickType.RIGHT -> {
                // Only open the entry GUI when the Pokémon is actually selected
                if (RegionsConfig.getPokemonFromRegion(regionId, species.showdownId(), formName, aspects) != null) {
                    RegionPokemonEntryGui.open(player, regionId, species.showdownId(), formName, aspects)
                }
            }
        }
    }

    // ── Toggle logic ──────────────────────────────────────────────────────────

    private fun togglePokemon(
        player: ServerPlayerEntity,
        regionId: String,
        species: Species,
        formName: String,
        aspects: Set<String>
    ) {
        val showdownId = species.showdownId()
        val existing   = RegionsConfig.getPokemonFromRegion(regionId, showdownId, formName, aspects)

        if (existing != null) {
            RegionsConfig.removePokemonFromRegion(regionId, showdownId, formName, aspects)
            player.sendMessage(Text.literal("§c[CSR] Removed ${species.name} from region."), false)
        } else {
            try {
                val entry = RegionsConfig.createDefaultPokemonEntry(showdownId, formName, aspects)
                RegionsConfig.addPokemonToRegion(regionId, entry)
                player.sendMessage(Text.literal("§a[CSR] Added ${species.name} to region."), false)
            } catch (e: IllegalArgumentException) {
                player.sendMessage(Text.literal("§c[CSR] ${e.message}"), false)
                return
            }
        }
        refresh(player, regionId)
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    private fun refresh(player: ServerPlayerEntity, regionId: String) {
        val page = playerPages[player] ?: 0
        playerComputations[player]?.cancel(true)

        val future = CompletableFuture.runAsync {
            val items = buildLayout(regionId, page)
            player.server.execute {
                CustomGui.refreshGui(player, items)
                playerComputations.remove(player)
            }
        }.exceptionally {
            playerComputations.remove(player)
            null
        }
        playerComputations[player] = future
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(regionId: String, page: Int): List<ItemStack> {
        val region = RegionsConfig.getRegion(regionId) ?: return MutableList(54) { filler() }
        val layout = generatePokemonItems(region.selectedPokemon, page).toMutableList()
        val total  = getTotalVariantsCount(region.selectedPokemon)

        layout[Slots.PREV] = if (page > 0) navBtn("Previous", Textures.PREV) else filler()
        layout[Slots.NEXT] = if ((page + 1) * Constants.PAGE_SIZE < total) navBtn("Next", Textures.NEXT) else filler()
        layout[Slots.SORT] = sortBtn()
        layout[Slots.BACK] = backBtn()
        listOf(46, 47, 50, 51, 52).forEach { layout[it] = filler() }

        return layout
    }

    private fun generatePokemonItems(selected: List<PokemonSpawnEntry>, page: Int): List<ItemStack> {
        val layout = MutableList(54) { ItemStack.EMPTY }
        getVariantsForPage(selected, page).forEachIndexed { i, variant ->
            layout[i] = pokemonItem(variant, isPokemonSelected(variant, selected), selected)
        }
        return layout
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private fun pokemonItem(
        variant: SpeciesFormVariant,
        isSelected: Boolean,
        selected: List<PokemonSpawnEntry>
    ): ItemStack {
        val showForms = RegionsConfig.config.showFormsInGui
        val props   = PokemonProperties.parse(buildPropsString(variant, showForms))
        val pokemon = props.create()
        val tint    = if (isSelected) Vector4f(1f, 1f, 1f, 1f) else Vector4f(0.3f, 0.3f, 0.3f, 1f)
        val item    = PokemonItem.from(pokemon, tint = tint)

        item.setCustomName(Text.literal((if (isSelected) "§f§n" else "§f") + buildDisplayName(variant, showForms)))

        if (isSelected) {
            CustomGui.addEnchantmentGlint(item)
            CustomGui.setItemLore(item, selectedLore(variant, findEntry(variant, selected)))
        } else {
            CustomGui.setItemLore(item, unselectedLore(variant))
        }
        return item
    }

    private fun selectedLore(variant: SpeciesFormVariant, entry: PokemonSpawnEntry?): List<String> = buildList {
        addAll(baseLore(variant))
        add("§8──────────────")
        add("§6Spawn Chance: §e${entry?.spawnChance ?: 50.0}%")
        add("§dMin Level:    §f${entry?.minLevel ?: 1}")
        add("§dMax Level:    §f${entry?.maxLevel ?: 100}")
        add("§8──────────────")
        add("§e§lLeft-click §r§7to §cDeselect")
        add("§e§lRight-click §r§7to §aEdit entry")
    }

    private fun unselectedLore(variant: SpeciesFormVariant): List<String> = buildList {
        addAll(baseLore(variant))
        add("§8──────────────")
        add("§e§lLeft-click §r§7to §aSelect")
    }

    private fun baseLore(variant: SpeciesFormVariant): List<String> = buildList {
        add("§7Type: §f${variant.species.primaryType.name}")
        variant.species.secondaryType?.let { add("§7Secondary: §f${it.name}") }
        if (variant.form.name != Constants.STANDARD_FORM) {
            val fn = if (variant.form.name == Constants.STANDARD_FORM) Constants.NORMAL_FORM else variant.form.name
            add("§7Form: §f$fn")
        }
        val aspects = displayableAspects(variant.additionalAspects)
        if (aspects.isNotEmpty()) {
            add("§7Aspects: §f${aspects.joinToString(", ")}")
        }
    }

    private fun buildPropsString(variant: SpeciesFormVariant, showForms: Boolean): String = buildString {
        append(variant.species.showdownId())
        if (showForms && variant.form.name != Constants.STANDARD_FORM) {
            if (variant.form.aspects.isNotEmpty())
                variant.form.aspects.forEach { append(" aspect=${it.lowercase()}") }
            else
                append(" form=${variant.form.formOnlyShowdownId()}")
        }
        variant.additionalAspects.forEach { aspect ->
            if (aspect.contains("=")) append(" ${aspect.lowercase()}")
            else append(" aspect=${aspect.lowercase()}")
        }
    }

    private fun buildDisplayName(variant: SpeciesFormVariant, showForms: Boolean): String {
        val parts = mutableListOf<String>()
        if (showForms || variant.form.name != Constants.STANDARD_FORM)
            parts.add(if (variant.form.name == Constants.STANDARD_FORM) Constants.NORMAL_FORM else variant.form.name)
        parts.addAll(displayableAspects(variant.additionalAspects))
        return if (parts.isNotEmpty()) "${variant.species.name} (${parts.joinToString(", ")})" else variant.species.name
    }

    private fun displayableAspects(aspects: Set<String>): List<String> {
        return when {
            RegionsConfig.config.showAspectsInGui -> aspects.map { it.replaceFirstChar(Char::titlecase) }
            aspects.any { it.equals(Constants.SHINY_ASPECT, true) } -> listOf("Shiny")
            else -> emptyList()
        }
    }

    // ── Variant list / cache ──────────────────────────────────────────────────

    private fun getAllVariants(selected: List<PokemonSpawnEntry>): List<SpeciesFormVariant> {
        val config = RegionsConfig.config
        val configKey = "${config.showUnimplementedPokemonInGui}_${config.showFormsInGui}_${config.showAspectsInGui}"
        if (sortMethod != RegionSortMethod.SELECTED &&
            cachedVariants != null &&
            cachedSortMethod == sortMethod &&
            cachedSearchTerm == searchTerm &&
            cachedConfigKey == configKey
        ) return cachedVariants!!

        val speciesList = getSortedSpecies()
        val variants = speciesList.flatMap { species ->
            val forms = if (config.showFormsInGui && species.forms.isNotEmpty()) species.forms else listOf(species.standardForm)
            val aspectSets = if (config.showAspectsInGui) additionalAspectSets(species) else emptyList()
            forms.flatMap { form ->
                val baseVariants = listOf(
                    SpeciesFormVariant(species, form, emptySet()),
                    SpeciesFormVariant(species, form, setOf(Constants.SHINY_ASPECT))
                )
                if (config.showAspectsInGui) {
                    (baseVariants + aspectSets.map { SpeciesFormVariant(species, form, it) }).distinctBy { it.toKey() }
                } else {
                    baseVariants
                }
            }.distinctBy { it.toKey() }
        }

        val result = if (sortMethod == RegionSortMethod.SELECTED)
            variants.filter { isPokemonSelected(it, selected) }
        else variants

        if (sortMethod != RegionSortMethod.SELECTED) {
            cachedVariants   = result
            cachedSortMethod = sortMethod
            cachedSearchTerm = searchTerm
            cachedConfigKey  = configKey
        }
        return result
    }

    private fun getSortedSpecies(): List<Species> {
        val showUnimplemented = RegionsConfig.config.showUnimplementedPokemonInGui
        val all = PokemonSpecies.species.filter { showUnimplemented || it.implemented }
        return when (sortMethod) {
            RegionSortMethod.ALPHABETICAL -> all.sortedBy { it.name }
            RegionSortMethod.TYPE         -> all.sortedBy { it.primaryType.name }
            RegionSortMethod.SELECTED     -> all.sortedBy { it.name }
            RegionSortMethod.SEARCH       ->
                if (searchTerm.isBlank()) all.sortedBy { it.name }
                else all.filter { it.name.lowercase().contains(searchTerm.lowercase()) }.sortedBy { it.name }
        }
    }

    private fun additionalAspectSets(species: Species): List<Set<String>> {
        return additionalAspectsCache.getOrPut(species.name.lowercase()) {
            val aspectSets = mutableSetOf(setOf(Constants.SHINY_ASPECT))
            val aspects = mutableSetOf<String>()

            species.forms.forEach { form -> form.aspects.forEach(aspects::add) }
            SpeciesFeatures.getFeaturesFor(species)
                .filterIsInstance<ChoiceSpeciesFeatureProvider>()
                .forEach { provider -> provider.getAllAspects().forEach(aspects::add) }

            aspects.forEach { aspect ->
                aspectSets.add(setOf(aspect))
                aspectSets.add(setOf(aspect, Constants.SHINY_ASPECT))
            }

            aspectSets.distinctBy { it.toSortedSet().joinToString(",") }
        }
    }

    private fun getVariantsForPage(selected: List<PokemonSpawnEntry>, page: Int): List<SpeciesFormVariant> {
        val all   = getAllVariants(selected)
        val start = page * Constants.PAGE_SIZE
        return if (start < all.size) all.subList(start, minOf(start + Constants.PAGE_SIZE, all.size)) else emptyList()
    }

    private fun getTotalVariantsCount(selected: List<PokemonSpawnEntry>): Int =
        getAllVariants(selected).size

    // ── Selection helpers ─────────────────────────────────────────────────────

    private fun isPokemonSelected(variant: SpeciesFormVariant, selected: List<PokemonSpawnEntry>): Boolean =
        selected.any {
            val fn = it.formName ?: Constants.NORMAL_FORM
            it.pokemonName.equals(variant.species.showdownId(), ignoreCase = true) &&
                    (fn.equals(variant.form.name, ignoreCase = true) ||
                            (fn == Constants.NORMAL_FORM && variant.form.name == Constants.STANDARD_FORM)) &&
                    it.aspects.map(String::lowercase).toSet() ==
                    variant.additionalAspects.map(String::lowercase).toSet()
        }

    private fun findEntry(variant: SpeciesFormVariant, selected: List<PokemonSpawnEntry>): PokemonSpawnEntry? =
        selected.find {
            val fn = it.formName ?: Constants.NORMAL_FORM
            it.pokemonName.equals(variant.species.showdownId(), ignoreCase = true) &&
                    (fn.equals(variant.form.name, ignoreCase = true) ||
                            (fn == Constants.NORMAL_FORM && variant.form.name == Constants.STANDARD_FORM)) &&
                    it.aspects.map(String::lowercase).toSet() ==
                    variant.additionalAspects.map(String::lowercase).toSet()
        }

    // ── Name parsing ──────────────────────────────────────────────────────────

    private fun parsePokemonName(name: String): Triple<Species, String, Set<String>>? {
        val match = Regex("(.*) \\((.*)\\)").find(name)
        val (speciesName, details) = if (match != null)
            match.groupValues[1] to match.groupValues[2] else name to ""

        val species = PokemonSpecies.species.find {
            it.name.equals(speciesName, ignoreCase = true)
        } ?: return null

        val parts    = details.split(", ").map(String::trim).filter(String::isNotEmpty)
        val form     = species.forms.find { f -> parts.any { p -> p.equals(f.name, ignoreCase = true) } }
            ?: species.standardForm
        val formName = if (form.name == Constants.STANDARD_FORM) Constants.NORMAL_FORM else form.name
        val aspects  = parts.toMutableSet().also { it.remove(formName) }

        return Triple(species, formName, aspects)
    }

    // ── Cache control (called from search GUI) ────────────────────────────────

    fun applySearch(player: ServerPlayerEntity, term: String, regionId: String) {
        sortMethod  = RegionSortMethod.SEARCH
        searchTerm  = term
        invalidateCache()
        open(player, regionId, page = 0)
    }

    fun invalidateCache() { cachedVariants = null }

    // ── Nav / control items ───────────────────────────────────────────────────

    private fun sortBtn(): ItemStack {
        val label = if (sortMethod == RegionSortMethod.SEARCH && searchTerm.isNotBlank())
            "Searching: ${if (searchTerm.length > 10) searchTerm.take(7) + "…" else searchTerm}"
        else
            "Sort: ${sortMethod.name}"
        return CustomGui.createPlayerHeadButton(
            "SortMethod",
            Text.literal(label).formatted(Formatting.AQUA),
            listOf(
                Text.literal("§7Current: §f${sortMethod.name}"),
                Text.literal("§7Left-click §8to cycle"),
                Text.literal("§7Right-click §8to search")
            ),
            Textures.SORT
        )
    }

    private fun navBtn(label: String, texture: String) = CustomGui.createPlayerHeadButton(
        label,
        Text.literal(label).formatted(Formatting.GREEN),
        listOf(Text.literal("§7Go to the ${label.lowercase()} page")),
        texture
    )

    private fun backBtn() = CustomGui.createPlayerHeadButton(
        "Back",
        Text.literal("Back").formatted(Formatting.RED),
        listOf(Text.literal("§7Return to Unnatural Spawn settings")),
        Textures.BACK
    )

    private fun filler() = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { setCustomName(Text.literal(" ")) }
}
