package com.cobblespawnregions.utils





data class RegionRestrictionConfig(
    var disallowedSpecies: MutableList<String> = mutableListOf(),
    var disallowedLabels: MutableList<String> = mutableListOf(),
    var exclusionConditions: MutableList<Any> = mutableListOf(),
    var disableAll: Boolean = false,
    var excludeOwnedPokemon: Boolean = false
)