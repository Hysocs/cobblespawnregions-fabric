package com.cobblespawnregions.utils

/**
 * Mutable so GUI editors can toggle fields directly on the live object
 * and call RegionsConfig.saveRegion() without replacing the whole config.
 */
data class RegionRestrictionConfig(
    var disallowedSpecies: MutableList<String> = mutableListOf(),
    var disallowedLabels: MutableList<String> = mutableListOf(),
    var exclusionConditions: MutableList<Any> = mutableListOf(),
    var disableAll: Boolean = false,
    var excludeOwnedPokemon: Boolean = false
)