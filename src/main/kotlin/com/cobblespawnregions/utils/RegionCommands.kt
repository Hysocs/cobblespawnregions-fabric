package com.cobblespawnregions.utils

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
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
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.Box
import net.minecraft.world.Heightmap
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object RegionCommands {

    private val logger = LoggerFactory.getLogger("RegionCommands")
    private val manager = CommandManager(CobbleSpawnRegions.MOD_ID, 2, 2)

    fun register() {
        manager.command("csr", permission = permission("csr")) {
            subcommand("giveclaimstick", permission = permission("giveclaimstick")) {
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

            subcommand("region", permission = permission("region")) {
                registerLegacyRegionCommands()
            }

            subcommand("check", permission = permission("check")) {
                executes { ctx -> checkCurrentRegion(ctx.source) }
            }

            subcommand("editgui", permission = permission("editgui")) {
                executes { ctx -> openRegionList(ctx.source) }
            }

            subcommand("reload", permission = permission("reload")) {
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

    private fun permission(key: String): String = RegionsConfig.commandPermission(key)

    private fun CommandManager.CommandConfig.registerFlatRegionCommands() {
        subcommand("create", permission = permission("create")) {
            then(nameArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                createRegion(player, ctx.source, StringArgumentType.getString(ctx, "name"))
            })
        }

        subcommand("list", permission = permission("list")) {
            executes { ctx -> listRegions(ctx.source) }
        }

        subcommand("delete", permission = permission("delete")) {
            then(regionIdArg().executes { ctx ->
                deleteRegion(ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("visualize", permission = permission("visualize")) {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                toggleVisualization(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("edit", permission = permission("edit")) {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                openRegionEditor(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("teleport", permission = permission("teleport")) {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                teleportToRegion(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("tp", permission = permission("tp")) {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                teleportToRegion(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("killspawned", permission = permission("killspawned")) {
            then(regionIdArg().executes { ctx ->
                killSpawnedInRegion(ctx.source, StringArgumentType.getString(ctx, "regionId"))
            }.then(RequiredArgumentBuilder.argument<ServerCommandSource, String>("mode", StringArgumentType.word())
                .suggests { _, builder ->
                    builder.suggest("loaded")
                    builder.suggest("tracked")
                    builder.buildFuture()
                }
                .executes { ctx ->
                    killSpawnedInRegion(
                        ctx.source,
                        StringArgumentType.getString(ctx, "regionId"),
                        StringArgumentType.getString(ctx, "mode")
                    )
                }
            ))
        }

        subcommand("addmon", permission = permission("addmon")) {
            then(pokemonCommandArgs { source, regionId, pokemonName, formName, aspects ->
                addPokemon(source, regionId, pokemonName, formName, aspects)
            })
        }

        subcommand("removemon", permission = permission("removemon")) {
            then(pokemonCommandArgs { source, regionId, pokemonName, formName, aspects ->
                removePokemon(source, regionId, pokemonName, formName, aspects)
            })
        }

        subcommand("rename", permission = permission("rename")) {
            then(regionIdArg()
                .then(RequiredArgumentBuilder.argument<ServerCommandSource, String>("newName", StringArgumentType.word())
                    .executes { ctx ->
                        renameRegion(ctx.source, StringArgumentType.getString(ctx, "regionId"), StringArgumentType.getString(ctx, "newName"))
                    }
                )
            )
        }

        subcommand("inspectnearest", permission = permission("inspectnearest")) {
            executes { ctx -> inspectNearest(ctx.source) }
        }

        subcommand("forcespawn", permission = permission("forcespawn")) {
            then(regionIdArg().executes { ctx ->
                forceSpawn(ctx.source, StringArgumentType.getString(ctx, "regionId"), 1)
            }.then(RequiredArgumentBuilder.argument<ServerCommandSource, Int>("amount", IntegerArgumentType.integer(1, 100))
                .executes { ctx ->
                    forceSpawn(
                        ctx.source,
                        StringArgumentType.getString(ctx, "regionId"),
                        IntegerArgumentType.getInteger(ctx, "amount")
                    )
                }
            ))
        }

        subcommand("info", permission = permission("info")) {
            then(regionIdArg().executes { ctx ->
                showRegionInfo(ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("clone", permission = permission("clone")) {
            then(regionIdArg()
                .then(RequiredArgumentBuilder.argument<ServerCommandSource, String>("newName", StringArgumentType.word())
                    .executes { ctx ->
                        val player = playerOrError(ctx.source) ?: return@executes 0
                        cloneRegionFromSelection(player, ctx.source, StringArgumentType.getString(ctx, "regionId"), StringArgumentType.getString(ctx, "newName"))
                    }
                )
            )
        }

        subcommand("setspawnamount", permission = permission("setspawnamount")) {
            then(regionIdArg()
                .then(RequiredArgumentBuilder.argument<ServerCommandSource, Int>("amount", IntegerArgumentType.integer(1, 100))
                    .executes { ctx ->
                        setSpawnAmount(
                            ctx.source,
                            StringArgumentType.getString(ctx, "regionId"),
                            IntegerArgumentType.getInteger(ctx, "amount")
                        )
                    }
                )
            )
        }

        subcommand("playeractivation", permission = permission("playeractivation")) {
            then(regionIdArg()
                .then(RequiredArgumentBuilder.argument<ServerCommandSource, String>("enabled", StringArgumentType.word())
                    .suggests { _, builder ->
                        builder.suggest("true")
                        builder.suggest("false")
                        builder.buildFuture()
                    }
                    .then(RequiredArgumentBuilder.argument<ServerCommandSource, Int>("range", IntegerArgumentType.integer(0, 512))
                        .executes { ctx ->
                            setPlayerActivation(
                                ctx.source,
                                StringArgumentType.getString(ctx, "regionId"),
                                StringArgumentType.getString(ctx, "enabled"),
                                IntegerArgumentType.getInteger(ctx, "range")
                            )
                        }
                    )
                    .executes { ctx ->
                        setPlayerActivation(
                            ctx.source,
                            StringArgumentType.getString(ctx, "regionId"),
                            StringArgumentType.getString(ctx, "enabled"),
                            null
                        )
                    }
                )
            )
        }

        subcommand("forcechunks", permission = permission("forcechunks")) {
            then(regionIdArg()
                .then(RequiredArgumentBuilder.argument<ServerCommandSource, String>("enabled", StringArgumentType.word())
                    .suggests { _, builder ->
                        builder.suggest("true")
                        builder.suggest("false")
                        builder.buildFuture()
                    }
                    .then(RequiredArgumentBuilder.argument<ServerCommandSource, Int>("maxChunks", IntegerArgumentType.integer(1, 256))
                        .then(RequiredArgumentBuilder.argument<ServerCommandSource, Int>("radius", IntegerArgumentType.integer(1, 32))
                            .executes { ctx ->
                                setForceChunks(
                                    ctx.source,
                                    StringArgumentType.getString(ctx, "regionId"),
                                    StringArgumentType.getString(ctx, "enabled"),
                                    IntegerArgumentType.getInteger(ctx, "maxChunks"),
                                    IntegerArgumentType.getInteger(ctx, "radius")
                                )
                            }
                        )
                        .executes { ctx ->
                            setForceChunks(
                                ctx.source,
                                StringArgumentType.getString(ctx, "regionId"),
                                StringArgumentType.getString(ctx, "enabled"),
                                IntegerArgumentType.getInteger(ctx, "maxChunks"),
                                null
                            )
                        }
                    )
                    .executes { ctx ->
                        setForceChunks(
                            ctx.source,
                            StringArgumentType.getString(ctx, "regionId"),
                            StringArgumentType.getString(ctx, "enabled"),
                            null,
                            null
                        )
                    }
                )
            )
        }

        subcommand("priority", permission = permission("priority")) {
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

        subcommand("gui", permission = permission("gui")) {
            executes { ctx -> openRegionList(ctx.source) }
        }
    }

    private fun CommandManager.SubcommandConfig.registerLegacyRegionCommands() {
        subcommand("create", permission = permission("region.create")) {
            then(nameArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                createRegion(player, ctx.source, StringArgumentType.getString(ctx, "name"))
            })
        }

        subcommand("list", permission = permission("region.list")) {
            executes { ctx -> listRegions(ctx.source) }
        }

        subcommand("delete", permission = permission("region.delete")) {
            then(regionIdArg().executes { ctx ->
                deleteRegion(ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("visualize", permission = permission("region.visualize")) {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                toggleVisualization(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("editgui", permission = permission("region.editgui")) {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                openRegionEditor(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("teleport", permission = permission("region.teleport")) {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                teleportToRegion(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("tp", permission = permission("region.tp")) {
            then(regionIdArg().executes { ctx ->
                val player = playerOrError(ctx.source) ?: return@executes 0
                teleportToRegion(player, ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("killspawned", permission = permission("region.killspawned")) {
            then(regionIdArg().executes { ctx ->
                killSpawnedInRegion(ctx.source, StringArgumentType.getString(ctx, "regionId"))
            })
        }

        subcommand("priority", permission = permission("region.priority")) {
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
        RegionsConfig.getRegion(regionId)?.let { region ->
            worldForRegion(source, region)?.let { world ->
                CobbleSpawnRegions.setRegionChunkTickets(world, region, enabled = false, respectCap = false)
            }
        }
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

    private fun teleportToRegion(player: ServerPlayerEntity, source: ServerCommandSource, regionId: String): Int {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        val world = worldForRegion(source, region) ?: return 0

        val minX = minOf(region.pos1.x, region.pos2.x)
        val maxX = maxOf(region.pos1.x, region.pos2.x)
        val minY = minOf(region.pos1.y, region.pos2.y)
        val maxY = maxOf(region.pos1.y, region.pos2.y)
        val minZ = minOf(region.pos1.z, region.pos2.z)
        val maxZ = maxOf(region.pos1.z, region.pos2.z)

        val x = (minX + maxX) / 2.0 + 0.5
        val z = (minZ + maxZ) / 2.0 + 0.5
        val surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x.toInt(), z.toInt())
        val y = if (surfaceY in minY..maxY) surfaceY.toDouble() else (minY + maxY) / 2.0 + 0.5

        player.teleport(world, x, y, z, player.yaw, player.pitch)
        player.sendMessage(Text.literal(
            "Â§a[CSR] Â§fTeleported to Â§e${region.regionName} Â§8(${region.regionId})Â§f."
        ), false)
        return 1
    }

    private fun killSpawnedInRegion(source: ServerCommandSource, regionId: String, mode: String = "loaded"): Int {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        val world = worldForRegion(source, region) ?: return 0
        val box = RegionSpawnHelper.regionBoundingBox(region)

        val spawned = world.getEntitiesByClass(PokemonEntity::class.java, box) { entity ->
            entity.pokemon.persistentData.getString(RegionEntityTracker.REGION_KEY) == regionId
        }

        spawned.forEach { entity ->
            RegionWanderingGoalManager.forget(entity.uuid)
            RegionEntityTracker.untrack(entity.uuid)
            entity.discard()
        }
        if (mode.equals("tracked", ignoreCase = true) || mode.equals("all", ignoreCase = true)) {
            RegionEntityTracker.clearRegion(regionId)
        } else if (!mode.equals("loaded", ignoreCase = true)) {
            source.sendError(Text.literal("Unknown kill mode '$mode'. Use: loaded or tracked."))
            return 0
        }
        RegionEntityTracker.flushIfDirty()

        val remainingTracked = RegionEntityTracker.countTotal(regionId)
        source.sendMessage(Text.literal(
            "Â§a[CSR] Â§fKilled Â§e${spawned.size} Â§floaded spawned Pokemon in Â§e${region.regionName}Â§f. " +
                    "Â§7Tracked remaining: Â§f${remainingTracked}Â§7."
        ))
        return 1
    }

    private fun addPokemon(
        source: ServerCommandSource,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>
    ): Int {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }

        val entry = try {
            RegionsConfig.createDefaultPokemonEntry(pokemonName, formName, aspects)
        } catch (e: IllegalArgumentException) {
            RegionsConfig.debugError(logger, "Failed to create Pokemon entry for command addmon: $pokemonName", e)
            source.sendError(Text.literal(e.message ?: "Unknown Pokemon '$pokemonName'."))
            return 0
        }

        if (!RegionsConfig.addPokemonToRegion(regionId, entry)) {
            source.sendError(Text.literal("That Pokemon entry already exists in '${region.regionName}'."))
            return 0
        }

        source.sendMessage(Text.literal(
            "Â§a[CSR] Â§fAdded Â§e${describePokemon(pokemonName, formName, aspects)} Â§fto Â§e${region.regionName}Â§f."
        ))
        return 1
    }

    private fun removePokemon(
        source: ServerCommandSource,
        regionId: String,
        pokemonName: String,
        formName: String?,
        aspects: Set<String>
    ): Int {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        if (!RegionsConfig.removePokemonFromRegion(regionId, pokemonName, formName, aspects)) {
            source.sendError(Text.literal("No matching Pokemon entry found in '${region.regionName}'."))
            return 0
        }
        source.sendMessage(Text.literal(
            "Â§a[CSR] Â§fRemoved Â§e${describePokemon(pokemonName, formName, aspects)} Â§ffrom Â§e${region.regionName}Â§f."
        ))
        return 1
    }

    private fun renameRegion(source: ServerCommandSource, regionId: String, newName: String): Int {
        val region = RegionsConfig.renameRegionDisplay(regionId, newName) ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        source.sendMessage(Text.literal("Â§a[CSR] Â§fRenamed region Â§e${region.regionId} Â§fto Â§e${region.regionName}Â§f."))
        return 1
    }

    private fun inspectNearest(source: ServerCommandSource): Int {
        val player = playerOrError(source) ?: return 0
        val range = 32.0
        val box = Box(
            player.x - range, player.y - range, player.z - range,
            player.x + range, player.y + range, player.z + range
        )
        val nearest = player.serverWorld.getEntitiesByClass(PokemonEntity::class.java, box) { true }
            .minByOrNull { it.squaredDistanceTo(player) } ?: run {
                source.sendError(Text.literal("No Pokemon found within ${range.toInt()} blocks."))
                return 0
            }

        val pokemon = nearest.pokemon
        val data = pokemon.persistentData
        val conditions = PokemonConditionExtractor.extractAllConditions(pokemon)
        source.sendMessage(Text.literal("Â§a[CSR] Â§fNearest Pokemon: Â§e${pokemon.species.name} Â§7lv ${pokemon.level}"))
        source.sendMessage(Text.literal("Â§7UUID: Â§f${nearest.uuid}"))
        source.sendMessage(Text.literal("Â§7Form: Â§f${pokemon.form.name} Â§7Aspects: Â§f${pokemon.aspects.joinToString(", ").ifEmpty { "none" }}"))
        source.sendMessage(Text.literal("Â§7Managed Region: Â§f${data.getString(RegionEntityTracker.REGION_KEY).ifEmpty { "none" }}"))
        source.sendMessage(Text.literal("Â§7Conditions (${conditions.size}): Â§f${conditions.take(20).joinToString(", ")}"))
        if (conditions.size > 20) source.sendMessage(Text.literal("Â§8...and ${conditions.size - 20} more."))
        return 1
    }

    private fun forceSpawn(source: ServerCommandSource, regionId: String, amount: Int): Int {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        val world = worldForRegion(source, region) ?: return 0
        val spawned = RegionSpawnHelper.attemptSpawnInRegion(world, region, amount.coerceAtLeast(1), respectTimer = false)
        source.sendMessage(Text.literal(
            "Â§a[CSR] Â§fForce spawned Â§e${spawned.size} Â§fPokemon in Â§e${region.regionName}Â§f."
        ))
        return 1
    }

    private fun showRegionInfo(source: ServerCommandSource, regionId: String): Int {
        val region = RegionsConfig.getRegion(regionId) ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        source.sendMessage(Text.literal("Â§a[CSR] Â§e${region.regionName} Â§8(${region.regionId})"))
        source.sendMessage(Text.literal("Â§7Dimension: Â§f${region.dimension} Â§7Mode: Â§f${region.mode} Â§7Priority: Â§f${region.priority}"))
        source.sendMessage(Text.literal("Â§7Bounds: Â§f(${region.pos1.x},${region.pos1.y},${region.pos1.z}) -> (${region.pos2.x},${region.pos2.y},${region.pos2.z})"))
        source.sendMessage(Text.literal("Â§7Custom Pokemon: Â§f${region.selectedPokemon.size} Â§7Timer: Â§f${region.spawnTimerTicks} Â§7Amount: Â§f${region.spawnAmountPerSpawn}"))
        source.sendMessage(Text.literal("Â§7Max Alive: Â§f${region.maxTotalSpawns} Â§7Tracked: Â§f${RegionEntityTracker.countTotal(regionId)}"))
        source.sendMessage(Text.literal("Â§7Require Player: ${flag(region.requirePlayerInRange)} Â§7Range: Â§f${region.playerActivationRange.toInt()}"))
        source.sendMessage(Text.literal("Â§7Force Chunks: ${flag(region.forceChunkLoading)} Â§7Cap: Â§f${region.maxForceLoadedChunks} Â§7Radius: Â§f${region.chunkLoadRadius}"))
        source.sendMessage(Text.literal("Â§7Natural Disable All: ${flag(region.spawnRestrictions.disableAll)} Â§7Blocked Species: Â§f${region.spawnRestrictions.disallowedSpecies.size}"))
        return 1
    }

    private fun cloneRegionFromSelection(player: ServerPlayerEntity, source: ServerCommandSource, sourceRegionId: String, newName: String): Int {
        val sourceRegion = RegionsConfig.getRegion(sourceRegionId) ?: run {
            source.sendError(Text.literal("No region found with id '$sourceRegionId'."))
            return 0
        }
        val regionId = sanitize(newName)
        if (RegionsConfig.getRegion(regionId) != null) {
            source.sendError(Text.literal("Region '$regionId' already exists."))
            return 0
        }
        val bounds = selectionBounds(player, source) ?: return 0
        val cloned = RegionData(
            regionId = regionId,
            regionName = newName,
            pos1 = bounds.first,
            pos2 = bounds.second,
            dimension = player.serverWorld.registryKey.value.toString(),
            mode = CobbleSpawnRegions.playerSelections[player.uuid]?.mode?.name ?: "COORDS",
            priority = sourceRegion.priority,
            spawnTimerTicks = sourceRegion.spawnTimerTicks,
            spawnAmountPerSpawn = sourceRegion.spawnAmountPerSpawn,
            requirePlayerInRange = sourceRegion.requirePlayerInRange,
            playerActivationRange = sourceRegion.playerActivationRange,
            forceChunkLoading = sourceRegion.forceChunkLoading,
            chunkLoadRadius = sourceRegion.chunkLoadRadius,
            maxForceLoadedChunks = sourceRegion.maxForceLoadedChunks,
            selectedPokemon = sourceRegion.selectedPokemon.map(::copyPokemonEntry).toMutableList(),
            spawnRestrictions = RegionRestrictionConfig(
                disallowedSpecies = sourceRegion.spawnRestrictions.disallowedSpecies.toMutableList(),
                disallowedLabels = sourceRegion.spawnRestrictions.disallowedLabels.toMutableList(),
                exclusionConditions = sourceRegion.spawnRestrictions.exclusionConditions.toMutableList(),
                disableAll = sourceRegion.spawnRestrictions.disableAll,
                excludeOwnedPokemon = sourceRegion.spawnRestrictions.excludeOwnedPokemon
            ),
            maxTotalSpawns = sourceRegion.maxTotalSpawns
        )
        RegionsConfig.addRegion(cloned)
        CobbleSpawnRegions.playerSelections.remove(player.uuid)
        CobbleSpawnRegions.requestParticleUpdate(player.uuid)
        SpawnPointScanner.enqueueLoadedChunks(regionId, cloned, source.server)
        source.sendMessage(Text.literal("Â§a[CSR] Â§fCloned Â§e${sourceRegion.regionName} Â§fto Â§e${cloned.regionName}Â§f."))
        return 1
    }

    private fun setSpawnAmount(source: ServerCommandSource, regionId: String, amount: Int): Int {
        val region = RegionsConfig.updateRegion(regionId) { it.spawnAmountPerSpawn = amount } ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        source.sendMessage(Text.literal("Â§a[CSR] Â§fSet Â§e${region.regionName} Â§fspawn amount to Â§e${region.spawnAmountPerSpawn}Â§f."))
        return 1
    }

    private fun setPlayerActivation(source: ServerCommandSource, regionId: String, enabledRaw: String, range: Int?): Int {
        val enabled = parseBoolean(enabledRaw) ?: run {
            source.sendError(Text.literal("Use true or false for enabled."))
            return 0
        }
        val region = RegionsConfig.updateRegion(regionId) {
            it.requirePlayerInRange = enabled
            if (range != null) it.playerActivationRange = range.toDouble()
        } ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        source.sendMessage(Text.literal(
            "Â§a[CSR] Â§fPlayer activation for Â§e${region.regionName}Â§f: ${flag(region.requirePlayerInRange)} Â§7range Â§f${region.playerActivationRange.toInt()}"
        ))
        return 1
    }

    private fun setForceChunks(
        source: ServerCommandSource,
        regionId: String,
        enabledRaw: String,
        maxChunks: Int?,
        radius: Int?
    ): Int {
        val enabled = parseBoolean(enabledRaw) ?: run {
            source.sendError(Text.literal("Use true or false for enabled."))
            return 0
        }
        val oldRegion = RegionsConfig.getRegion(regionId) ?: run {
            source.sendError(Text.literal("No region found with id '$regionId'."))
            return 0
        }
        val world = worldForRegion(source, oldRegion) ?: return 0
        CobbleSpawnRegions.setRegionChunkTickets(world, oldRegion, enabled = false, respectCap = false)

        val region = RegionsConfig.updateRegion(regionId) {
            it.forceChunkLoading = enabled
            if (maxChunks != null) it.maxForceLoadedChunks = maxChunks
            if (radius != null) it.chunkLoadRadius = radius
        } ?: return 0
        val changed = if (enabled) {
            CobbleSpawnRegions.setRegionChunkTickets(world, region, enabled = true, respectCap = true)
        } else {
            0
        }
        source.sendMessage(Text.literal(
            "Â§a[CSR] Â§fForce chunks for Â§e${region.regionName}Â§f: ${flag(region.forceChunkLoading)} " +
                    "Â§7cap Â§f${region.maxForceLoadedChunks} Â§7radius Â§f${region.chunkLoadRadius} Â§7tickets Â§f$changed"
        ))
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

    private fun worldForRegion(source: ServerCommandSource, region: RegionData): ServerWorld? {
        val id = Identifier.tryParse(region.dimension) ?: run {
            source.sendError(Text.literal("Invalid dimension '${region.dimension}' for region '${region.regionId}'."))
            return null
        }
        val key = RegistryKey.of(RegistryKeys.WORLD, id)
        return source.server.getWorld(key) ?: run {
            source.sendError(Text.literal("Dimension '${region.dimension}' is not loaded."))
            null
        }
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

    private fun pokemonCommandArgs(
        handler: (ServerCommandSource, String, String, String?, Set<String>) -> Int
    ): RequiredArgumentBuilder<ServerCommandSource, String> {
        val region = regionIdArg()
        val pokemon = RequiredArgumentBuilder.argument<ServerCommandSource, String>("pokemon", StringArgumentType.word())
            .executes { ctx ->
                handler(
                    ctx.source,
                    StringArgumentType.getString(ctx, "regionId"),
                    StringArgumentType.getString(ctx, "pokemon"),
                    null,
                    emptySet()
                )
            }
            .then(RequiredArgumentBuilder.argument<ServerCommandSource, String>("form", StringArgumentType.word())
                .executes { ctx ->
                    handler(
                        ctx.source,
                        StringArgumentType.getString(ctx, "regionId"),
                        StringArgumentType.getString(ctx, "pokemon"),
                        StringArgumentType.getString(ctx, "form"),
                        emptySet()
                    )
                }
                .then(RequiredArgumentBuilder.argument<ServerCommandSource, String>("aspects", StringArgumentType.greedyString())
                    .executes { ctx ->
                        handler(
                            ctx.source,
                            StringArgumentType.getString(ctx, "regionId"),
                            StringArgumentType.getString(ctx, "pokemon"),
                            StringArgumentType.getString(ctx, "form"),
                            parseAspects(StringArgumentType.getString(ctx, "aspects"))
                        )
                    }
                )
            )
        return region.then(pokemon)
    }

    private fun regionIdArg() =
        RequiredArgumentBuilder.argument<ServerCommandSource, String>("regionId", StringArgumentType.word())
            .suggests { _, builder ->
                RegionsConfig.regions.keys.forEach { builder.suggest(it) }
                builder.buildFuture()
            }!!

    private fun selectionBounds(
        player: ServerPlayerEntity,
        source: ServerCommandSource
    ): Pair<SerializableBlockPos, SerializableBlockPos>? {
        val sel = CobbleSpawnRegions.playerSelections[player.uuid]
        if (sel == null || !sel.isBothSet) {
            player.sendMessage(Text.literal(
                "Â§c[CSR] Â§fUse a Â§6Coords Â§for Â§bChunk Â§fclaim stick and select both points first."
            ), false)
            return null
        }

        val world = player.serverWorld
        return when (sel.mode) {
            StickMode.CHUNK -> {
                val c1 = sel.chunkPos1!!
                val c2 = sel.chunkPos2!!
                SerializableBlockPos(minOf(c1.startX, c2.startX), world.bottomY, minOf(c1.startZ, c2.startZ)) to
                        SerializableBlockPos(maxOf(c1.endX, c2.endX), world.topY, maxOf(c1.endZ, c2.endZ))
            }
            else -> SerializableBlockPos.fromBlockPos(sel.pos1!!) to SerializableBlockPos.fromBlockPos(sel.pos2!!)
        }
    }

    private fun copyPokemonEntry(entry: PokemonSpawnEntry): PokemonSpawnEntry =
        entry.copy(
            aspects = entry.aspects.toSet(),
            sizeSettings = entry.sizeSettings.copy(),
            captureSettings = entry.captureSettings.copy(requiredPokeBalls = entry.captureSettings.requiredPokeBalls.toList()),
            ivSettings = entry.ivSettings.copy(),
            evSettings = entry.evSettings.copy(),
            spawnSettings = entry.spawnSettings.copy(allowedBlocks = entry.spawnSettings.allowedBlocks.toList()),
            wanderingSettings = entry.wanderingSettings.copy(),
            heldItemsOnSpawn = entry.heldItemsOnSpawn.copy(itemsWithChance = entry.heldItemsOnSpawn.itemsWithChance.toMap()),
            moves = entry.moves?.copy(selectedMoves = entry.moves?.selectedMoves?.map { it.copy() }.orEmpty())
        )

    private fun parseAspects(raw: String): Set<String> =
        raw.split(',', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun parseBoolean(raw: String): Boolean? =
        when (raw.lowercase()) {
            "true", "on", "yes" -> true
            "false", "off", "no" -> false
            else -> null
        }

    private fun describePokemon(pokemonName: String, formName: String?, aspects: Set<String>): String {
        val parts = mutableListOf<String>()
        if (!formName.isNullOrBlank()) parts.add(formName)
        parts.addAll(aspects)
        return if (parts.isEmpty()) pokemonName else "$pokemonName (${parts.joinToString(", ")})"
    }

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
