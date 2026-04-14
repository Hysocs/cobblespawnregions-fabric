package com.cobblespawnregions.utils

import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.util.math.BlockPos

object RegionExclusionHelper {

    // ── Region lookup ─────────────────────────────────────────────────────────

    fun getApplicableRestriction(pos: BlockPos, dimension: String): RegionRestrictionConfig? {
        for (region in RegionsConfig.regions.values) {
            if (region.dimension != dimension) continue
            if (!pos.isInBounds(region.pos1, region.pos2)) continue

            val matchingSub = region.subRegions.firstOrNull { pos.isInBounds(it.pos1, it.pos2) }
            return matchingSub?.spawnRestrictions ?: region.spawnRestrictions
        }
        return null
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

        // 3. RAW substring match across all extracted strings
        val extractedStrings = PokemonConditionExtractor.extractAllConditions(pokemon)

        return config.exclusionConditions.any { condition ->
            condition is String && extractedStrings.any { extracted ->
                extracted.contains(condition, ignoreCase = true)
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun BlockPos.isInBounds(p1: SerializableBlockPos, p2: SerializableBlockPos): Boolean {
        val minX = minOf(p1.x, p2.x); val maxX = maxOf(p1.x, p2.x)
        val minY = minOf(p1.y, p2.y); val maxY = maxOf(p1.y, p2.y)
        val minZ = minOf(p1.z, p2.z); val maxZ = maxOf(p1.z, p2.z)
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }
}