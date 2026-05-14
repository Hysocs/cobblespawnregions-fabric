package com.cobblespawnregions.utils

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Box
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks every Pokemon spawned by CobbleSpawnRegions.
 *
 * Counts use csr_spawn_id, not the Minecraft entity UUID. UUIDs are only live
 * handles, so a chunk unload/reload cannot make the region forget a still
 * existing spawn or count a recreated entity twice.
 */
object RegionEntityTracker {

    const val REGION_KEY = "csr_region"
    const val ENTRY_KEY = "csr_entry_key"
    const val SPAWN_ID_KEY = "csr_spawn_id"
    const val SPAWNED_AT_MS_KEY = "csr_spawned_at_ms"
    const val SPAWNED_AT_WORLD_TIME_KEY = "csr_spawned_at_world_time"
    const val DIMENSION_KEY = "csr_dimension"

    data class TrackedSpawn(
        val regionId: String,
        val entryKey: String,
        val spawnId: String
    )

    private val tracked = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableSet<String>>>()
    private val liveUuidMap = ConcurrentHashMap<UUID, TrackedSpawn>()

    fun entryKey(entry: PokemonSpawnEntry): String {
        val aspects = entry.aspects.map { it.lowercase() }.sorted().joinToString(",")
        val form = entry.formName?.lowercase() ?: ""
        return "${entry.pokemonName.lowercase()}|$form|$aspects"
    }

    fun track(regionId: String, entryKey: String, spawnId: String, uuid: UUID) {
        tracked
            .getOrPut(regionId) { ConcurrentHashMap() }
            .getOrPut(entryKey) { ConcurrentHashMap.newKeySet() }
            .add(spawnId)
        liveUuidMap[uuid] = TrackedSpawn(regionId, entryKey, spawnId)
    }

    fun trackLoadedEntity(entity: PokemonEntity): Boolean {
        val data = entity.pokemon.persistentData
        val regionId = data.getString(REGION_KEY)
        val entryKey = data.getString(ENTRY_KEY)
        if (regionId.isEmpty() || entryKey.isEmpty()) return false

        var spawnId = data.getString(SPAWN_ID_KEY)
        if (spawnId.isEmpty()) {
            spawnId = "legacy-${entity.uuid}"
            data.putString(SPAWN_ID_KEY, spawnId)
        }
        if (!data.contains(SPAWNED_AT_MS_KEY)) {
            data.putLong(SPAWNED_AT_MS_KEY, System.currentTimeMillis())
        }

        track(regionId, entryKey, spawnId, entity.uuid)
        RegionWanderingGoalManager.attachIfConfigured(entity)
        return true
    }

    fun untrack(uuid: UUID) {
        val trackedSpawn = liveUuidMap.remove(uuid) ?: return
        tracked[trackedSpawn.regionId]?.get(trackedSpawn.entryKey)?.remove(trackedSpawn.spawnId)
    }

    fun forgetLiveUuid(uuid: UUID) {
        liveUuidMap.remove(uuid)
    }

    fun countForEntry(regionId: String, entryKey: String): Int =
        tracked[regionId]?.get(entryKey)?.size ?: 0

    fun countTotal(regionId: String): Int =
        tracked[regionId]?.values?.sumOf { it.size } ?: 0

    fun isManaged(entity: PokemonEntity): Boolean =
        entity.pokemon.persistentData.getString(REGION_KEY).isNotEmpty()

    fun rebuildFromWorld(world: ServerWorld, regionId: String, regionBox: Box) {
        world.getEntitiesByClass(PokemonEntity::class.java, regionBox) { entity ->
            entity.pokemon.persistentData.getString(REGION_KEY) == regionId
        }.forEach { entity ->
            trackLoadedEntity(entity)
        }
    }

    fun clearAll() {
        tracked.clear()
        liveUuidMap.clear()
    }
}
