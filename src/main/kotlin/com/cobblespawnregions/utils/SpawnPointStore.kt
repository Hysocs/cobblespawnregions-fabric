package com.cobblespawnregions.utils

import net.minecraft.block.Block
import net.minecraft.registry.Registries
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import java.util.concurrent.ConcurrentHashMap

enum class SpawnType { SOLID, AIR, WATER }

/**
 * Transient helper used only during scanning to pass data to [SpawnPointStore].
 * Never held in permanent storage — the store converts to primitive arrays immediately.
 */
data class SpawnFloor(val pos: BlockPos, val floorBlock: Block, val type: SpawnType)

object SpawnPointStore {

    /**
     * Compact primitive storage for one region's spawn floors.
     *
     * Per-entry cost: 8 + 4 + 1 = 13 bytes (vs ~48 bytes for a SpawnFloor object).
     * At 100k entries: ~1.3 MB vs ~5-10 MB with object storage.
     *
     *   positions  — BlockPos.asLong()
     *   blockIds   — Registries.BLOCK.getRawId(block)
     *   types      — SpawnType.ordinal as Byte
     */
    private class RegionFloors {
        var positions: LongArray  = LongArray(0)
        var blockIds:  IntArray   = IntArray(0)
        var types:     ByteArray  = ByteArray(0)
        val size get() = positions.size

        fun append(floors: List<SpawnFloor>) {
            if (floors.isEmpty()) return
            val n = positions.size
            val m = floors.size
            positions = positions.copyOf(n + m)
            blockIds  = blockIds.copyOf(n + m)
            types     = types.copyOf(n + m)
            floors.forEachIndexed { i, floor ->
                positions[n + i] = floor.pos.asLong()
                blockIds [n + i] = Registries.BLOCK.getRawId(floor.floorBlock)
                types    [n + i] = floor.type.ordinal.toByte()
            }
        }

        fun clear() {
            positions = LongArray(0)
            blockIds  = IntArray(0)
            types     = ByteArray(0)
        }
    }

    private val regionFloors  = ConcurrentHashMap<String, RegionFloors>()
    private val scannedChunks = ConcurrentHashMap<String, MutableSet<Long>>()

    // ── Read ──────────────────────────────────────────────────────────────────

    fun isChunkScanned(regionId: String, chunkX: Int, chunkZ: Int): Boolean =
        scannedChunks[regionId]?.contains(ChunkPos.toLong(chunkX, chunkZ)) == true

    fun isEmpty(regionId: String): Boolean =
        (regionFloors[regionId]?.size ?: 0) == 0

    fun size(regionId: String): Int =
        regionFloors[regionId]?.size ?: 0

    fun rawAt(regionId: String, index: Int, action: (posLong: Long, blockId: Int, type: SpawnType) -> Unit): Boolean {
        val data = regionFloors[regionId] ?: return false
        if (index !in 0 until data.size) return false
        action(
            data.positions[index],
            data.blockIds[index],
            SpawnType.entries[data.types[index].toInt()]
        )
        return true
    }

    /**
     * Iterate all floors for a region without allocating any objects.
     * [block] is decoded from the registry on each call — cache it if needed.
     */
    fun forEach(regionId: String, action: (pos: BlockPos, block: Block, type: SpawnType) -> Unit) {
        val data = regionFloors[regionId] ?: return
        for (i in 0 until data.size) {
            action(
                BlockPos.fromLong(data.positions[i]),
                Registries.BLOCK.get(data.blockIds[i]),
                SpawnType.entries[data.types[i].toInt()]
            )
        }
    }

    fun forEachRaw(regionId: String, action: (posLong: Long, blockId: Int, type: SpawnType) -> Unit) {
        val data = regionFloors[regionId] ?: return
        for (i in 0 until data.size) {
            action(
                data.positions[i],
                data.blockIds[i],
                SpawnType.entries[data.types[i].toInt()]
            )
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    fun addChunkFloors(regionId: String, chunkX: Int, chunkZ: Int, floors: List<SpawnFloor>) {
        regionFloors.getOrPut(regionId) { RegionFloors() }.append(floors)
        scannedChunks.getOrPut(regionId) { mutableSetOf() }.add(ChunkPos.toLong(chunkX, chunkZ))
    }

    // ── Invalidation ──────────────────────────────────────────────────────────

    fun clearRegion(regionId: String) {
        regionFloors.remove(regionId)
        scannedChunks.remove(regionId)
    }

    fun clearAll() {
        regionFloors.clear()
        scannedChunks.clear()
    }
}
