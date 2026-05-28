package com.cobblespawnregions.utils

import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.util.math.BlockPos
import java.util.Locale

object RegionExclusionHelper {



    fun getApplicableRestriction(pos: BlockPos, dimension: String): RegionRestrictionConfig? {
        return RegionsConfig.controllingRegionAt(pos, dimension)?.spawnRestrictions
    }

    fun isSpawnDisabledAt(pos: BlockPos, dimension: String): Boolean {
        val config = getApplicableRestriction(pos, dimension) ?: return false
        return config.disableAll
    }




















    fun shouldExcludePokemon(pokemon: Pokemon, pos: BlockPos, dimension: String): Boolean {
        val config = getApplicableRestriction(pos, dimension) ?: return false


        if (config.disableAll) {

            if (config.excludeOwnedPokemon && !pokemon.isWild()) return false
            return true
        }


        if (config.excludeOwnedPokemon && !pokemon.isWild()) return false


        return isPokemonExcluded(pokemon, config)
    }









    fun isPokemonExcluded(pokemon: Pokemon, config: RegionRestrictionConfig): Boolean {


        val speciesId = pokemon.species.resourceIdentifier.toString()
        if (speciesId in config.disallowedSpecies) return true


        val labels = pokemon.form.labels
        if (config.disallowedLabels.any { it in labels }) return true


        if (config.exclusionConditions.isEmpty()) return false


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
