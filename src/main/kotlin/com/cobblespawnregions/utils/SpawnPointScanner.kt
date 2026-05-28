package com.cobblespawnregions.utils

import net.minecraft.fluid.Fluids
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import java.util.concurrent.ConcurrentLinkedQueue

object SpawnPointScanner {

    private const val SPARSE_STEP    = 4
    private const val MAX_FLOOR_DEPTH = 25



    private data class ScanJob(
        val regionId: String,
        val region: RegionData,
        val chunkPos: ChunkPos,
        val world: ServerWorld
    )

    private val scanQueue = ConcurrentLinkedQueue<ScanJob>()






    fun enqueueScan(regionId: String, region: RegionData, chunkPos: ChunkPos, world: ServerWorld) {
        scanQueue.add(ScanJob(regionId, region, chunkPos, world))
    }





    fun processPendingScans(count: Int = 2) {
        repeat(count) {
            val job = scanQueue.poll() ?: return

            if (SpawnPointStore.isChunkScanned(job.regionId, job.chunkPos.x, job.chunkPos.z)) return@repeat
            if (!job.world.isChunkLoaded(job.chunkPos.x, job.chunkPos.z)) return@repeat
            scanChunkForRegion(job.regionId, job.region, job.chunkPos, job.world)
        }
    }

    fun clearQueue() = scanQueue.clear()







    fun enqueueLoadedChunks(regionId: String, region: RegionData, server: MinecraftServer) {
        val worldKey = RegistryKey.of(
            RegistryKeys.WORLD,
            Identifier.tryParse(region.dimension) ?: return
        )
        val world = server.getWorld(worldKey) ?: return

        val minCX = minOf(region.pos1.x, region.pos2.x) shr 4
        val maxCX = maxOf(region.pos1.x, region.pos2.x) shr 4
        val minCZ = minOf(region.pos1.z, region.pos2.z) shr 4
        val maxCZ = maxOf(region.pos1.z, region.pos2.z) shr 4

        for (cx in minCX..maxCX) {
            for (cz in minCZ..maxCZ) {
                if (world.isChunkLoaded(cx, cz)) {
                    enqueueScan(regionId, region, ChunkPos(cx, cz), world)
                }
            }
        }
    }


    fun enqueueAllLoadedChunks(server: MinecraftServer) {
        RegionsConfig.allRegions().forEach { region ->
            enqueueLoadedChunks(region.regionId, region, server)
        }
    }








    fun scanChunkForRegion(regionId: String, region: RegionData, chunkPos: ChunkPos, world: ServerWorld) {
        if (SpawnPointStore.isChunkScanned(regionId, chunkPos.x, chunkPos.z)) return

        val regionMinX = minOf(region.pos1.x, region.pos2.x)
        val regionMaxX = maxOf(region.pos1.x, region.pos2.x)
        val regionMinZ = minOf(region.pos1.z, region.pos2.z)
        val regionMaxZ = maxOf(region.pos1.z, region.pos2.z)

        val minX = maxOf(regionMinX, chunkPos.startX)
        val maxX = minOf(regionMaxX, chunkPos.endX)
        val minZ = maxOf(regionMinZ, chunkPos.startZ)
        val maxZ = minOf(regionMaxZ, chunkPos.endZ)

        if (minX > maxX || minZ > maxZ) {
            SpawnPointStore.addChunkFloors(regionId, chunkPos.x, chunkPos.z, emptyList())
            return
        }

        val useHeightmap = region.pos1.y == 0 && region.pos2.y == 0
        val fixedMinY    = if (!useHeightmap) minOf(region.pos1.y, region.pos2.y) else 0
        val fixedMaxY    = if (!useHeightmap) maxOf(region.pos1.y, region.pos2.y) else 0

        val floors = mutableListOf<SpawnFloor>()




        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                val feetY = if (useHeightmap) {


                    world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z)
                } else {
                    topSolidInRange(world, x, z, fixedMinY, fixedMaxY)
                } ?: continue

                if (!useHeightmap && (feetY < fixedMinY || feetY > fixedMaxY)) continue

                val floorPos = BlockPos(x, feetY - 1, z)
                val feetPos  = BlockPos(x, feetY,     z)
                val airPos   = feetPos.up()

                val floorState = world.getBlockState(floorPos)
                val feetState  = world.getBlockState(feetPos)
                val airState   = world.getBlockState(airPos)

                if (!floorState.getCollisionShape(world, floorPos).isEmpty && feetState.isAir) {
                    floors.add(SpawnFloor(feetPos, floorState.block, SpawnType.SOLID))
                    if ((useHeightmap || airPos.y <= fixedMaxY) && airState.isAir) {
                        floors.add(SpawnFloor(airPos, net.minecraft.block.Blocks.AIR, SpawnType.AIR))
                    }
                }
            }
        }



        for (x in minX..maxX step SPARSE_STEP) {
            for (z in minZ..maxZ step SPARSE_STEP) {
                val (scanMin, scanMax) = yBounds(world, useHeightmap, fixedMinY, fixedMaxY, x, z)
                for (y in scanMin..scanMax) {
                    val pos = BlockPos(x, y, z)
                    if (!world.getBlockState(pos).isAir) continue
                    val floorBlock = solidWithin(world, x, y, z, scanMin) ?: continue
                    floors.add(SpawnFloor(pos, floorBlock, SpawnType.AIR))
                }
            }
        }



        for (x in minX..maxX step SPARSE_STEP) {
            for (z in minZ..maxZ step SPARSE_STEP) {
                val (scanMin, scanMax) = yBounds(world, useHeightmap, fixedMinY, fixedMaxY, x, z)
                for (y in scanMin..scanMax) {
                    val pos   = BlockPos(x, y, z)
                    val fluid = world.getBlockState(pos).fluidState.fluid
                    if (fluid != Fluids.WATER && fluid != Fluids.FLOWING_WATER) continue
                    val floorBlock = solidWithin(world, x, y, z, scanMin) ?: continue
                    floors.add(SpawnFloor(pos, floorBlock, SpawnType.WATER))
                }
            }
        }

        SpawnPointStore.addChunkFloors(regionId, chunkPos.x, chunkPos.z, floors)
    }




    private fun yBounds(
        world: ServerWorld,
        useHeightmap: Boolean,
        fixedMin: Int, fixedMax: Int,
        x: Int, z: Int
    ): Pair<Int, Int> = if (useHeightmap) {
        Pair(world.bottomY, world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z))
    } else {
        Pair(fixedMin, fixedMax)
    }





    private fun topSolidInRange(world: ServerWorld, x: Int, z: Int, minY: Int, maxY: Int): Int? {
        for (y in maxY downTo minY + 1) {
            val floorState = world.getBlockState(BlockPos(x, y - 1, z))
            val feetState  = world.getBlockState(BlockPos(x, y, z))
            if (!floorState.getCollisionShape(world, BlockPos(x, y - 1, z)).isEmpty && feetState.isAir) return y
        }
        return null
    }


    private fun solidWithin(
        world: ServerWorld,
        x: Int, startY: Int, z: Int,
        absoluteMin: Int
    ): net.minecraft.block.Block? {
        for (dy in 1..MAX_FLOOR_DEPTH) {
            val checkY = startY - dy
            if (checkY < absoluteMin) break
            val pos   = BlockPos(x, checkY, z)
            val state = world.getBlockState(pos)
            if (!state.getCollisionShape(world, pos).isEmpty) return state.block
        }
        return null
    }
}
