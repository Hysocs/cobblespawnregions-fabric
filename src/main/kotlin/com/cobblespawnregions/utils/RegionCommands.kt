package com.cobblespawnregions.utils

import com.cobblespawnregions.CobbleSpawnRegions
import com.cobblespawnregions.StickMode
import com.cobblespawnregions.gui.RegionListGui
import com.cobblespawnregions.gui.RegionEditorGui
import com.cobblespawnregions.gui.RegionSettingsGui
import com.everlastingutils.command.CommandManager
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
import net.minecraft.util.math.BlockPos

object RegionCommands {

    private val manager = CommandManager(CobbleSpawnRegions.MOD_ID, 2, 2)

    fun register() {
        manager.command("csr") {

            // ── /csr giveclaimstick <coords|chunk|subregion> ──────────────────
            subcommand("giveclaimstick") {
                then(
                    RequiredArgumentBuilder.argument<ServerCommandSource, String>("mode", StringArgumentType.word())
                        .suggests { _, builder ->
                            builder.suggest("coords")
                            builder.suggest("chunk")
                            builder.suggest("subregion")
                            builder.buildFuture()
                        }
                        .executes { ctx ->
                            val player = playerOrError(ctx.source) ?: return@executes 0
                            val mode = parseSticMode(StringArgumentType.getString(ctx, "mode"), ctx.source)
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
                            regions.values.forEach { r ->
                                ctx.source.sendMessage(Text.literal(
                                    "§7 - §e${r.regionName} §8(${r.regionId}) §7[${r.mode}] " +
                                            "§f(${r.pos1.x},${r.pos1.y},${r.pos1.z}) → (${r.pos2.x},${r.pos2.y},${r.pos2.z}) " +
                                            "§7| ${r.subRegions.size} sub(s) | ${r.dimension}"
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
                            CobbleSpawnRegions.activeVisualizations.values.removeIf { it == id }
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
                        if (CobbleSpawnRegions.activeVisualizations[player.uuid] == id) {
                            CobbleSpawnRegions.activeVisualizations.remove(player.uuid)
                            player.sendMessage(Text.literal("§a[CSR] §fStopped visualising §e${region.regionName}§f."), false)
                        } else {
                            CobbleSpawnRegions.activeVisualizations[player.uuid] = id
                            player.sendMessage(Text.literal(
                                "§a[CSR] §fVisualising §e${region.regionName} §f— " +
                                        "§b${region.subRegions.size} §fsub-region(s) shown in white."
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
            }

            // ── /csr subregion ... ────────────────────────────────────────────
            subcommand("subregion") {

                // /csr subregion create <parentId> <name>
                subcommand("create") {
                    then(regionIdArg()
                        .then(nameArg().executes { ctx ->
                            val player   = playerOrError(ctx.source) ?: return@executes 0
                            val parentId = StringArgumentType.getString(ctx, "regionId")
                            val name     = StringArgumentType.getString(ctx, "name")
                            createSubRegion(player, ctx.source, parentId, name)
                        })
                    )
                }

                // /csr subregion list <parentId>
                subcommand("list") {
                    then(regionIdArg().executes { ctx ->
                        val id   = StringArgumentType.getString(ctx, "regionId")
                        val subs = RegionsConfig.getSubRegions(id)
                        if (subs.isEmpty()) {
                            ctx.source.sendMessage(Text.literal("§a[CSR] §fNo sub-regions in §e$id§f."))
                        } else {
                            ctx.source.sendMessage(Text.literal("§a[CSR] §f${subs.size} sub-region(s) in §e$id§f:"))
                            subs.forEach { s ->
                                ctx.source.sendMessage(Text.literal(
                                    "§7 - §b${s.subRegionName} §8(${s.subRegionId}) " +
                                            "§f(${s.pos1.x},${s.pos1.y},${s.pos1.z}) → (${s.pos2.x},${s.pos2.y},${s.pos2.z})"
                                ))
                            }
                        }
                        1
                    })
                }

                // /csr subregion delete <parentId> <subId>
                subcommand("delete") {
                    then(regionIdArg()
                        .then(subIdArg().executes { ctx ->
                            val parentId = StringArgumentType.getString(ctx, "regionId")
                            val subId    = StringArgumentType.getString(ctx, "subRegionId")
                            if (RegionsConfig.removeSubRegion(parentId, subId)) {
                                ctx.source.sendMessage(Text.literal(
                                    "§a[CSR] §fDeleted sub-region §e$subId §ffrom §e$parentId§f."
                                ))
                                1
                            } else {
                                ctx.source.sendError(Text.literal("Sub-region '$subId' not found in '$parentId'.")); 0
                            }
                        })
                    )
                }

                // /csr subregion editgui <parentId> <subId>
                subcommand("editgui") {
                    then(regionIdArg()
                        .then(subIdArg().executes { ctx ->
                            val player   = playerOrError(ctx.source) ?: return@executes 0
                            val parentId = StringArgumentType.getString(ctx, "regionId")
                            val subId    = StringArgumentType.getString(ctx, "subRegionId")

                            val parent = RegionsConfig.getRegion(parentId) ?: run {
                                ctx.source.sendError(Text.literal("No region found with id '$parentId'.")); return@executes 0
                            }

                            val sub = parent.subRegions.find { it.subRegionId == subId } ?: run {
                                ctx.source.sendError(Text.literal("Sub-region '$subId' not found in '$parentId'.")); return@executes 0
                            }

                            RegionSettingsGui.open(player, parentId, subId)
                            player.sendMessage(Text.literal("§a[CSR] §fOpened settings for §b${sub.subRegionName} §fin §e${parent.regionName}§f."), false)
                            1
                        })
                    )
                }
            }

            // ── /csr check ────────────────────────────────────────────────────
            subcommand("check") {
                executes { ctx ->
                    val player = playerOrError(ctx.source) ?: return@executes 0
                    val pos    = player.blockPos
                    val dim    = player.serverWorld.registryKey.value.toString()

                    val region = RegionsConfig.regions.values.firstOrNull { r ->
                        r.dimension == dim && run {
                            val p1 = r.pos1; val p2 = r.pos2
                            pos.x in minOf(p1.x, p2.x)..maxOf(p1.x, p2.x) &&
                                    pos.y in minOf(p1.y, p2.y)..maxOf(p1.y, p2.y) &&
                                    pos.z in minOf(p1.z, p2.z)..maxOf(p1.z, p2.z)
                        }
                    }

                    if (region == null) {
                        player.sendMessage(Text.literal("§a[CSR] §fYou are §cnot §finside any region."), false)
                        return@executes 1
                    }

                    val sub = region.subRegions.firstOrNull { s ->
                        val p1 = s.pos1; val p2 = s.pos2
                        pos.x in minOf(p1.x, p2.x)..maxOf(p1.x, p2.x) &&
                                pos.y in minOf(p1.y, p2.y)..maxOf(p1.y, p2.y) &&
                                pos.z in minOf(p1.z, p2.z)..maxOf(p1.z, p2.z)
                    }

                    val restr = sub?.spawnRestrictions ?: region.spawnRestrictions
                    player.sendMessage(Text.literal("§a[CSR] §fRegion: §e${region.regionName} §8(${region.regionId})"), false)
                    if (sub != null) player.sendMessage(Text.literal("§a[CSR] §f  Sub-region: §b${sub.subRegionName} §8(${sub.subRegionId})"), false)
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
            StickMode.CHUNK      -> "§bChunk Claim Stick"      to "Chunk Claim Stick"
            StickMode.SUB_REGION -> "§dSub-Region Claim Stick" to "Sub-Region Claim Stick"
            else                 -> "§6Coords Claim Stick"     to "Coords Claim Stick"
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

        if (sel == null || !sel.isBothSet || sel.mode == StickMode.SUB_REGION) {
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

    private fun createSubRegion(player: ServerPlayerEntity, source: ServerCommandSource, parentId: String, name: String): Int {
        val sel = CobbleSpawnRegions.playerSelections[player.uuid]

        if (sel == null || !sel.isBothSet || sel.mode != StickMode.SUB_REGION) {
            player.sendMessage(Text.literal(
                "§c[CSR] §fUse the §dSub-Region Claim Stick §fand select both corners first."
            ), false)
            return 0
        }

        val parent = RegionsConfig.getRegion(parentId) ?: run {
            player.sendMessage(Text.literal("§c[CSR] §fParent region §e$parentId §fdoes not exist."), false)
            return 0
        }

        val p1Block = sel.pos1!!
        val p2Block = sel.pos2!!
        val pMin = parent.pos1; val pMax = parent.pos2
        val rMinX = minOf(pMin.x, pMax.x); val rMaxX = maxOf(pMin.x, pMax.x)
        val rMinY = minOf(pMin.y, pMax.y); val rMaxY = maxOf(pMin.y, pMax.y)
        val rMinZ = minOf(pMin.z, pMax.z); val rMaxZ = maxOf(pMin.z, pMax.z)

        fun inParent(b: BlockPos) =
            b.x in rMinX..rMaxX && b.y in rMinY..rMaxY && b.z in rMinZ..rMaxZ

        if (!inParent(p1Block) || !inParent(p2Block)) {
            player.sendMessage(Text.literal(
                "§c[CSR] §fBoth corners must be inside §e${parent.regionName}§f."
            ), false)
            return 0
        }

        val subId = sanitize(name)
        if (parent.subRegions.any { it.subRegionId == subId }) {
            player.sendMessage(Text.literal(
                "§c[CSR] §fSub-region §e$subId §falready exists in §e$parentId§f."
            ), false)
            return 0
        }

        val p1 = SerializableBlockPos.fromBlockPos(p1Block)
        val p2 = SerializableBlockPos.fromBlockPos(p2Block)

        RegionsConfig.addSubRegion(parentId, SubRegionData(subRegionId = subId, subRegionName = name, pos1 = p1, pos2 = p2))
        CobbleSpawnRegions.playerSelections.remove(player.uuid)

        // Clear the parent's floors and rescan loaded chunks so the updated
        // region (now including the new sub-region) is reflected immediately.
        SpawnPointStore.clearRegion(parentId)
        SpawnPointScanner.enqueueLoadedChunks(parentId, RegionsConfig.getRegion(parentId)!!, source.server)

        player.sendMessage(Text.literal(
            "§a[CSR] §fSub-region §b$name §fadded to §e${parent.regionName}§f. " +
                    "§b(${p1.x},${p1.y},${p1.z}) §f→ §b(${p2.x},${p2.y},${p2.z})"
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

    private fun subIdArg() =
        RequiredArgumentBuilder.argument<ServerCommandSource, String>("subRegionId", StringArgumentType.word())
            .suggests { ctx, builder ->
                try {
                    val parentId = StringArgumentType.getString(ctx, "regionId")
                    RegionsConfig.getSubRegions(parentId).forEach { builder.suggest(it.subRegionId) }
                } catch (_: Exception) {}
                builder.buildFuture()
            }!!

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun parseSticMode(raw: String, source: ServerCommandSource): StickMode? =
        when (raw.lowercase()) {
            "coords"    -> StickMode.COORDS
            "chunk"     -> StickMode.CHUNK
            "subregion" -> StickMode.SUB_REGION
            else        -> { source.sendError(Text.literal("Unknown mode '$raw'. Use: coords, chunk, subregion.")); null }
        }

    private fun sanitize(name: String) = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")

    private fun playerOrError(source: ServerCommandSource): ServerPlayerEntity? {
        val p = source.player as? ServerPlayerEntity
        if (p == null) source.sendError(Text.literal("Only players can use this command."))
        return p
    }
}

private fun flag(b: Boolean) = if (b) "§atrue" else "§cfalse"