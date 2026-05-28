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



















object PokemonConditionExtractor {

    private val logger = LoggerFactory.getLogger("PokemonConditionExtractor")


    private val speciesConditionCache = ConcurrentHashMap<String, List<String>>()





    private val NAME_PROPERTIES = listOf(
        "name",
        "displayName",
        "resourceIdentifier",
        "translatedName",
        "id",
        "showNameId"
    )







    fun extractAllConditions(pokemon: Pokemon): List<String> {
        val conditionList = mutableListOf<String>()


        val props = PokemonProperties()
        PokemonPropertyExtractor.ALL.forEach { it(pokemon, props) }


        props.type = pokemon.form.types.joinToString(",") { it.name.lowercase(Locale.getDefault()) }
        conditionList.addAll(extractAllRaw(props))


        conditionList.addAll(extractAllRaw(pokemon))


        conditionList.addAll(extractAllRaw(pokemon.form))


        try {
            pokemon.moveSet.getMoves().forEach { move ->

                val moveName = move.template?.name
                if (!moveName.isNullOrBlank()) {
                    conditionList.add("move=$moveName")
                } else {

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
            .filter { !it.contains("@") }
            .distinct()
            .sorted()
    }







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


    fun clearCache() {
        speciesConditionCache.clear()
    }









    internal fun resolveObjectName(obj: Any?): String? {
        if (obj == null) return null


        if (obj is String) return obj.ifBlank { null }


        val str = obj.toString()
        if (!str.contains("@") && str.isNotBlank()) return str


        return tryResolveName(obj)
    }




    private fun tryResolveName(obj: Any): String? {

        for (propName in NAME_PROPERTIES) {
            try {
                val prop = obj::class.memberProperties.find { it.name.equals(propName, ignoreCase = true) }
                if (prop != null) {
                    val value = prop.getter.call(obj)
                    if (value != null && value is String && value.isNotBlank() && !value.contains("@")) {
                        return value
                    }

                    if (value != null && value !is String && value !is Number && value !is Boolean) {
                        val deep = tryResolveName(value)
                        if (deep != null) return deep
                    }
                }
            } catch (e: Exception) {
                RegionsConfig.debugError(logger, "Failed to resolve property '$propName' on ${obj::class.qualifiedName}", e)
            }
        }


        if (obj is Iterable<*>) {
            val resolved = obj.mapNotNull { item -> resolveObjectName(item) }
            if (resolved.isNotEmpty()) return resolved.joinToString(",")
        }


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





    internal fun resolvePropertyValue(value: Any?): String? {
        if (value == null) return null
        return resolveObjectName(value)
    }










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
