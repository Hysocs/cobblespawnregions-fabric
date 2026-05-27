package com.cobblespawnregions.utils

import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.util.math.BlockPos
import java.util.Locale

object RegionExclusionHelper {

    // ── Region lookup ─────────────────────────────────────────────────────────

    fun getApplicableRestriction(pos: BlockPos, dimension: String): RegionRestrictionConfig? {
        return RegionsConfig.controllingRegionAt(pos, dimension)?.spawnRestrictions
    }

    fun isSpawnDisabledAt(pos: BlockPos, dimension: String): Boolean {
        val config = getApplicableRestriction(pos, dimension) ?: return false
        return config.disableAll
    }

    // ════════════════════════════════════════════════════════════════════════
    // MIDDLEMAN — fast path for mixins / spawn checks
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Entry point for mixins and runtime spawn filtering.
     *
     * Short-circuits immediately if the position isn't inside any region,
     * so the expensive [isPokemonExcluded] check only runs for Pokémon
     * that are actually standing in claimed land.
     *
     * Checks in order:
     *   1. Not in any region? → allow (instant)
     *   2. [disableAll] set? → block all spawns (unless owned + excludeOwnedPokemon)
     *   3. Owned Pokémon + [excludeOwnedPokemon]? → allow (skip expensive extraction)
     *   4. Species / Labels / Conditions? → run full check
     *
     * @return true → block the spawn, false → allow it
     */
    fun shouldExcludePokemon(pokemon: Pokemon, pos: BlockPos, dimension: String): Boolean {
        val config = getApplicableRestriction(pos, dimension) ?: return false

        // ── disableAll: block everything in this region ─────────────────────
        if (config.disableAll) {
            // Unless excludeOwnedPokemon is ON and this Pokémon is NOT wild
            if (config.excludeOwnedPokemon && !pokemon.isWild()) return false
            return true  // blocked
        }

        // ── excludeOwnedPokemon: owned mons bypass all other restrictions ───
        if (config.excludeOwnedPokemon && !pokemon.isWild()) return false

        // ── species / labels / conditions: expensive check ──────────────────
        return isPokemonExcluded(pokemon, config)
    }

    // ── Pokémon exclusion (species/labels/conditions only) ──────────────────

    /**
     * Core exclusion logic — expensive, only call after confirming position is in a region.
     *
     * GUI calls this directly (it already knows the region).
     * Mixins should call [shouldExcludePokemon] instead (which also handles disableAll/owned).
     */
    fun isPokemonExcluded(pokemon: Pokemon, config: RegionRestrictionConfig): Boolean {

        // 1. Exact species block-list (cheap string compare)
        val speciesId = pokemon.species.resourceIdentifier.toString()
        if (speciesId in config.disallowedSpecies) return true

        // 2. Label block-list (set contains check)
        val labels = pokemon.form.labels
        if (config.disallowedLabels.any { it in labels }) return true

        // No conditions? Done.
        if (config.exclusionConditions.isEmpty()) return false

        // 3. RAW substring match across the same searchable condition text used by the GUI.
        val searchText = buildConditionSearchText(pokemon)

        return config.exclusionConditions.any { condition ->
            condition is String && searchText.contains(condition.trim().lowercase(Locale.getDefault()))
        }
    }

    private fun buildConditionSearchText(pokemon: Pokemon): String {
        val extractedStrings = PokemonConditionExtractor.extractAllConditions(pokemon)
        val propertyMap = PokemonConditionExtractor.buildPropertyMap(pokemon)
        val terms = mutableListOf<String>()

        fun addTerm(term: String?) {
            if (term.isNullOrBlank()) return
            terms.add(term)
            if ('=' in term) terms.add(term.replace('=', ':'))
            if (':' in term) terms.add(term.replace(':', '='))
        }

        addTerm(pokemon.species.resourceIdentifier.toString())
        addTerm(pokemon.species.name)
        addTerm("species=${pokemon.species.resourceIdentifier}")
        addTerm("pokemon=${pokemon.species.resourceIdentifier}")
        addTerm("pokemon=${pokemon.species.name}")
        addTerm("form=${pokemon.form.name}")
        if (pokemon.form.name.equals("standard", ignoreCase = true)) {
            addTerm("form=normal")
        }
        pokemon.form.aspects.forEach { addTerm("form=$it") }
        pokemon.form.aspects.forEach { addTerm("aspect=$it") }
        pokemon.aspects.forEach { addTerm("aspect=$it") }
        pokemon.form.labels.forEach { addTerm(it) }
        pokemon.form.labels.forEach { addTerm("label=$it") }
        pokemon.form.types.forEach { addTerm("type=${it.name}") }

        propertyMap.forEach { (key, value) ->
            addTerm(key)
            addTerm(value)
            addTerm("$key=$value")
        }
        extractedStrings.forEach(::addTerm)

        return terms.joinToString("\n").lowercase(Locale.getDefault())
    }

}
