package com.cobblespawnregions

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblespawnregions.utils.RegionCommands
import com.cobblespawnregions.utils.RegionBattleTracker
import com.cobblespawnregions.utils.RegionCatchingTracker
import com.cobblespawnregions.utils.RegionEntityTracker
import com.cobblespawnregions.utils.RegionParticleUtils
import com.cobblespawnregions.utils.RegionSpawnHelper
import com.cobblespawnregions.utils.RegionWanderingGoalManager
import com.cobblespawnregions.utils.RegionsConfig
import com.cobblespawnregions.utils.SpawnPointScanner
import com.cobblespawnregions.utils.SpawnPointStore
import com.everlastingutils.scheduling.SchedulerManager
import com.everlastingutils.utils.logDebug
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.Entity
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class StickMode { COORDS, CHUNK }

data class PlayerSelection(
    val mode: StickMode = StickMode.COORDS,
    var pos1: BlockPos? = null,
    var pos2: BlockPos? = null,
    var chunkPos1: ChunkPos? = null,
    var chunkPos2: ChunkPos? = null
) {
    val hasFirst   get() = if (mode == StickMode.CHUNK) chunkPos1 != null else pos1 != null
    val hasSecond  get() = if (mode == StickMode.CHUNK) chunkPos2 != null else pos2 != null
    val isBothSet  get() = hasFirst && hasSecond
}

object CobbleSpawnRegions : ModInitializer {

    private val logger = LoggerFactory.getLogger("cobblespawnregions")
    const val MOD_ID = "cobblespawnregions"
    private val battleTracker = RegionBattleTracker()
    private val catchingTracker = RegionCatchingTracker()

    val playerSelections = ConcurrentHashMap<UUID, PlayerSelection>()
    val activeVisualizations = ConcurrentHashMap<UUID, MutableSet<String>>()
    val particleUpdatePlayers = ConcurrentHashMap.newKeySet<UUID>()

    @Volatile private var serverReady = false
    @Volatile private var nextSpawnLoopCheckAtMs = 0L
    private val dimensionKeyCache = ConcurrentHashMap<String, RegistryKey<World>>()

    override fun onInitialize() {
        logger.info("Initializing CobbleSpawnRegions")

        RegionsConfig.initializeAndLoad()
        RegionEntityTracker.loadFromDisk()
        RegionCommands.register()
        battleTracker.registerEvents()
        catchingTracker.registerEvents()

        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            SchedulerManager.onServerStarting(server)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            serverReady = true
            SpawnPointScanner.enqueueAllLoadedChunks(server)

            // ── Rebuild entity tracker from already-loaded entities ────────────
            // Covers entities that were alive before this server session started.
            for (region in RegionsConfig.allRegions()) {
                val rWorld = server.getWorld(parseDimension(region.dimension)) ?: continue
                val box    = RegionSpawnHelper.regionBoundingBox(region)
                RegionEntityTracker.rebuildFromWorld(rWorld, region.regionId, box)
                logger.info(
                    "[CSR] Tracker rebuilt for '${region.regionId}': " +
                            "${RegionEntityTracker.countTotal(region.regionId)} entity/ies tracked."
                )
            }

            SchedulerManager.scheduleAtFixedRate(
                "cobblespawnregions-particle-loop",
                server, 0L, 500L, TimeUnit.MILLISECONDS
            ) {
                RegionParticleUtils.updateParticles(server)
            }

            SchedulerManager.scheduleAtFixedRate(
                "cobblespawnregions-scan-loop",
                server, 0L, 1L, TimeUnit.SECONDS
            ) {
                SpawnPointScanner.processPendingScans(count = 2)
            }

            SchedulerManager.scheduleAtFixedRate(
                "cobblespawnregions-spawn-loop",
                server, 0L, 1L, TimeUnit.SECONDS
            ) {
                processAllRegionSpawns(server)
            }

            SchedulerManager.scheduleAtFixedRate(
                "cobblespawnregions-tracker-save-loop",
                server, 5L, 5L, TimeUnit.SECONDS
            ) {
                RegionEntityTracker.flushIfDirty()
            }

            battleTracker.startCleanupScheduler(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            serverReady = false
            SpawnPointScanner.clearQueue()
            SchedulerManager.shutdown("cobblespawnregions-particle-loop")
            SchedulerManager.shutdown("cobblespawnregions-scan-loop")
            SchedulerManager.shutdown("cobblespawnregions-spawn-loop")
            SchedulerManager.shutdown("cobblespawnregions-tracker-save-loop")
            SchedulerManager.shutdown("cobblespawnregions-battle-cleanup")
            RegionEntityTracker.flushIfDirty()
            playerSelections.clear()
            activeVisualizations.clear()
            particleUpdatePlayers.clear()
            SpawnPointStore.clearAll()
            RegionEntityTracker.clearAll()
            RegionWanderingGoalManager.clearAll()
        }

        ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
            if (!serverReady) return@register
            if (world !is ServerWorld) return@register

            val dim      = world.registryKey.value.toString()
            val chunkPos = chunk.pos
            RegionEntityTracker.markChunkLoaded(world, chunkPos)

            RegionsConfig.allRegions().forEach { region ->
                if (region.dimension != dim) return@forEach

                val rMinCX = minOf(region.pos1.x, region.pos2.x) shr 4
                val rMaxCX = maxOf(region.pos1.x, region.pos2.x) shr 4
                val rMinCZ = minOf(region.pos1.z, region.pos2.z) shr 4
                val rMaxCZ = maxOf(region.pos1.z, region.pos2.z) shr 4

                if (chunkPos.x < rMinCX || chunkPos.x > rMaxCX) return@forEach
                if (chunkPos.z < rMinCZ || chunkPos.z > rMaxCZ) return@forEach

                SpawnPointScanner.enqueueScan(region.regionId, region, chunkPos, world)

                // Re-populate tracker with entities returning from hibernation
                val box = RegionSpawnHelper.regionBoundingBox(region)
                RegionEntityTracker.rebuildFromWorld(world, region.regionId, box)
                world.server.execute {
                    RegionEntityTracker.reconcileLoadedChunk(world, region.regionId, chunkPos)
                }
            }
        }

        ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
            if (!serverReady) return@register
            if (world !is ServerWorld) return@register
            RegionEntityTracker.markChunkUnloading(world, chunk.pos)
        }

        // ── Entity removal listener ────────────────────────────────────────────
        // Untrack our Pokémon when they are genuinely removed (killed, despawned,
        // changed dimension) — but NOT when their chunk is merely unloaded.
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
            if (entity is PokemonEntity && RegionEntityTracker.isManaged(entity)) {
                RegionEntityTracker.trackLoadedEntity(entity)
            }
        }

        ServerEntityEvents.ENTITY_UNLOAD.register { entity, _ ->
            if (entity !is PokemonEntity) return@register
            RegionWanderingGoalManager.forget(entity.uuid)
            val chunkIsUnloading = RegionEntityTracker.isChunkUnloading(entity)
            val reason = entity.removalReason
            if (chunkIsUnloading) {
                RegionEntityTracker.forgetLiveUuid(entity.uuid)
                // The entity is being saved with its chunk. Keep its spawn id
                // counted so distant parts of a large region cannot refill.
                return@register
            }
            if (reason == null) return@register
            when (reason) {
                Entity.RemovalReason.UNLOADED_TO_CHUNK,
                Entity.RemovalReason.UNLOADED_WITH_PLAYER -> {
                    RegionEntityTracker.forgetLiveUuid(entity.uuid)
                    // Entity is hibernating, not dead — leave it in the tracker.
                }
                else -> {
                    // KILLED, DISCARDED, CHANGED_DIMENSION, etc.
                    val wasTracked = RegionEntityTracker.isManaged(entity)
                    RegionEntityTracker.untrack(entity.uuid)
                    if (wasTracked) {
                        logDebug(
                            "Untracked ${entity.uuid} (reason=${reason.name}, " +
                                    "species=${entity.pokemon.species.name})", MOD_ID
                        )
                    }
                }
            }
        }

        registerInteractionEvents()
    }

    // ── Spawn driver ──────────────────────────────────────────────────────────

    private fun processAllRegionSpawns(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (now < nextSpawnLoopCheckAtMs) return

        var nextDueAt = Long.MAX_VALUE
        var hasActiveRegion = false

        for (region in RegionsConfig.allRegions()) {
            if (region.selectedPokemon.isEmpty()) continue
            hasActiveRegion = true

            val dueAt = RegionSpawnHelper.nextSpawnDueAt(region)
            if (now < dueAt) {
                if (dueAt < nextDueAt) nextDueAt = dueAt
                continue
            }

            val world = server.getWorld(parseDimension(region.dimension)) ?: continue
            try {
                RegionSpawnHelper.attemptSpawnInRegion(world, region, respectTimer = false)
                val nextRegionDueAt = RegionSpawnHelper.nextSpawnDueAt(region)
                if (nextRegionDueAt < nextDueAt) nextDueAt = nextRegionDueAt
            } catch (e: Exception) {
                logger.error("Error spawning for region '${region.regionId}'", e)
            }
        }

        nextSpawnLoopCheckAtMs = when {
            nextDueAt != Long.MAX_VALUE -> nextDueAt
            hasActiveRegion -> now + 1_000L
            else -> now + 5_000L
        }
    }

    private fun parseDimension(str: String): RegistryKey<World> {
        dimensionKeyCache[str]?.let { return it }
        val parts = str.split(":")
        val key = if (parts.size != 2) {
            logger.warn("Invalid dimension '$str', using 'minecraft:overworld'")
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"))
        } else {
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of(parts[0], parts[1]))
        }
        dimensionKeyCache[str] = key
        return key
    }

    // ── Stick interactions ───────────────────────────────────────────────────

    private fun registerInteractionEvents() {

        AttackBlockCallback.EVENT.register { player, world, hand, pos, _ ->
            if (world.isClient || hand != Hand.MAIN_HAND) return@register ActionResult.PASS
            if (player !is ServerPlayerEntity) return@register ActionResult.PASS
            val mode = getStickMode(player) ?: return@register ActionResult.PASS

            val sel = freshOrExisting(player.uuid, mode)

            when (mode) {
                StickMode.CHUNK -> {
                    val cp = ChunkPos(pos)
                    sel.chunkPos1 = cp
                    player.sendMessage(Text.literal(
                        "§a[Regions] §fChunk §e1 §fset to §b[${cp.x}, ${cp.z}] §7(${cp.startX},${cp.startZ} → ${cp.endX},${cp.endZ})"
                    ), false)
                }
                else -> {
                    sel.pos1 = pos
                    player.sendMessage(Text.literal(
                        "§a[Regions] §fPos §e1 §fset to §b(${pos.x}, ${pos.y}, ${pos.z})"
                    ), false)
                }
            }

            logDebug("${player.name.string} [${mode.name}] first point = $pos", MOD_ID)
            requestParticleUpdate(player.uuid)
            sendStatus(player, sel)
            ActionResult.SUCCESS
        }

        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (world.isClient || hand != Hand.MAIN_HAND) return@register ActionResult.PASS
            if (player !is ServerPlayerEntity) return@register ActionResult.PASS
            val mode = getStickMode(player) ?: return@register ActionResult.PASS

            val pos = hitResult.blockPos
            val sel = freshOrExisting(player.uuid, mode)

            when (mode) {
                StickMode.CHUNK -> {
                    val cp = ChunkPos(pos)
                    sel.chunkPos2 = cp
                    player.sendMessage(Text.literal(
                        "§a[Regions] §fChunk §e2 §fset to §b[${cp.x}, ${cp.z}] §7(${cp.startX},${cp.startZ} → ${cp.endX},${cp.endZ})"
                    ), false)
                }
                else -> {
                    sel.pos2 = pos
                    player.sendMessage(Text.literal(
                        "§a[Regions] §fPos §e2 §fset to §b(${pos.x}, ${pos.y}, ${pos.z})"
                    ), false)
                }
            }

            logDebug("${player.name.string} [${mode.name}] second point = $pos", MOD_ID)
            requestParticleUpdate(player.uuid)
            sendStatus(player, sel)
            ActionResult.SUCCESS
        }
    }

    fun requestParticleUpdate(uuid: UUID) {
        particleUpdatePlayers.add(uuid)
    }

    fun requestParticleUpdate(player: ServerPlayerEntity, reason: String, logRequest: Boolean = false) {
        particleUpdatePlayers.add(player.uuid)
        if (logRequest) {
            logger.info("[CSR-VISUAL] ${player.name.string} (${player.uuid}) requested visual update: $reason")
        }
    }

    private fun freshOrExisting(uuid: UUID, mode: StickMode): PlayerSelection {
        val existing = playerSelections[uuid]
        return if (existing != null && existing.mode == mode) existing
        else PlayerSelection(mode).also { playerSelections[uuid] = it }
    }

    fun getStickMode(player: ServerPlayerEntity): StickMode? {
        val nbt = player.getStackInHand(Hand.MAIN_HAND)
            .get(DataComponentTypes.CUSTOM_DATA)?.copyNbt() ?: return null
        if (!nbt.getBoolean("cobblespawnregions:is_region_stick")) return null
        return when (nbt.getString("cobblespawnregions:mode")) {
            "CHUNK" -> StickMode.CHUNK
            else    -> StickMode.COORDS
        }
    }

    private fun sendStatus(player: ServerPlayerEntity, sel: PlayerSelection) {
        when {
            sel.isBothSet  -> player.sendMessage(Text.literal(
                "§a[Regions] §fBoth points set — run §e/csr region create <n> §fto save."
            ), false)
            sel.hasFirst   -> player.sendMessage(Text.literal(
                "§a[Regions] §fNow §eright-click §fa block to set the second point."
            ), false)
            sel.hasSecond  -> player.sendMessage(Text.literal(
                "§a[Regions] §fNow §eleft-click §fa block to set the first point."
            ), false)
        }
    }
}
