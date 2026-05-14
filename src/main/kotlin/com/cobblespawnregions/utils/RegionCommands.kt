package com.cobblespawnregions.utils

import com.cobblespawnregions.CobbleSpawnRegions
import com.cobblespawnregions.StickMode
import com.cobblespawnregions.gui.RegionListGui
import com.cobblespawnregions.gui.RegionEditorGui
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

            // ── /csr giveclaimstick <coords|chunk> ────────────────────────────
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

            // ── /csr region ... ───────────────────────────────────────────────
            subcommand("region") {

                // /csr region create <name>
                subcommand("create") {
                    then(nameArg().executes { ctx ->
                        val player = playerOrError(ctx.source) ?: return@executes 0
                        createRegion(player, ctx.source, StringArgumentType.getString(ctx, "name"))
                    })
                }

                // /csr region list
                subcommand("list") {
                    executes { ctx ->
                        val regions = RegionsConfig.regions
                        if (regions.isEmpty()) {
                            ctx.source.sendMessage(Text.literal("§a[CSR] §fNo regions defined yet."))
                        } else {
                            ctx.source.sendMessage(Text.literal("§a[CSR] §f${regions.size} region(s):"))
                            RegionsConfig.regionsInPriorityOrder().forEach { r ->
                                ctx.source.sendMessage(Text.literal(
                                    "§7 - §e${r.regionName} §8(${r.regionId}) §7[${r.mode}] " +
                                            "§f(${r.pos1.x},${r.pos1.y},${r.pos1.z}) → (${r.pos2.x},${r.pos2.y},${r.pos2.z}) " +
                                            "§7| priority §f${r.priority} §7| ${r.dimension}"
                                ))
                            }
                        }
                        1
                    }
                }

                // /csr region delete <regionId>
                subcommand("delete") {
                    then(regionIdArg().executes { ctx ->
                        val id = StringArgumentType.getString(ctx, "regionId")
                        if (RegionsConfig.removeRegion(id)) {
                            CobbleSpawnRegions.activeVisualizations.entries.removeIf { entry ->
                                entry.value.remove(id)
                                entry.value.isEmpty()
                            }
                            // Evict cached spawn floors so memory isn't held for a deleted region.
                            SpawnPointStore.clearRegion(id)
                            ctx.source.sendMessage(Text.literal("§a[CSR] §fDeleted region §e$id§f."))
                            1
                        } else {
                            ctx.source.sendError(Text.literal("No region found with id '$id'.")); 0
                        }
                    })
                }

                // /csr region visualize <regionId>
                subcommand("visualize") {
                    then(regionIdArg().executes { ctx ->
                        val player = playerOrError(ctx.source) ?: return@executes 0
                        val id     = StringArgumentType.getString(ctx, "regionId")
                        val region = RegionsConfig.getRegion(id) ?: run {
                            ctx.source.sendError(Text.literal("No region found with id '$id'.")); return@executes 0
                        }
                        val visualizations = CobbleSpawnRegions.activeVisualizations
                            .computeIfAbsent(player.uuid) { ConcurrentHashMap.newKeySet() }
                        if (visualizations.remove(id)) {
                            if (visualizations.isEmpty()) {
                                CobbleSpawnRegions.activeVisualizations.remove(player.uuid)
                            }
                            player.sendMessage(Text.literal("§a[CSR] §fStopped visualising §e${region.regionName}§f."), false)
                        } else {
                            visualizations.add(id)
                            player.sendMessage(Text.literal(
                                "§a[CSR] §fVisualising §e${region.regionName} §7(priority ${region.priority})§f."
                            ), false)
                        }
                        1
                    })
                }

                // /csr region editgui <regionId>
                subcommand("editgui") {
                    then(regionIdArg().executes { ctx ->
                        val player = playerOrError(ctx.source) ?: return@executes 0
                        val id     = StringArgumentType.getString(ctx, "regionId")
                        val region = RegionsConfig.getRegion(id) ?: run {
                            ctx.source.sendError(Text.literal("No region found with id '$id'.")); return@executes 0
                        }
                        RegionEditorGui.open(player, id)
                        player.sendMessage(Text.literal("§a[CSR] §fOpened editor for §e${region.regionName}§f."), false)
                        1
                    })
                }

                // /csr region priority <regionId> <value>
                subcommand("priority") {
                    then(regionIdArg()
                        .then(RequiredArgumentBuilder.argument<ServerCommandSource, Int>("value", IntegerArgumentType.integer(-1000, 1000))
                            .executes { ctx ->
                                val id = StringArgumentType.getString(ctx, "regionId")
                                val value = IntegerArgumentType.getInteger(ctx, "value")
                                val region = RegionsConfig.updateRegion(id) { it.priority = value } ?: run {
                                    ctx.source.sendError(Text.literal("No region found with id '$id'.")); return@executes 0
                                }
                                ctx.source.sendMessage(Text.literal(
                                    "§a[CSR] §fSet §e${region.regionName} §fpriority to §b${region.priority}§f."
                                ))
                                1
                            }
                        )
                    )
                }
            }

            // ── /csr check ────────────────────────────────────────────────────
            subcommand("check") {
                executes { ctx ->
                    val player = playerOrError(ctx.source) ?: return@executes 0
                    val pos    = player.blockPos
                    val dim    = player.serverWorld.registryKey.value.toString()

                    val matches = RegionsConfig.regionsAt(pos, dim)
                    val region = matches.firstOrNull()

                    if (region == null) {
                        player.sendMessage(Text.literal("§a[CSR] §fYou are §cnot §finside any region."), false)
                        return@executes 1
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
                    1
                }
            }

            // ── /csr editgui ──────────────────────────────────────────────────
            subcommand("editgui") {
                executes { ctx ->
                    val player = playerOrError(ctx.source) ?: return@executes 0
                    RegionListGui.open(player)
                    1
                }
            }

            // ── /csr reload ───────────────────────────────────────────────────
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

    // ── Command implementations ───────────────────────────────────────────────

    private fun giveStick(player: ServerPlayerEntity, source: ServerCommandSource, mode: StickMode): Int {
        val stick = ItemStack(Items.STICK, 1)

        val (label, plainName) = when (mode) {
            StickMode.CHUNK -> "§bChunk Claim Stick"  to "Chunk Claim Stick"
            else            -> "§6Coords Claim Stick" to "Coords Claim Stick"
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
        val world     = player.serverWorld

        val (p1, p2) = when (sel.mode) {
            StickMode.CHUNK -> {
                val c1 = sel.chunkPos1!!; val c2 = sel.chunkPos2!!
                SerializableBlockPos(minOf(c1.startX, c2.startX), world.bottomY, minOf(c1.startZ, c2.startZ)) to
                        SerializableBlockPos(maxOf(c1.endX, c2.endX), world.topY, maxOf(c1.endZ, c2.endZ))
            }
            else -> SerializableBlockPos.fromBlockPos(sel.pos1!!) to
                    SerializableBlockPos.fromBlockPos(sel.pos2!!)
        }

        RegionsConfig.addRegion(
            RegionData(regionId = regionId, regionName = name, pos1 = p1, pos2 = p2,
                dimension = dimension, mode = sel.mode.name)
        )
        CobbleSpawnRegions.playerSelections.remove(player.uuid)

        // Scan any already-loaded chunks immediately; remaining chunks will be
        // picked up automatically by the CHUNK_LOAD event as they load.
        SpawnPointScanner.enqueueLoadedChunks(regionId, RegionsConfig.getRegion(regionId)!!, source.server)

        player.sendMessage(Text.literal(
            "§a[CSR] §fRegion §e$name §f[${sel.mode.name}] created. " +
                    "§b(${p1.x},${p1.y},${p1.z}) §f→ §b(${p2.x},${p2.y},${p2.z}) §7in $dimension"
        ), false)
        return 1
    }

    // ── Argument builders ─────────────────────────────────────────────────────

    private fun nameArg() =
        RequiredArgumentBuilder.argument<ServerCommandSource, String>("name", StringArgumentType.word())!!

    private fun regionIdArg() =
        RequiredArgumentBuilder.argument<ServerCommandSource, String>("regionId", StringArgumentType.word())
            .suggests { _, builder ->
                RegionsConfig.regions.keys.forEach { builder.suggest(it) }
                builder.buildFuture()
            }!!

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun parseStickMode(raw: String, source: ServerCommandSource): StickMode? =
        when (raw.lowercase()) {
            "coords"    -> StickMode.COORDS
            "chunk"     -> StickMode.CHUNK
            else        -> { source.sendError(Text.literal("Unknown mode '$raw'. Use: coords, chunk.")); null }
        }

    private fun sanitize(name: String) = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")

    private fun playerOrError(source: ServerCommandSource): ServerPlayerEntity? {
        val p = source.player
        if (p == null) source.sendError(Text.literal("Only players can use this command."))
        return p
    }
}

private fun flag(b: Boolean) = if (b) "§atrue" else "§cfalse"
