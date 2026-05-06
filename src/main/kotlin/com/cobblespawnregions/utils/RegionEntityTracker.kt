package com.cobblespawnregions.utils

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Box
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks UUIDs of every Pokémon spawned by CobbleSpawnRegions.
 *
 * Entities are tagged on spawn via [com.cobblemon.mod.common.pokemon.Pokemon.persistentData]:
 *   "csr_region"    → regionId  (String)
 *   "csr_entry_key" → entryKey  (String) — see [entryKey]
 *
 * That data survives chunk unloads and server restarts, so we can
 * [rebuildFromWorld] whenever a chunk comes back into memory.
 */
object RegionEntityTracker {

    // regionId → entryKey → live UUID set
    private val tracked = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableSet<UUID>>>()
    // uuid → (regionId, entryKey) — O(1) reverse-lookup on removal
    private val reverseMap = ConcurrentHashMap<UUID, Pair<String, String>>()

    // ── Key helpers ───────────────────────────────────────────────────────────

    /**
     * Canonical, deterministic key for a [PokemonSpawnEntry].
     * Written into [com.cobblemon.mod.common.pokemon.Pokemon.persistentData]
     * so loaded entities can be matched back to the right counter.
     */
    fun entryKey(entry: PokemonSpawnEntry): String {
        val aspects = entry.aspects.map { it.lowercase() }.sorted().joinToString(",")
        val form    = entry.formName?.lowercase() ?: ""
        return "${entry.pokemonName.lowercase()}|$form|$aspects"
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /** Register a freshly-spawned entity with the tracker. */
    fun track(regionId: String, entryKey: String, uuid: UUID) {
        tracked
            .getOrPut(regionId) { ConcurrentHashMap() }
            .getOrPut(entryKey) { ConcurrentHashMap.newKeySet() }
            .add(uuid)
        reverseMap[uuid] = regionId to entryKey
    }

    /**
     * Remove a UUID when the entity is *actually* gone (killed, discarded, or
     * dimension-changed). Do **not** call this on a plain chunk-unload — the
     * entity still exists, just hibernated.
     */
    fun untrack(uuid: UUID) {
        val (regionId, entryKey) = reverseMap.remove(uuid) ?: return
        tracked[regionId]?.get(entryKey)?.remove(uuid)
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Count of tracked (possibly unloaded) entities for one entry in a region. */
    fun countForEntry(regionId: String, entryKey: String): Int =
        tracked[regionId]?.get(entryKey)?.size ?: 0

    /** Total count of all tracked entries in a region. */
    fun countTotal(regionId: String): Int =
        tracked[regionId]?.values?.sumOf { it.size } ?: 0

    // ── World rebuild ─────────────────────────────────────────────────────────

    /**
     * Scans currently-loaded [PokemonEntity] instances inside [regionBox] for
     * ones tagged with [regionId] and registers any that aren't already known.
     *
     * Call on:
     *  - `SERVER_STARTED` (covers already-loaded chunks after a restart)
     *  - `CHUNK_LOAD`     (picks up entities as their chunk comes back)
     */
    fun rebuildFromWorld(world: ServerWorld, regionId: String, regionBox: Box) {
        world.getEntitiesByClass(PokemonEntity::class.java, regionBox) { entity ->
            entity.pokemon.persistentData.getString("csr_region") == regionId
        }.forEach { entity ->
            if (reverseMap.containsKey(entity.uuid)) return@forEach   // already known
            val eKey = entity.pokemon.persistentData.getString("csr_entry_key")
            if (eKey.isNotEmpty()) track(regionId, eKey, entity.uuid)
        }
    }

    fun clearAll() {
        tracked.clear()
        reverseMap.clear()
    }
}