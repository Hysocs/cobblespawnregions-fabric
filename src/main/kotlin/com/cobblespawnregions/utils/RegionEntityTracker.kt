package com.cobblespawnregions.utils

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.GsonBuilder
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Box
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean








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
        val spawnId: String,
        val dimension: String = "",
        val chunkX: Int = 0,
        val chunkZ: Int = 0
    )

    private data class PersistedTracker(
        val spawns: List<TrackedSpawn> = emptyList()
    )

    private val logger = LoggerFactory.getLogger("RegionEntityTracker")
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val trackerFile = File("config/cobblespawnregions/tracked_spawns.json")

    private val tracked = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableSet<String>>>()
    private val liveUuidMap = ConcurrentHashMap<UUID, TrackedSpawn>()
    private val spawnRecords = ConcurrentHashMap<String, TrackedSpawn>()
    private val unloadingChunks = ConcurrentHashMap.newKeySet<String>()
    private val saveLock = Any()
    private val dirty = AtomicBoolean(false)

    fun registerEvents() {
        CobblemonEvents.POKEMON_CAPTURED.subscribe { event ->
            clearManagedData(event.pokemon)
        }
    }

    fun entryKey(entry: PokemonSpawnEntry): String {
        val aspects = entry.aspects.map { it.lowercase() }.sorted().joinToString(",")
        val form = entry.formName?.lowercase() ?: ""
        return "${entry.pokemonName.lowercase()}|$form|$aspects"
    }

    fun track(
        regionId: String,
        entryKey: String,
        spawnId: String,
        uuid: UUID,
        dimension: String = "",
        chunkX: Int = 0,
        chunkZ: Int = 0
    ) {
        val record = TrackedSpawn(regionId, entryKey, spawnId, dimension, chunkX, chunkZ)
        tracked
            .getOrPut(regionId) { ConcurrentHashMap() }
            .getOrPut(entryKey) { ConcurrentHashMap.newKeySet() }
            .also { spawnIds ->
                val added = spawnIds.add(spawnId)
                val previous = spawnRecords.put(spawnId, record)
                if (added || previous != record) markDirty()
            }
        liveUuidMap[uuid] = record
    }

    fun trackLoadedEntity(entity: PokemonEntity): Boolean {
        if (!entity.pokemon.isWild()) {
            clearManagedData(entity.pokemon)
            return false
        }

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

        val dimension = entity.world.registryKey.value.toString()
        val chunkPos = entity.chunkPos
        track(regionId, entryKey, spawnId, entity.uuid, dimension, chunkPos.x, chunkPos.z)
        RegionWanderingGoalManager.attachIfConfigured(entity)
        return true
    }

    fun untrack(uuid: UUID) {
        val trackedSpawn = liveUuidMap.remove(uuid) ?: return
        if (removeRecord(trackedSpawn)) markDirty()
    }

    fun forgetLiveUuid(uuid: UUID) {
        liveUuidMap.remove(uuid)
    }

    fun markChunkUnloading(world: ServerWorld, chunkPos: ChunkPos) {
        unloadingChunks.add(chunkKey(world.registryKey.value.toString(), chunkPos))
    }

    fun markChunkLoaded(world: ServerWorld, chunkPos: ChunkPos) {
        unloadingChunks.remove(chunkKey(world.registryKey.value.toString(), chunkPos))
    }

    fun isChunkUnloading(entity: PokemonEntity): Boolean =
        chunkKey(entity.world.registryKey.value.toString(), entity.chunkPos) in unloadingChunks

    fun reconcileLoadedChunk(world: ServerWorld, regionId: String, chunkPos: ChunkPos) {
        val dimension = world.registryKey.value.toString()
        val liveSpawnIds = world.getEntitiesByClass(PokemonEntity::class.java, chunkBox(world, chunkPos)) { entity ->
            val data = entity.pokemon.persistentData
            data.getString(REGION_KEY) == regionId
        }.mapNotNull { entity ->
            entity.pokemon.persistentData.getString(SPAWN_ID_KEY).takeIf { it.isNotEmpty() }
        }.toSet()

        val staleRecords = spawnRecords.values
            .filter {
                it.regionId == regionId &&
                        it.dimension == dimension &&
                        it.chunkX == chunkPos.x &&
                        it.chunkZ == chunkPos.z &&
                        it.spawnId !in liveSpawnIds
            }
            .toList()

        if (staleRecords.isNotEmpty()) {
            var changed = false
            staleRecords.forEach { changed = removeRecord(it) || changed }
            if (changed) markDirty()
        }
    }

    fun loadedManagedEntities(world: ServerWorld, regionId: String, regionBox: Box? = null): List<PokemonEntity> {
        val dimension = world.registryKey.value.toString()
        val entities = linkedMapOf<UUID, PokemonEntity>()

        liveUuidMap.entries
            .filter { it.value.regionId == regionId && (it.value.dimension.isEmpty() || it.value.dimension == dimension) }
            .forEach { entry ->
                val entity = world.getEntity(entry.key) as? PokemonEntity
                if (entity == null || entity.isRemoved) {
                    liveUuidMap.remove(entry.key, entry.value)
                    return@forEach
                }

                val data = entity.pokemon.persistentData
                if (data.getString(REGION_KEY) == regionId) {
                    entities[entity.uuid] = entity
                    trackLoadedEntity(entity)
                } else {
                    liveUuidMap.remove(entry.key, entry.value)
                }
            }

        if (regionBox != null) {
            world.getEntitiesByClass(PokemonEntity::class.java, regionBox) { entity ->
                entity.pokemon.persistentData.getString(REGION_KEY) == regionId
            }.forEach { entity ->
                entities[entity.uuid] = entity
                trackLoadedEntity(entity)
            }
        }

        return entities.values.toList()
    }

    fun countForEntry(regionId: String, entryKey: String): Int =
        tracked[regionId]?.get(entryKey)?.size ?: 0

    fun countTotal(regionId: String): Int =
        tracked[regionId]?.values?.sumOf { it.size } ?: 0

    fun isManaged(entity: PokemonEntity): Boolean =
        entity.pokemon.persistentData.getString(REGION_KEY).isNotEmpty()






    fun clearManagedData(pokemon: Pokemon): Boolean {
        val data = pokemon.persistentData
        val regionId = data.getString(REGION_KEY)
        if (regionId.isEmpty()) return false

        val spawnId = data.getString(SPAWN_ID_KEY)
        if (spawnId.isNotEmpty()) {
            spawnRecords[spawnId]?.let { record ->
                if (removeRecord(record)) markDirty()
            }
            liveUuidMap.entries.removeIf { it.value.spawnId == spawnId }
        }

        pokemon.entity?.let { entity ->
            liveUuidMap.remove(entity.uuid)
            RegionWanderingGoalManager.forget(entity.uuid)
        }

        MANAGED_DATA_KEYS.forEach(data::remove)
        return true
    }

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
        spawnRecords.clear()
        unloadingChunks.clear()
    }

    fun clearAllAndMarkDirty() {
        clearAll()
        markDirty()
    }

    fun clearRegion(regionId: String) {
        var changed = false
        spawnRecords.values
            .filter { it.regionId == regionId }
            .toList()
            .forEach { if (removeRecord(it)) changed = true }
        liveUuidMap.entries.removeIf { it.value.regionId == regionId }
        if (changed) markDirty()
    }

    fun loadFromDisk() {
        tracked.clear()
        liveUuidMap.clear()
        spawnRecords.clear()
        dirty.set(false)

        if (!trackerFile.exists()) return
        runCatching {
            val persisted = trackerFile.reader().use { gson.fromJson(it, PersistedTracker::class.java) }
            persisted?.spawns.orEmpty().forEach { record ->
                if (record.regionId.isBlank() || record.entryKey.isBlank() || record.spawnId.isBlank()) return@forEach
                tracked
                    .getOrPut(record.regionId) { ConcurrentHashMap() }
                    .getOrPut(record.entryKey) { ConcurrentHashMap.newKeySet() }
                    .add(record.spawnId)
                spawnRecords[record.spawnId] = record
            }
        }.onFailure {
            RegionsConfig.debugError(logger, "Failed to load tracked spawns", it)
        }
        dirty.set(false)
    }

    fun flushIfDirty() {
        if (!dirty.get()) return
        synchronized(saveLock) {
            if (!dirty.getAndSet(false)) return
            runCatching {
                saveToDisk()
            }.onFailure {
                dirty.set(true)
                RegionsConfig.debugError(logger, "Failed to save tracked spawns", it)
            }
        }
    }

    private fun chunkKey(dimension: String, chunkPos: ChunkPos): String =
        "$dimension:${chunkPos.x},${chunkPos.z}"

    private fun markDirty() {
        dirty.set(true)
    }

    private fun removeRecord(record: TrackedSpawn): Boolean {
        val removedTracked = tracked[record.regionId]?.get(record.entryKey)?.remove(record.spawnId) == true
        val removedRecord = spawnRecords.remove(record.spawnId) != null
        return removedTracked || removedRecord
    }

    private fun chunkBox(world: ServerWorld, chunkPos: ChunkPos): Box = Box(
        chunkPos.startX.toDouble(),
        world.bottomY.toDouble(),
        chunkPos.startZ.toDouble(),
        chunkPos.endX.toDouble() + 1.0,
        world.topY.toDouble(),
        chunkPos.endZ.toDouble() + 1.0
    )

    private fun saveToDisk() {
        trackerFile.parentFile?.mkdirs()
        val persisted = PersistedTracker(
            spawnRecords.values.sortedWith(
                compareBy<TrackedSpawn> { it.regionId }
                    .thenBy { it.entryKey }
                    .thenBy { it.spawnId }
            )
        )
        trackerFile.writeText(gson.toJson(persisted))
    }

    private val MANAGED_DATA_KEYS = listOf(
        REGION_KEY,
        ENTRY_KEY,
        SPAWN_ID_KEY,
        SPAWNED_AT_MS_KEY,
        SPAWNED_AT_WORLD_TIME_KEY,
        DIMENSION_KEY
    )
}
