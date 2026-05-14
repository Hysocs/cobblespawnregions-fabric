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
    private val settings: RegionWanderingSettings
) : Goal() {

    private var targetPos: Vec3d? = null
    private var ticksSinceCheck = entity.random.nextInt(settings.tickDelay.coerceAtLeast(1))

    init {
        controls = EnumSet.of(Control.MOVE)
    }

    override fun canStart(): Boolean {
        if (!settings.enabled) return false

        val delay = settings.tickDelay.coerceAtLeast(1)
        if (--ticksSinceCheck > 0) return false
        ticksSinceCheck = delay

        val region = RegionsConfig.getRegion(regionId) ?: return false
        val dimension = entity.world.registryKey.value.toString()
        if (region.dimension != dimension) return false

        return !RegionsConfig.contains(region, entity.blockPos)
    }

    override fun start() {
        entity.navigation.stop()

        val region = RegionsConfig.getRegion(regionId) ?: return
        targetPos = if (settings.returnTarget.equals("CENTER", ignoreCase = true)) {
            centerTarget(region)
        } else {
            randomTarget(region) ?: centerTarget(region)
        }

        val target = targetPos ?: return
        val path = entity.navigation.findPathTo(target.x, target.y, target.z, 0)
        if (path != null) {
            entity.navigation.startMovingAlong(path, settings.speed.coerceAtLeast(0.05))
        }
    }

    override fun shouldContinue(): Boolean {
        if (!settings.enabled) return false
        val region = RegionsConfig.getRegion(regionId) ?: return false
        return !entity.navigation.isIdle && !RegionsConfig.contains(region, entity.blockPos)
    }

    override fun stop() {
        targetPos = null
        entity.navigation.stop()
    }

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

    private fun centerTarget(region: RegionData): Vec3d {
        val x = (region.pos1.x + region.pos2.x) / 2
        val z = (region.pos1.z + region.pos2.z) / 2
        val y = entity.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
        return Vec3d.ofBottomCenter(BlockPos(x, y, z))
    }
}
