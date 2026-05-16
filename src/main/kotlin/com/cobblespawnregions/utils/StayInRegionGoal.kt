package com.cobblespawnregions.utils

import net.minecraft.entity.ai.goal.Goal
import net.minecraft.entity.mob.MobEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import java.util.EnumSet

class StayInRegionGoal(
    private val entity: MobEntity,
    private val regionId: String,
    private val settings: RegionWanderingSettings,
    private val allowedBlocks: List<String>
) : Goal() {

    private var targetPos: Vec3d? = null
    private var ticksSinceCheck = entity.random.nextInt(settings.tickDelay.coerceAtLeast(1))
    private var nextPathAttemptTick = 0L
    private var cachedRegion: RegionData? = null
    private var lastDistanceToTargetSq = Double.MAX_VALUE
    private var stuckTicks = 0
    private var lastRepathTick = 0L

    init {
        controls = EnumSet.of(Control.MOVE)
    }

    override fun canStart(): Boolean {
        if (!settings.enabled) return false
        if (entity.world.time < nextPathAttemptTick) return false

        val delay = settings.tickDelay.coerceAtLeast(1)
        if (--ticksSinceCheck > 0) return false
        ticksSinceCheck = delay

        val region = RegionsConfig.getRegion(regionId) ?: return false
        val dimension = entity.world.registryKey.value.toString()
        if (region.dimension != dimension) return false

        val outside = !RegionsConfig.contains(region, entity.blockPos)
        if (!outside) return false

        cachedRegion = region
        return true
    }

    override fun start() {
        val region = cachedRegion ?: RegionsConfig.getRegion(regionId) ?: return
        targetPos = targetForMode(region, chooseNewRandom = true)

        val target = targetPos ?: return
        lastDistanceToTargetSq = Double.MAX_VALUE
        stuckTicks = 0
        if (!startPathTo(target)) {
            nextPathAttemptTick = entity.world.time + (settings.tickDelay.coerceAtLeast(1) * 4).coerceAtLeast(40)
        }
    }

    override fun shouldContinue(): Boolean {
        if (!settings.enabled) return false
        val region = cachedRegion ?: RegionsConfig.getRegion(regionId)?.also { cachedRegion = it } ?: return false
        return !RegionsConfig.contains(region, entity.blockPos)
    }

    override fun tick() {
        val region = cachedRegion ?: RegionsConfig.getRegion(regionId)?.also { cachedRegion = it } ?: return
        if (RegionsConfig.contains(region, entity.blockPos)) return

        val target = targetPos ?: targetForMode(region, chooseNewRandom = true).also { targetPos = it }
        val now = entity.world.time
        val distanceSq = target.squaredDistanceTo(entity.pos)

        if (entity.navigation.isIdle) {
            repath(region, now, chooseNewTarget = false)
            return
        }

        if (distanceSq + 0.25 < lastDistanceToTargetSq) {
            lastDistanceToTargetSq = distanceSq
            stuckTicks = 0
            return
        }

        stuckTicks++
        if (stuckTicks >= STUCK_REPATH_TICKS) {
            repath(region, now, chooseNewTarget = true)
        }
    }

    override fun stop() {
        targetPos = null
        entity.navigation.stop()
        lastDistanceToTargetSq = Double.MAX_VALUE
        stuckTicks = 0
    }

    private fun repath(region: RegionData, now: Long, chooseNewTarget: Boolean) {
        if (now - lastRepathTick < REPATH_COOLDOWN_TICKS) return

        val target = if (chooseNewTarget) targetForMode(region, chooseNewRandom = true) else targetPos ?: targetForMode(region, chooseNewRandom = false)
        targetPos = target

        if (startPathTo(target)) {
            lastRepathTick = now
            lastDistanceToTargetSq = target.squaredDistanceTo(entity.pos)
            stuckTicks = 0
        } else {
            lastRepathTick = now
            nextPathAttemptTick = now + REPATH_COOLDOWN_TICKS
        }
    }

    private fun startPathTo(target: Vec3d): Boolean {
        val path = entity.navigation.findPathTo(target.x, target.y, target.z, 0) ?: return false
        entity.navigation.stop()
        entity.navigation.startMovingAlong(path, settings.speed.coerceAtLeast(0.05))
        nextPathAttemptTick = entity.world.time + settings.tickDelay.coerceAtLeast(1)
        lastRepathTick = entity.world.time
        lastDistanceToTargetSq = target.squaredDistanceTo(entity.pos)
        return true
    }

    private fun targetForMode(region: RegionData, chooseNewRandom: Boolean): Vec3d =
        when (settings.returnTarget.uppercase()) {
            "CENTER" -> spawnPointNear(centerTarget(region)) ?: centerTarget(region)
            "CLOSEST" -> spawnPointNear(entity.pos) ?: closestTarget(region)
            else -> {
                if (chooseNewRandom) {
                    RegionSpawnHelper.pickRandomSpawnPos(regionId, allowedBlocks)?.let(Vec3d::ofBottomCenter)
                        ?: randomTarget(region)
                        ?: centerTarget(region)
                } else {
                    targetPos ?: spawnPointNear(centerTarget(region)) ?: centerTarget(region)
                }
            }
        }

    private fun spawnPointNear(origin: Vec3d): Vec3d? =
        RegionSpawnHelper.pickClosestSpawnPos(regionId, allowedBlocks, origin)?.let(Vec3d::ofBottomCenter)

    private fun randomTarget(region: RegionData): Vec3d? {
        val minX = minOf(region.pos1.x, region.pos2.x)
        val maxX = maxOf(region.pos1.x, region.pos2.x)
        val minZ = minOf(region.pos1.z, region.pos2.z)
        val maxZ = maxOf(region.pos1.z, region.pos2.z)

        repeat(12) {
            val x = entity.random.nextBetween(minX, maxX)
            val z = entity.random.nextBetween(minZ, maxZ)
            val y = entity.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
            val pos = BlockPos(x, y, z)

            if (RegionsConfig.contains(region, pos)) {
                return Vec3d.ofBottomCenter(pos)
            }
        }

        return null
    }

    private fun closestTarget(region: RegionData): Vec3d {
        val minX = minOf(region.pos1.x, region.pos2.x)
        val maxX = maxOf(region.pos1.x, region.pos2.x)
        val minY = minOf(region.pos1.y, region.pos2.y)
        val maxY = maxOf(region.pos1.y, region.pos2.y)
        val minZ = minOf(region.pos1.z, region.pos2.z)
        val maxZ = maxOf(region.pos1.z, region.pos2.z)

        val x = entity.blockPos.x.coerceIn(minX, maxX)
        val z = entity.blockPos.z.coerceIn(minZ, maxZ)
        val surfaceY = entity.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
        val y = surfaceY.coerceIn(minY, maxY)

        return Vec3d.ofBottomCenter(BlockPos(x, y, z))
    }

    private fun centerTarget(region: RegionData): Vec3d {
        val x = (region.pos1.x + region.pos2.x) / 2
        val z = (region.pos1.z + region.pos2.z) / 2
        val y = entity.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
        return Vec3d.ofBottomCenter(BlockPos(x, y, z))
    }

    private companion object {
        private const val STUCK_REPATH_TICKS = 30
        private const val REPATH_COOLDOWN_TICKS = 20L
    }
}
