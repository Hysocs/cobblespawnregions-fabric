package com.cobblespawnregions.utils

import com.cobblespawnregions.CobbleSpawnRegions
import com.cobblespawnregions.StickMode
import com.cobblespawnregions.gui.RegionEditorGui
import com.cobblespawnregions.gui.RegionListGui
import com.everlastingutils.command.CommandManager
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.concurrent.ConcurrentHashMap

object RegionCommands {

    private val manager = CommandManager(CobbleSpawnRegions.MOD_ID, 2, 2)

    fun register() {
        manager.command("csr") {
            subcommand("giveclaimstick") {
                then(
                    RequiredArgumentBuilder.argument<ServerCommandSource, String>("mode", StringArgumentType.word())
                        .suggests { _, builder ->
                            builder.suggest("coords")
                            builder.suggest("chunk")
                            builder.buildFuture()
                        }
                        .executes { ctx ->
                            val player = playerOrError(ctx.source) ?: return@executes 0
                            val mode = parseStickMode(StringArgumentType.getString(ctx, "mode"), ctx.source)
                                ?: return@executes 0
                            giveStick(player, ctx.source, mode)
                            1
                        }
                )
            }

            registerFlatRegionCommands()

            subcommand("region") {
                registerLegacyRegionCommands()
            }

            subcommand("check") {
                executes { ctx -> checkCurrentRegion(ctx.source) }
            }

            subcommand("editgui") {
                executes { ctx -> openRegionList(ctx.source) }
            }

            subcommand("reload") {
                executes { ctx ->
                    RegionsConfig.reloadBlocking()
                    SpawnPointStore.clearAll()
                    SpawnPointScanner.enqueueAllLoadedChunks(ctx.source.server)
                    ctx.source.sendMessage(Text.literal("§a[CSR] §fConfig reloaded."))
                    1
                }
            }
        }

        manager.register()
    }

    private fun CommandManager.CommandConfig.registerFlatRegionCommands() {
        subcommand("create") {
            then(nameArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                createRegion(player, ctx.source, StringArgumentType.getString(ctx, "name"))
            })
        }

        subcommand("list") {
            executes { ctx -> listRegions(ctx.source) }
        }

        subcommand("delete") {
            then(regionIdArg().executes { ctx ->
                deleteRegion(ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("visualize") {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                toggleVisualization(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("edit") {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                openRegionEditor(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("priority") {
            then(regionIdArg()
                .then(RequiredArgumentBuilder.argument<ServerCommandSource, Int>("value", IntegerArgumentType.integer(-1000, 1000))
                    .executes { ctx ->
                        setRegionPriority(
                            ctx.source,
                            StringArgumentType.getString(ctx, "regionId"),
                            IntegerArgumentType.getInteger(ctx, "value")
                        )
                    }
                )
            )
        }

        subcommand("gui") {
            executes { ctx -> openRegionList(ctx.source) }
        }
    }

    private fun CommandManager.SubcommandConfig.registerLegacyRegionCommands() {
        subcommand("create") {
            then(nameArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                createRegion(player, ctx.source, StringArgumentType.getString(ctx, "name"))
            })
        }

        subcommand("list") {
            executes { ctx -> listRegions(ctx.source) }
        }

        subcommand("delete") {
            then(regionIdArg().executes { ctx ->
                deleteRegion(ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("visualize") {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                toggleVisualization(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("editgui") {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                openRegionEditor(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("priority") {
            then(regionIdArg()
                .then(RequiredArgumentBuilder.argument<ServerCommandSource, Int>("value", IntegerArgumentType.integer(-1000, 1000))
                    .executes { ctx ->
                        setRegionPriority(
                            ctx.source,
                            StringArgumentType.getString(ctx, "regionId"),
                            IntegerArgumentType.getInteger(ctx, "value")
                        )
                    }
                )
            )
        }
    }

    private fun openRegionList(source: ServerCommandSource): Int {
        val player = playerOrError(source) ?: return 0
        RegionListGui.open(player)
        return 1
    }

    private fun listRegions(source: ServerCommandSource): Int {
        val regions = RegionsConfig.regions
        if (regions.isEmpty()) {
            source.sendMessage(Text.literal("§a[CSR] §fNo regions defined yet."))
            return 1
        }

        source.sendMessage(Text.literal("§a[CSR] §f${regions.size} region(s):"))
        RegionsConfig.regionsInPriorityOrder().forEach { r ->
            source.sendMessage(Text.literal(
                "§7 - §e${r.regionName} §8(${r.regionId}) §7[${r.mode}] " +
                        "§f(${r.pos1.x},${r.pos1.y},${r.pos1.z}) -> (${r.pos2.x},${r.pos2.y},${r.pos2.z}) " +
                        "§7| priority §f${r.priority} §7| ${r.dimension}"
            ))
        }
        return 1
    }

    private fun deleteRegion(source: ServerCommandSource, regionId: String): Int {
        if (!RegionsConfig.removeRegion(regionId)) {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }

        val affectedPlayers = mutableListOf<java.util.UUID>()
        CobbleSpawnRegions.activeVisualizations.entries.removeIf { entry ->
            if (entry.value.remove(regionId)) affectedPlayers.add(entry.key)
            entry.value.isEmpty()
        }
        affectedPlayers.forEach(CobbleSpawnRegions::requestParticleUpdate)
        RegionEntityTracker.clearRegion(regionId)
        SpawnPointStore.clearRegion(regionId)
        source.sendMessage(Text.literal("§a[CSR] §fDeleted region §e$regionId§f."))
        return 1
    }

    private fun toggleVisualization(player: ServerPlayerEntity, source: ServerCommandSource, regionId: String): Int {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }

        val visualizations = CobbleSpawnRegions.activeVisualizations
            .computeIfAbsent(player.uuid) { ConcurrentHashMap.newKeySet() }
        if (visualizations.remove(regionId)) {
            if (visualizations.isEmpty()) {
                CobbleSpawnRegions.activeVisualizations.remove(player.uuid)
            }
            CobbleSpawnRegions.requestParticleUpdate(player, "stopped visualizing $regionId", logRequest = true)
            player.sendMessage(Text.literal("§a[CSR] §fStopped visualizing §e${region.regionName}§f."), false)
        } else {
            visualizations.add(regionId)
            CobbleSpawnRegions.requestParticleUpdate(player, "started visualizing $regionId", logRequest = true)
            player.sendMessage(Text.literal(
                "§a[CSR] §fVisualizing §e${region.regionName} §7(priority ${region.priority})§f."
            ), false)
        }
        return 1
    }

    private fun openRegionEditor(player: ServerPlayerEntity, source: ServerCommandSource, regionId: String): Int {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        RegionEditorGui.open(player, regionId)
        player.sendMessage(Text.literal("§a[CSR] §fOpened editor for §e${region.regionName}§f."), false)
        return 1
    }

    private fun setRegionPriority(source: ServerCommandSource, regionId: String, value: Int): Int {
        val region = RegionsConfig.updateRegion(regionId) { it.priority = value } ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        source.sendMessage(Text.literal(
            "§a[CSR] §fSet §e${region.regionName} §fpriority to §b${region.priority}§f."
        ))
        return 1
    }

    private fun checkCurrentRegion(source: ServerCommandSource): Int {
        val player = playerOrError(source) ?: return 0
        val pos = player.blockPos
        val dim = player.serverWorld.registryKey.value.toString()

        val matches = RegionsConfig.regionsAt(pos, dim)
        val region = matches.firstOrNull()

        if (region == null) {
            player.sendMessage(Text.literal("§a[CSR] §fYou are §cnot §finside any region."), false)
            return 1
        }

        val restr = region.spawnRestrictions
        player.sendMessage(Text.literal("§a[CSR] §fControlling region: §e${region.regionName} §8(${region.regionId}) §7priority §f${region.priority}"), false)
        if (matches.size > 1) {
            player.sendMessage(Text.literal("§a[CSR] §fOverlaps here: §7" +
                    matches.drop(1).joinToString(", ") { "${it.regionName}(${it.priority})" }), false)
        }
        player.sendMessage(Text.literal("§a[CSR] §fActive restrictions:"), false)
        player.sendMessage(Text.literal("§7  disableAll: ${flag(restr.disableAll)}"), false)
        player.sendMessage(Text.literal("§7  excludeOwnedPokemon: ${flag(restr.excludeOwnedPokemon)}"), false)
        player.sendMessage(Text.literal("§7  blockedSpecies (${restr.disallowedSpecies.size}): §f${restr.disallowedSpecies.joinToString(", ").ifEmpty { "none" }}"), false)
        return 1
    }

    private fun giveStick(player: ServerPlayerEntity, source: ServerCommandSource, mode: StickMode): Int {
        val stick = ItemStack(Items.STICK, 1)

        val (label, plainName) = when (mode) {
            StickMode.CHUNK -> "§bChunk Claim Stick" to "Chunk Claim Stick"
            else -> "§6Coords Claim Stick" to "Coords Claim Stick"
        }
        stick.set(DataComponentTypes.CUSTOM_NAME, Text.literal(label))

        val nbt = NbtCompound()
        nbt.putBoolean("cobblespawnregions:is_region_stick", true)
        nbt.putString("cobblespawnregions:mode", mode.name)
        stick.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt))

        if (!player.inventory.insertStack(stick)) player.dropItem(stick, false)

        source.sendFeedback(
            { Text.literal("§aGave §f$plainName §ato §f${player.name.string}§a.") },
            true
        )
        return 1
    }

    private fun createRegion(player: ServerPlayerEntity, source: ServerCommandSource, name: String): Int {
        val sel = CobbleSpawnRegions.playerSelections[player.uuid]

        if (sel == null || !sel.isBothSet) {
            player.sendMessage(Text.literal(
                "§c[CSR] §fUse a §6Coords §for §bChunk §fclaim stick and select both points first."
            ), false)
            return 0
        }

        val regionId = sanitize(name)
        if (RegionsConfig.getRegion(regionId) != null) {
            player.sendMessage(Text.literal("§c[CSR] §fRegion §e$regionId §falready exists."), false)
            return 0
        }

        val dimension = player.serverWorld.registryKey.value.toString()
        val world = player.serverWorld

        val (p1, p2) = when (sel.mode) {
            StickMode.CHUNK -> {
                val c1 = sel.chunkPos1!!
                val c2 = sel.chunkPos2!!
                SerializableBlockPos(minOf(c1.startX, c2.startX), world.bottomY, minOf(c1.startZ, c2.startZ)) to
                        SerializableBlockPos(maxOf(c1.endX, c2.endX), world.topY, maxOf(c1.endZ, c2.endZ))
            }
            else -> SerializableBlockPos.fromBlockPos(sel.pos1!!) to SerializableBlockPos.fromBlockPos(sel.pos2!!)
        }

        RegionsConfig.addRegion(
            RegionData(
                regionId = regionId,
                regionName = name,
                pos1 = p1,
                pos2 = p2,
                dimension = dimension,
                mode = sel.mode.name
            )
        )
        CobbleSpawnRegions.playerSelections.remove(player.uuid)
        CobbleSpawnRegions.requestParticleUpdate(player.uuid)

        SpawnPointScanner.enqueueLoadedChunks(regionId, RegionsConfig.getRegion(regionId)!!, source.server)

        player.sendMessage(Text.literal(
            "§a[CSR] §fRegion §e$name §f[${sel.mode.name}] created. " +
                    "§b(${p1.x},${p1.y},${p1.z}) §f-> §b(${p2.x},${p2.y},${p2.z}) §7in $dimension"
        ), false)
        return 1
    }

    private fun nameArg() =
        RequiredArgumentBuilder.argument<ServerCommandSource, String>("name", StringArgumentType.word())!!

    private fun regionIdArg() =
        RequiredArgumentBuilder.argument<ServerCommandSource, String>("regionId", StringArgumentType.word())
            .suggests { _, builder ->
                RegionsConfig.regions.keys.forEach { builder.suggest(it) }
                builder.buildFuture()
            }!!

    private fun parseStickMode(raw: String, source: ServerCommandSource): StickMode? =
        when (raw.lowercase()) {
            "coords" -> StickMode.COORDS
            "chunk" -> StickMode.CHUNK
            else -> {
                source.sendError(Text.literal("Unknown mode '$raw'. Use: coords, chunk."))
                null
            }
        }

    private fun sanitize(name: String) = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")

    private fun playerOrError(source: ServerCommandSource): ServerPlayerEntity? {
        val player = source.player
        if (player == null) source.sendError(Text.literal("Only players can use this command."))
        return player
    }
}

private fun flag(b: Boolean) = if (b) "§atrue" else "§cfalse"
