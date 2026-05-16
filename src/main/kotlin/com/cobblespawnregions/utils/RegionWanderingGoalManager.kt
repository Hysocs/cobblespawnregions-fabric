package com.cobblespawnregions.utils

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblespawnregions.mixin.MobEntityAccessor
import net.minecraft.entity.mob.MobEntity
import net.minecraft.server.MinecraftServer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RegionWanderingGoalManager {

    private val attached = ConcurrentHashMap.newKeySet<UUID>()

    fun attachIfConfigured(entity: PokemonEntity) {
        if (!attached.add(entity.uuid)) return

        val data = entity.pokemon.persistentData
        val regionId = data.getString(RegionEntityTracker.REGION_KEY)
        val entryKey = data.getString(RegionEntityTracker.ENTRY_KEY)
        if (regionId.isEmpty() || entryKey.isEmpty()) {
            attached.remove(entity.uuid)
            return
        }

        val region = RegionsConfig.getRegion(regionId)
        val entry = region?.selectedPokemon?.firstOrNull { RegionEntityTracker.entryKey(it) == entryKey }
        val settings = entry?.wanderingSettings
        if (settings == null || !settings.enabled) {
            attached.remove(entity.uuid)
            return
        }

        (entity as MobEntityAccessor).`cobblespawnregions$getGoalSelector`()
            .add(0, StayInRegionGoal(entity as MobEntity, regionId, settings))
    }

    fun attachLoadedForEntry(server: MinecraftServer, regionId: String, entryKey: String) {
        val region = RegionsConfig.getRegion(regionId) ?: return
        val world = server.worlds.firstOrNull { it.registryKey.value.toString() == region.dimension } ?: return
        val box = RegionSpawnHelper.regionBoundingBox(region)

        world.getEntitiesByClass(PokemonEntity::class.java, box) { entity ->
            val data = entity.pokemon.persistentData
            data.getString(RegionEntityTracker.REGION_KEY) == regionId &&
                    data.getString(RegionEntityTracker.ENTRY_KEY) == entryKey
        }.forEach(::attachIfConfigured)
    }

    fun forget(uuid: UUID) {
        attached.remove(uuid)
    }

    fun clearAll() {
        attached.clear()
    }
}
