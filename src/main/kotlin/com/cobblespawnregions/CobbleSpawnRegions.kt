package com.cobblespawnregions

import com.cobblespawnregions.utils.RegionCommands
import com.cobblespawnregions.utils.RegionParticleUtils
import com.cobblespawnregions.utils.RegionsConfig
import com.cobblespawnregions.utils.SpawnPointScanner
import com.cobblespawnregions.utils.SpawnPointStore
import com.everlastingutils.scheduling.SchedulerManager
import com.everlastingutils.utils.logDebug
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.component.DataComponentTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class StickMode { COORDS, CHUNK, SUB_REGION }

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

    val playerSelections = ConcurrentHashMap<UUID, PlayerSelection>()
    val activeVisualizations = ConcurrentHashMap<UUID, String>()


    @Volatile private var serverReady = false

    override fun onInitialize() {
        logger.info("Initializing CobbleSpawnRegions")

        RegionsConfig.initializeAndLoad()
        RegionCommands.register()

        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            SchedulerManager.onServerStarting(server)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            serverReady = true
            SpawnPointScanner.enqueueAllLoadedChunks(server)

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
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            serverReady = false
            SpawnPointScanner.clearQueue()
            SchedulerManager.shutdown("cobblespawnregions-particle-loop")
            SchedulerManager.shutdown("cobblespawnregions-scan-loop")
            playerSelections.clear()
            activeVisualizations.clear()
            SpawnPointStore.clearAll()
        }

        ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
            if (!serverReady) return@register
            if (world !is ServerWorld) return@register

            val dim      = world.registryKey.value.toString()
            val chunkPos = chunk.pos

            RegionsConfig.regions.values.forEach { region ->
                if (region.dimension != dim) return@forEach

                val rMinCX = minOf(region.pos1.x, region.pos2.x) shr 4
                val rMaxCX = maxOf(region.pos1.x, region.pos2.x) shr 4
                val rMinCZ = minOf(region.pos1.z, region.pos2.z) shr 4
                val rMaxCZ = maxOf(region.pos1.z, region.pos2.z) shr 4

                if (chunkPos.x < rMinCX || chunkPos.x > rMaxCX) return@forEach
                if (chunkPos.z < rMinCZ || chunkPos.z > rMaxCZ) return@forEach

                SpawnPointScanner.enqueueScan(region.regionId, region, chunkPos, world)
            }
        }

        registerInteractionEvents()
    }

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
            sendStatus(player, sel)
            ActionResult.SUCCESS
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
            "CHUNK"      -> StickMode.CHUNK
            "SUB_REGION" -> StickMode.SUB_REGION
            else         -> StickMode.COORDS
        }
    }

    private fun sendStatus(player: ServerPlayerEntity, sel: PlayerSelection) {
        val createCmd = when (sel.mode) {
            StickMode.SUB_REGION -> "/csr subregion create <parentRegion> <n>"
            else                 -> "/csr region create <n>"
        }
        when {
            sel.isBothSet  -> player.sendMessage(Text.literal(
                "§a[Regions] §fBoth points set — run §e$createCmd §fto save."
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