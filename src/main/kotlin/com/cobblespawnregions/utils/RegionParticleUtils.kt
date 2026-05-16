package com.cobblespawnregions.utils

import com.cobblespawnregions.CobbleSpawnRegions
import com.cobblespawnregions.StickMode
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.EntityType
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.AffineTransformation
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


object RegionParticleUtils {

    // ── Region outline ─────────────────────────────────────────────────────────

    data class BoxRequest(
        val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double,
        val state: BlockState
    )

    class PlayerVisualState {
        var basePos: Vec3d = Vec3d.ZERO
        val entities = mutableListOf<DisplayEntity.BlockDisplayEntity>()

        fun clear(player: ServerPlayerEntity) {
            if (entities.isNotEmpty()) {
                player.networkHandler.sendPacket(EntitiesDestroyS2CPacket(*entities.map { it.id }.toIntArray()))
                entities.clear()
            }
        }
    }

    private val activeVisualStates = ConcurrentHashMap<UUID, PlayerVisualState>()

    private const val SPAWN_PARTICLE_RADIUS_SQ = 25.0 * 25.0

    private data class RegionVisualPalette(
        val face: BlockState,
        val frame: BlockState,
        val edge: BlockState
    )

    private val priorityPalettes = listOf(
        RegionVisualPalette(Blocks.RED_STAINED_GLASS.defaultState, Blocks.RED_CONCRETE.defaultState, Blocks.RED_CONCRETE.defaultState),
        RegionVisualPalette(Blocks.ORANGE_STAINED_GLASS.defaultState, Blocks.ORANGE_CONCRETE.defaultState, Blocks.ORANGE_CONCRETE.defaultState),
        RegionVisualPalette(Blocks.YELLOW_STAINED_GLASS.defaultState, Blocks.YELLOW_CONCRETE.defaultState, Blocks.YELLOW_CONCRETE.defaultState),
        RegionVisualPalette(Blocks.LIME_STAINED_GLASS.defaultState, Blocks.LIME_CONCRETE.defaultState, Blocks.LIME_CONCRETE.defaultState),
        RegionVisualPalette(Blocks.GREEN_STAINED_GLASS.defaultState, Blocks.GREEN_CONCRETE.defaultState, Blocks.GREEN_CONCRETE.defaultState)
    )

    // ── Main update loop ───────────────────────────────────────────────────────

    fun updateParticles(server: MinecraftServer) {
        val playersToCheck = HashSet<UUID>()
        playersToCheck.addAll(CobbleSpawnRegions.particleUpdatePlayers)
        playersToCheck.addAll(activeVisualStates.keys)
        if (playersToCheck.isEmpty()) return

        val playersToUpdate = mutableSetOf<UUID>()
        var priorityIndexCache: Map<String, Int>? = null

        playersToCheck.forEach { uuid ->
            val player = server.playerManager.getPlayer(uuid)
            if (player == null) {
                CobbleSpawnRegions.particleUpdatePlayers.remove(uuid)
                activeVisualStates.remove(uuid)
                return@forEach
            }

            val requests = mutableListOf<BoxRequest>()

            val sel = CobbleSpawnRegions.playerSelections[player.uuid]
            if (sel != null) {
                playersToUpdate.add(player.uuid)
                buildSelectionRequests(player, sel, requests)
            }

            val regionIds = CobbleSpawnRegions.activeVisualizations[player.uuid]
            if (regionIds != null) {
                val missing = mutableListOf<String>()
                val priorityIndex = priorityIndexCache ?: RegionsConfig.regionsInPriorityOrder()
                    .mapIndexed { index, region -> region.regionId to index }
                    .toMap()
                    .also { priorityIndexCache = it }
                val priorityRegionCount = priorityIndex.size
                regionIds.forEach { regionId ->
                    val region = RegionsConfig.getRegion(regionId)
                    if (region != null) {
                        playersToUpdate.add(player.uuid)
                        buildRegionRequests(
                            region,
                            priorityIndex[region.regionId] ?: 0,
                            priorityRegionCount,
                            requests
                        )
                        spawnPointParticles(player, regionId)
                    } else {
                        missing.add(regionId)
                    }
                }
                missing.forEach { regionIds.remove(it) }
                if (regionIds.isEmpty()) {
                    CobbleSpawnRegions.activeVisualizations.remove(player.uuid)
                } else {
                    playersToUpdate.add(player.uuid)
                }
            }

            if (requests.isNotEmpty() || activeVisualStates.containsKey(player.uuid)) {
                updatePlayerVisuals(player, requests)
            }
        }

        // Clean up region-outline states for players with no active visualization
        val it = activeVisualStates.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val uuid = entry.key
            if (uuid !in playersToUpdate) {
                val player = server.playerManager.getPlayer(uuid)
                if (player != null) {
                    entry.value.clear(player)
                }
                it.remove()
                CobbleSpawnRegions.particleUpdatePlayers.remove(uuid)
            }
        }

        playersToCheck.forEach { uuid ->
            if (uuid !in playersToUpdate && !activeVisualStates.containsKey(uuid)) {
                CobbleSpawnRegions.particleUpdatePlayers.remove(uuid)
            }
        }
    }

    // ── Region outline rendering ───────────────────────────────────────────────

    private fun updatePlayerVisuals(player: ServerPlayerEntity, requests: List<BoxRequest>) {
        val state = activeVisualStates.getOrPut(player.uuid) { PlayerVisualState() }

        if (requests.size != state.entities.size) {
            state.clear(player)
            state.basePos = player.pos

            requests.forEach { req ->
                val entity = DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, player.world as ServerWorld)
                entity.setPosition(state.basePos)
                applyTransformation(entity, req, state.basePos)
                entity.setBlockState(req.state)

                state.entities.add(entity)

                player.networkHandler.sendPacket(
                    EntitySpawnS2CPacket(
                        entity.id, entity.uuid, entity.x, entity.y, entity.z,
                        entity.pitch, entity.yaw, entity.type, 0, entity.velocity, 0.0
                    )
                )
                val entries = entity.dataTracker.changedEntries
                if (entries != null) {
                    player.networkHandler.sendPacket(EntityTrackerUpdateS2CPacket(entity.id, entries))
                }
            }
        } else {
            if (player.pos.distanceTo(state.basePos) > 16.0) {
                state.basePos = player.pos
                state.entities.forEach { entity ->
                    entity.setPosition(state.basePos)
                    player.networkHandler.sendPacket(EntityPositionS2CPacket(entity))
                }
            }
            state.entities.forEachIndexed { i, entity ->
                val req = requests[i]
                applyTransformation(entity, req, state.basePos)
                entity.setBlockState(req.state)
                val entries = entity.dataTracker.changedEntries
                if (entries != null) {
                    player.networkHandler.sendPacket(EntityTrackerUpdateS2CPacket(entity.id, entries))
                }
            }
        }
    }

    private fun applyTransformation(entity: DisplayEntity.BlockDisplayEntity, req: BoxRequest, basePos: Vec3d) {
        val scaleX = (req.maxX - req.minX).toFloat().coerceAtLeast(0.001f)
        val scaleY = (req.maxY - req.minY).toFloat().coerceAtLeast(0.001f)
        val scaleZ = (req.maxZ - req.minZ).toFloat().coerceAtLeast(0.001f)

        val offsetX = (req.minX - basePos.x).toFloat()
        val offsetY = (req.minY - basePos.y).toFloat()
        val offsetZ = (req.minZ - basePos.z).toFloat()

        val transform = AffineTransformation(
            Matrix4f().translate(offsetX, offsetY, offsetZ).scale(scaleX, scaleY, scaleZ)
        )
        entity.setTransformation(transform)
        entity.interpolationDuration = 3
        entity.startInterpolation = 0
    }

    // ── Spawn point particles ──────────────────────────────────────────────────

    /**
     * Sends particles for every nearby spawn floor.
     * [SpawnFloor.pos] is always the feet position for all types.
     *
     *   SOLID → HAPPY_VILLAGER (green)  – two stacked to mark the column
     *   AIR   → END_ROD (white)         – single at the air block
     *   WATER → BUBBLE (blue)           – single at the water block
     */
    private fun spawnPointParticles(player: ServerPlayerEntity, regionId: String) {
        val region = RegionsConfig.getRegion(regionId) ?: return
        val px = player.x; val py = player.y; val pz = player.z

        SpawnPointStore.forEach(regionId) { pos, _, type ->
            if (!RegionsConfig.isControllingRegion(regionId, pos, region.dimension)) return@forEach

            val fx = pos.x + 0.5
            val fy = pos.y + 0.5
            val fz = pos.z + 0.5
            val dx = fx - px; val dy = fy - py; val dz = fz - pz
            if (dx * dx + dy * dy + dz * dz > SPAWN_PARTICLE_RADIUS_SQ) return@forEach

            when (type) {
                SpawnType.SOLID -> sendParticle(player, ParticleTypes.HAPPY_VILLAGER, fx, fy, fz)
                SpawnType.AIR   -> sendParticle(player, ParticleTypes.END_ROD, fx, fy, fz)
                SpawnType.WATER -> sendParticle(player, ParticleTypes.BUBBLE,  fx, fy, fz)
            }
        }
    }

    private fun sendParticle(player: ServerPlayerEntity, particle: ParticleEffect, x: Double, y: Double, z: Double) {
        player.networkHandler.sendPacket(
            ParticleS2CPacket(particle, true, x, y, z, 0f, 0f, 0f, 0f, 1)
        )
    }

    // ── Box builders ───────────────────────────────────────────────────────────

    private fun drawHollowBox(
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
        faceState: BlockState, frameState: BlockState,
        requests: MutableList<BoxRequest>,
        drawFaces: Boolean = true
    ) {
        val e = 0.05
        val f = 0.01

        requests.add(BoxRequest(minX - e, minY, minZ - e, minX + e, maxY, minZ + e, frameState))
        requests.add(BoxRequest(maxX - e, minY, minZ - e, maxX + e, maxY, minZ + e, frameState))
        requests.add(BoxRequest(minX - e, minY, maxZ - e, minX + e, maxY, maxZ + e, frameState))
        requests.add(BoxRequest(maxX - e, minY, maxZ - e, maxX + e, maxY, maxZ + e, frameState))

        requests.add(BoxRequest(minX + e, minY - e, minZ - e, maxX - e, minY + e, minZ + e, frameState))
        requests.add(BoxRequest(minX + e, minY - e, maxZ - e, maxX - e, minY + e, maxZ + e, frameState))
        requests.add(BoxRequest(minX - e, minY - e, minZ + e, minX + e, minY + e, maxZ - e, frameState))
        requests.add(BoxRequest(maxX - e, minY - e, minZ + e, maxX + e, minY + e, maxZ - e, frameState))

        requests.add(BoxRequest(minX + e, maxY - e, minZ - e, maxX - e, maxY + e, minZ + e, frameState))
        requests.add(BoxRequest(minX + e, maxY - e, maxZ - e, maxX - e, maxY + e, maxZ + e, frameState))
        requests.add(BoxRequest(minX - e, maxY - e, minZ + e, minX + e, maxY + e, maxZ - e, frameState))
        requests.add(BoxRequest(maxX - e, maxY - e, minZ + e, maxX + e, maxY + e, maxZ - e, frameState))

        if (drawFaces) {
            requests.add(BoxRequest(minX + e, minY + e, minZ - f, maxX - e, maxY - e, minZ + f, faceState))
            requests.add(BoxRequest(minX + e, minY + e, maxZ - f, maxX - e, maxY - e, maxZ + f, faceState))
            requests.add(BoxRequest(minX - f, minY + e, minZ + e, minX + f, maxY - e, maxZ - e, faceState))
            requests.add(BoxRequest(maxX - f, minY + e, minZ + e, maxX + f, maxY - e, maxZ - e, faceState))
            requests.add(BoxRequest(minX + e, minY - f, minZ + e, maxX - e, minY + f, maxZ - e, faceState))
            requests.add(BoxRequest(minX + e, maxY - f, minZ + e, maxX - e, maxY + f, maxZ - e, faceState))
        }
    }

    private fun drawWireframeEdges(
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
        edgeState: BlockState,
        thickness: Double = 0.12,
        requests: MutableList<BoxRequest>
    ) {
        val t = thickness / 2

        requests.add(BoxRequest(minX - t, minY - t, minZ - t, maxX + t, minY + t, minZ + t, edgeState))
        requests.add(BoxRequest(minX - t, minY - t, maxZ - t, maxX + t, minY + t, maxZ + t, edgeState))
        requests.add(BoxRequest(minX - t, minY - t, minZ - t, minX + t, minY + t, maxZ + t, edgeState))
        requests.add(BoxRequest(maxX - t, minY - t, minZ - t, maxX + t, minY + t, maxZ + t, edgeState))

        requests.add(BoxRequest(minX - t, maxY - t, minZ - t, maxX + t, maxY + t, minZ + t, edgeState))
        requests.add(BoxRequest(minX - t, maxY - t, maxZ - t, maxX + t, maxY + t, maxZ + t, edgeState))
        requests.add(BoxRequest(minX - t, maxY - t, minZ - t, minX + t, maxY + t, maxZ + t, edgeState))
        requests.add(BoxRequest(maxX - t, maxY - t, minZ - t, maxX + t, maxY + t, maxZ + t, edgeState))

        requests.add(BoxRequest(minX - t, minY - t, minZ - t, minX + t, maxY + t, minZ + t, edgeState))
        requests.add(BoxRequest(maxX - t, minY - t, minZ - t, maxX + t, maxY + t, minZ + t, edgeState))
        requests.add(BoxRequest(minX - t, minY - t, maxZ - t, minX + t, maxY + t, maxZ + t, edgeState))
        requests.add(BoxRequest(maxX - t, minY - t, maxZ - t, maxX + t, maxY + t, maxZ + t, edgeState))
    }

    // ── Request builders ───────────────────────────────────────────────────────

    private fun buildSelectionRequests(
        player: ServerPlayerEntity,
        sel: com.cobblespawnregions.PlayerSelection,
        requests: MutableList<BoxRequest>
    ) {
        when (sel.mode) {
            StickMode.COORDS -> {
                val p1 = sel.pos1
                val p2 = sel.pos2

                val faceBlock  = Blocks.ORANGE_STAINED_GLASS.defaultState
                val frameBlock = Blocks.ORANGE_CONCRETE.defaultState
                val edgeBlock  = Blocks.RED_CONCRETE.defaultState

                if (p1 != null && p2 != null) {
                    val bMinX = minOf(p1.x, p2.x).toDouble()
                    val bMinY = minOf(p1.y, p2.y).toDouble()
                    val bMinZ = minOf(p1.z, p2.z).toDouble()
                    val bMaxX = maxOf(p1.x, p2.x).toDouble() + 1.0
                    val bMaxY = maxOf(p1.y, p2.y).toDouble() + 1.0
                    val bMaxZ = maxOf(p1.z, p2.z).toDouble() + 1.0

                    drawHollowBox(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ, faceBlock, frameBlock, requests)
                    drawWireframeEdges(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ, edgeBlock, requests = requests)
                } else {
                    if (p1 != null) drawHollowBox(p1.x + 0.25, p1.y + 0.25, p1.z + 0.25, p1.x + 0.75, p1.y + 0.75, p1.z + 0.75, faceBlock, frameBlock, requests)
                    if (p2 != null) drawHollowBox(p2.x + 0.25, p2.y + 0.25, p2.z + 0.25, p2.x + 0.75, p2.y + 0.75, p2.z + 0.75, faceBlock, frameBlock, requests)
                }
            }
            StickMode.CHUNK -> {
                val c1 = sel.chunkPos1
                val c2 = sel.chunkPos2
                val yMin = player.world.bottomY.toDouble()
                val yMax = player.world.topY.toDouble()

                val faceBlock  = Blocks.YELLOW_STAINED_GLASS.defaultState
                val frameBlock = Blocks.YELLOW_CONCRETE.defaultState
                val edgeBlock  = Blocks.RED_CONCRETE.defaultState

                if (c1 != null && c2 != null) {
                    val bMinX = minOf(c1.startX, c2.startX).toDouble()
                    val bMaxX = maxOf(c1.endX,   c2.endX  ).toDouble() + 1.0
                    val bMinZ = minOf(c1.startZ, c2.startZ).toDouble()
                    val bMaxZ = maxOf(c1.endZ,   c2.endZ  ).toDouble() + 1.0

                    drawHollowBox(bMinX, yMin, bMinZ, bMaxX, yMax, bMaxZ, faceBlock, frameBlock, requests)
                    drawWireframeEdges(bMinX, yMin, bMinZ, bMaxX, yMax, bMaxZ, edgeBlock, requests = requests)
                } else {
                    if (c1 != null) drawHollowBox(c1.startX.toDouble(), yMin, c1.startZ.toDouble(), c1.startX + 16.0, yMax, c1.startZ + 16.0, faceBlock, frameBlock, requests)
                    if (c2 != null) drawHollowBox(c2.startX.toDouble(), yMin, c2.startZ.toDouble(), c2.startX + 16.0, yMax, c2.startZ + 16.0, faceBlock, frameBlock, requests)
                }
            }
        }
    }

    private fun buildRegionRequests(
        region: RegionData,
        priorityRank: Int,
        priorityRegionCount: Int,
        requests: MutableList<BoxRequest>
    ) {
        val rMinX = minOf(region.pos1.x, region.pos2.x).toDouble()
        val rMinY = minOf(region.pos1.y, region.pos2.y).toDouble()
        val rMinZ = minOf(region.pos1.z, region.pos2.z).toDouble()
        val rMaxX = maxOf(region.pos1.x, region.pos2.x).toDouble() + 1.0
        val rMaxY = maxOf(region.pos1.y, region.pos2.y).toDouble() + 1.0
        val rMaxZ = maxOf(region.pos1.z, region.pos2.z).toDouble() + 1.0
        val palette = priorityPalette(priorityRank, priorityRegionCount)

        drawHollowBox(rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ, palette.face, palette.frame, requests)
        drawWireframeEdges(rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ, palette.edge, requests = requests)

    }

    private fun priorityPalette(priorityRank: Int, priorityRegionCount: Int): RegionVisualPalette {
        if (priorityRegionCount <= 1) return priorityPalettes.first()
        val clampedRank = priorityRank.coerceIn(0, priorityRegionCount - 1)
        val paletteIndex = clampedRank * (priorityPalettes.size - 1) / (priorityRegionCount - 1)
        return priorityPalettes[paletteIndex.coerceIn(priorityPalettes.indices)]
    }
}
