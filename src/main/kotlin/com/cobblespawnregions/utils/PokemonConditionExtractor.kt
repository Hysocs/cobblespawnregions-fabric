package com.cobblespawnregions.utils

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonPropertyExtractor
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.server.network.ServerPlayerEntity
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.memberProperties

/**
 * Centralized utility for extracting condition strings from Pokémon.
 *
 * Used by:
 * - [RegionConditionScannerGui] — to populate the condition selector menu
 * - [RegionExclusionHelper]     — to check if a spawned Pokémon matches exclusions
 * - Any future system that needs to read Pokémon properties as key=value strings
 *
 * Output format examples:
 *   species=abra
 *   shiny=true
 *   nature=bold
 *   ability=synchronize
 *   labels=gen1
 *   aspects=male
 *   move=teleport              ← resolved from Move.template.name
 *   type=psychic               ← resolved from ElementType name
 */
object PokemonConditionExtractor {

    private val logger = LoggerFactory.getLogger("PokemonConditionExtractor")

    // Cache extracted conditions per species to avoid re-scanning the same species repeatedly
    private val speciesConditionCache = ConcurrentHashMap<String, List<String>>()

    // ── Name resolution priority ─────────────────────────────────────────────
    // When we hit an object with a garbage toString() (@hash), we try these
    // property names in order to find something human-readable.

    private val NAME_PROPERTIES = listOf(
        "name",                // most Cobblemon objects use .name
        "displayName",         // some use displayName
        "resourceIdentifier",  // registry-backed things
        "translatedName",      // localized names
        "id",                  // fallback
        "showNameId"           // Cobblemon internal
    )

    /**
     * Extracts all possible condition strings from a live [Pokemon] instance.
     *
     * @param pokemon The live Pokémon to inspect
     * @return A sorted, deduplicated list of condition strings like "species=abra"
     */
    fun extractAllConditions(pokemon: Pokemon): List<String> {
        val conditionList = mutableListOf<String>()

        // 1. Properties from PokemonPropertyExtractor (ability, nature, ivs, evs, etc.)
        val props = PokemonProperties()
        PokemonPropertyExtractor.ALL.forEach { it(pokemon, props) }

        // Types: extract readable type names instead of object references
        props.type = pokemon.form.types.joinToString(",") { it.name.lowercase(Locale.getDefault()) }
        conditionList.addAll(extractAllRaw(props))

        // 2. Entity-specific variables (labels, aspects, shiny, gender, level, etc.)
        conditionList.addAll(extractAllRaw(pokemon))

        // 3. Form-specific variables (types, abilities, labels, etc.)
        conditionList.addAll(extractAllRaw(pokemon.form))

        // 4. Moveset — use getMoves() (public API) and pull template.name from each
        try {
            pokemon.moveSet.getMoves().forEach { move ->
                // Move has .template which is a MoveTemplate with .name
                val moveName = move.template?.name
                if (!moveName.isNullOrBlank()) {
                    conditionList.add("move=$moveName")
                } else {
                    // Fallback: try deep resolution on the move object itself
                    val resolved = resolveObjectName(move)
                    if (resolved != null) conditionList.add("move=$resolved")
                }
            }
        } catch (e: Exception) {
            RegionsConfig.debugError(logger, "Failed to extract moveset conditions for ${pokemon.species.name}", e)
        }

        return conditionList
            .filter { it.contains("=") || it.contains(":") }
            .filter { !it.contains("uuid", ignoreCase = true) }
            .filter { !it.contains("@") }                    // drop any remaining garbage refs
            .distinct()
            .sorted()
    }

    /**
     * Spawns a temporary entity, scans it, then discards it.
     * Caches results per species name to avoid redundant work.
     *
     * Used by the GUI's species scanner.
     */
    fun scanSpeciesForConditions(player: ServerPlayerEntity, speciesName: String): List<String> {
        speciesConditionCache[speciesName]?.let { return it }

        try {
            val basePokemon = PokemonProperties.parse(speciesName.lowercase()).create()
            val world = player.serverWorld
            val entity = PokemonEntity(world, basePokemon)
            entity.setPosition(player.pos)
            world.spawnEntity(entity)

            val conditions = extractAllConditions(entity.pokemon)
            entity.discard()

            speciesConditionCache[speciesName] = conditions
            return conditions

        } catch (e: Exception) {
            RegionsConfig.debugError(logger, "Failed to scan species: $speciesName", e)
            return emptyList()
        }
    }

    /**
     * Builds a flat key→value map from a [Pokemon] for fast condition matching.
     */
    fun buildPropertyMap(pokemon: Pokemon): Map<String, String> {
        val properties = PokemonProperties()
        PokemonPropertyExtractor.ALL.forEach { it(pokemon, properties) }
        properties.type = pokemon.form.types.joinToString(",") { it.name }

        return buildMap {
            PokemonProperties::class.memberProperties.forEach { prop ->
                put(
                    prop.name.lowercase(Locale.getDefault()),
                    resolvePropertyValue(prop.get(properties)) ?: ""
                )
            }
        }
    }

    /** Clears the species condition cache. */
    fun clearCache() {
        speciesConditionCache.clear()
    }

    // ════════════════════════════════════════════════════════════════════════
    // DEEP OBJECT RESOLUTION — turns garbage refs into readable names
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Attempts to find a human-readable name for an object.
     * Returns null if nothing useful can be found.
     */
    internal fun resolveObjectName(obj: Any?): String? {
        if (obj == null) return null

        // Already a clean string? Great.
        if (obj is String) return obj.ifBlank { null }

        // Has a clean toString? Use it.
        val str = obj.toString()
        if (!str.contains("@") && str.isNotBlank()) return str

        // Garbage toString — dig deeper via reflection
        return tryResolveName(obj)
    }

    /**
     * Tries multiple property paths to find a name on an object.
     */
    private fun tryResolveName(obj: Any): String? {
        // Method 1: Check Kotlin/Java properties on the object itself
        for (propName in NAME_PROPERTIES) {
            try {
                val prop = obj::class.memberProperties.find { it.name.equals(propName, ignoreCase = true) }
                if (prop != null) {
                    val value = prop.getter.call(obj)
                    if (value != null && value is String && value.isNotBlank() && !value.contains("@")) {
                        return value
                    }
                    // Recurse if the value is also an object
                    if (value != null && value !is String && value !is Number && value !is Boolean) {
                        val deep = tryResolveName(value)
                        if (deep != null) return deep
                    }
                }
            } catch (e: Exception) {
                RegionsConfig.debugError(logger, "Failed to resolve property '$propName' on ${obj::class.qualifiedName}", e)
            }
        }

        // Method 2: If it's an Iterable, try to resolve each element into a comma-joined list
        if (obj is Iterable<*>) {
            val resolved = obj.mapNotNull { item -> resolveObjectName(item) }
            if (resolved.isNotEmpty()) return resolved.joinToString(",")
        }

        // Method 3: Last resort — check all properties for anything that looks like a name
        return try {
            obj::class.memberProperties.firstNotNullOfOrNull { prop ->
                try {
                    val v = prop.getter.call(obj)
                    if (v is String && v.isNotBlank() && !v.contains("@") && v.length > 1 && v.length < 100) v
                    else null
                } catch (e: Exception) {
                    RegionsConfig.debugError(logger, "Failed to inspect fallback property '${prop.name}' on ${obj::class.qualifiedName}", e)
                    null
                }
            }
        } catch (e: Exception) {
            RegionsConfig.debugError(logger, "Failed to inspect fallback properties on ${obj::class.qualifiedName}", e)
            null
        }
    }

    /**
     * Resolves a single property value — handles objects that would otherwise
     * print as ClassName@hashcode.
     */
    internal fun resolvePropertyValue(value: Any?): String? {
        if (value == null) return null
        return resolveObjectName(value)
    }

    // ── Internal reflection extractor ────────────────────────────────────────

    /**
     * Uses Kotlin reflection to pull every property from an object into key=value strings.
     *
     * When a value's toString() contains "@", we attempt to resolve a real name
     * from the object before emitting it. If resolution fails, we drop it entirely
     * rather than showing garbage to the user.
     */
    internal fun extractAllRaw(obj: Any): List<String> {
        val results = mutableListOf<String>()
        try {
            obj::class.memberProperties.forEach { prop ->
                try {
                    val value = prop.getter.call(obj)
                    if (value != null) {
                        val key = prop.name.lowercase(Locale.getDefault())
                        when (value) {
                            is Iterable<*> -> {
                                // Resolve each element individually
                                value.filterNotNull().forEach { item ->
                                    val resolved = resolveObjectName(item) ?: item.toString()
                                    if (resolved.isNotBlank() && !resolved.contains("@") && resolved != "[]") {
                                        results.add("$key=$resolved")
                                    }
                                }
                            }
                            is Map<*, *> -> {
                                value.entries.forEach { entry ->
                                    val k = resolveObjectName(entry.key) ?: entry.key.toString()
                                    val v = resolveObjectName(entry.value) ?: entry.value.toString()
                                    if (!k.contains("@") && !v.contains("@")) {
                                        results.add("$key=$k=$v")
                                    }
                                }
                            }
                            else -> {
                                val resolved = resolveObjectName(value)
                                if (resolved != null && resolved.isNotBlank() && resolved != "[]") {
                                    results.add("$key=$resolved")
                                }
                                // Also emit raw toString() IF it's clean (no @)
                                else {
                                    val raw = value.toString()
                                    if (raw.isNotBlank() && raw != "[]" && !raw.contains("@")) {
                                        results.add("$key=$raw")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    RegionsConfig.debugError(logger, "Failed to extract condition property '${prop.name}' from ${obj::class.qualifiedName}", e)
                }
            }
        } catch (e: Exception) {
            RegionsConfig.debugError(logger, "Failed to extract raw conditions from ${obj::class.qualifiedName}", e)
        }
        return results
    }
}
