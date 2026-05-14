package com.cobblespawnregions.utils

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.everlastingutils.utils.logDebug
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.random.Random
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RegionSpawnHelper {

    private val logger = LoggerFactory.getLogger("RegionSpawnHelper")
    private const val MOD_ID = "cobblespawnregions"
    private val random: Random = Random.create()
    private val spawnAttemptLocks = ConcurrentHashMap<String, Any>()

    // ════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Picks an entry by weight and a floor position matching that entry's
     * [SpawnSettings.allowedBlocks], then spawns it.
     *
     * Before picking, two live-count caps are enforced:
     *  - [RegionData.maxTotalSpawns]           — total region-owned Pokémon tracked
     *                                            (0 = unlimited)
     *  - [PokemonSpawnEntry.maxSpawnCount]      — per-entry count tracked
     *                                            (0 = unlimited)
     *
     * Counts come from [RegionEntityTracker], which tracks UUIDs across chunk
     * unloads/reloads so hibernated entities are still counted against the cap.
     *
     * @return entities actually spawned (may be empty).
     */
    fun attemptSpawnInRegion(
        world: ServerWorld,
        regionId: String,
        amount: Int = 1,
        respectTimer: Boolean = true
    ): List<PokemonEntity> {
        val attemptLock = spawnAttemptLocks.computeIfAbsent(regionId) { Any() }
        return synchronized(attemptLock) {
        val region = RegionsConfig.getRegion(regionId) ?: return emptyList()

        if (region.dimension != world.registryKey.value.toString()) return emptyList()
        if (respectTimer && !isSpawnReady(regionId)) return emptyList()
        if (region.selectedPokemon.isEmpty()) return emptyList()

        // ── Region-wide cap (tracker-based, includes unloaded entities) ────────
        if (region.maxTotalSpawns > 0) {
            val liveTotal = RegionEntityTracker.countTotal(regionId)
            if (liveTotal >= region.maxTotalSpawns) {
                logDebug(
                    "Region '$regionId' at total cap ($liveTotal/${region.maxTotalSpawns}). Skipping.",
                    MOD_ID
                )
                markSpawnAttempted(regionId)
                return emptyList()
            }
        }

        // ── Per-entry cap filter + basic condition check ───────────────────────
        val eligible = region.selectedPokemon.filter { entry ->
            checkBasicSpawnConditions(world, entry) == null &&
                    isEntryUnderCap(world, region, entry)
        }

        if (eligible.isEmpty()) {
            markSpawnAttempted(regionId)
            return emptyList()
        }

        val spawned = mutableListOf<PokemonEntity>()
        repeat(amount) {
            // Re-check region cap each iteration
            if (region.maxTotalSpawns > 0 &&
                RegionEntityTracker.countTotal(regionId) >= region.maxTotalSpawns
            ) return@repeat

            val entry = selectPokemonByWeight(eligible) ?: return@repeat

            // Re-check per-entry cap for the chosen entry
            if (!isEntryUnderCap(world, region, entry)) return@repeat

            val pos = pickRandomSpawnPos(regionId, entry.spawnSettings.allowedBlocks) ?: run {
                logDebug(
                    "No position matching allowedBlocks=${entry.spawnSettings.allowedBlocks} " +
                            "controlled by region '$regionId' for '${entry.pokemonName}'.", MOD_ID
                )
                return@repeat
            }

            // Pass regionId so spawnPokemonAt can tag + track the entity
            val entity = spawnPokemonAt(world, pos, entry, regionId) ?: return@repeat
            spawned.add(entity)
        }

        markSpawnAttempted(regionId)
        if (spawned.isNotEmpty()) {
            logDebug("Spawned ${spawned.size} Pokémon in region '$regionId'.", MOD_ID)
        }
        spawned
        }
    }

    /**
     * Spawns a specific entry at a specific position (no timer check).
     * Applies all per-entry settings (IVs, size, moves, held items).
     *
     * If [regionId] is non-null, the spawned entity is:
     *  1. Tagged in [Pokemon.persistentData] with `csr_region` / `csr_entry_key`
     *  2. Registered in [RegionEntityTracker] so it counts against caps even
     *     while its chunk is unloaded.
     *
     * If the spawn position is 2+ blocks above any solid surface, flying-capable
     * species are put into Cobblemon's actual flying pathing state.
     */
    fun spawnPokemonAt(
        world: ServerWorld,
        spawnPos: BlockPos,
        entry: PokemonSpawnEntry,
        regionId: String? = null
    ): PokemonEntity? {

        // ── 1. Chunk check ────────────────────────────────────────────────────
        val chunk = world.getChunk(spawnPos.x shr 4, spawnPos.z shr 4, ChunkStatus.FULL, false)
        if (chunk == null) {
            logger.warn("[CSR-SPAWN] FAIL — chunk not loaded at $spawnPos")
            return null
        }
        logger.info("[CSR-SPAWN] Chunk loaded at cx=${spawnPos.x shr 4} cz=${spawnPos.z shr 4}")

        // ── 2. Species lookup ─────────────────────────────────────────────────
        val sanitized = entry.pokemonName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
        val species = PokemonSpecies.getByName(sanitized) ?: run {
            logger.warn("[CSR-SPAWN] FAIL — species '$sanitized' not found in registry")
            return null
        }
        logger.info("[CSR-SPAWN] Species resolved: ${species.name}")

        // ── 3. Position diagnostics ───────────────────────────────────────────
        val blockAtFeet  = world.getBlockState(spawnPos)
        val blockAbove   = world.getBlockState(spawnPos.up())
        val blockAbove2  = world.getBlockState(spawnPos.up(2))
        val blockBelow   = world.getBlockState(spawnPos.down())
        logger.info("[CSR-SPAWN] Position $spawnPos diagnostics:")
        logger.info("  block below  (y-1): ${Registries.BLOCK.getId(blockBelow.block)}")
        logger.info("  block at feet (y+0): ${Registries.BLOCK.getId(blockAtFeet.block)}")
        logger.info("  block above  (y+1): ${Registries.BLOCK.getId(blockAbove.block)}")
        logger.info("  block above  (y+2): ${Registries.BLOCK.getId(blockAbove2.block)}")
        logger.info("  isAir@feet=${blockAtFeet.isAir}  isAir@+1=${blockAbove.isAir}  isAir@+2=${blockAbove2.isAir}")

        if (!blockAtFeet.isAir && !blockAtFeet.isOf(net.minecraft.block.Blocks.WATER)) {
            logger.warn("[CSR-SPAWN] WARNING — block at feet ($spawnPos) is not air/water: ${Registries.BLOCK.getId(blockAtFeet.block)}")
        }

        // ── 4. World border check ─────────────────────────────────────────────
        val border = world.worldBorder
        val inBorder = border.contains(spawnPos.x.toDouble(), spawnPos.z.toDouble())
        logger.info("[CSR-SPAWN] Inside world border: $inBorder (border size=${border.size})")
        if (!inBorder) {
            logger.warn("[CSR-SPAWN] FAIL — position $spawnPos is outside the world border")
            return null
        }

        // ── 5. Build entity ───────────────────────────────────────────────────
        val level   = entry.minLevel + random.nextInt(entry.maxLevel - entry.minLevel + 1)
        val isShiny = entry.aspects.any { it.equals("shiny", ignoreCase = true) }
        val propsString = buildPropertiesString(sanitized, level, isShiny, entry, species)
        logger.info("[CSR-SPAWN] Properties string: $propsString")

        val properties = PokemonProperties.parse(propsString)
        val entity     = properties.createEntity(world)
        val pokemon    = entity.pokemon

        pokemon.level = level
        pokemon.shiny = isShiny
        applyCustomMoves(pokemon, entry, level)
        applyCustomIVs(pokemon, entry)
        applyCustomSize(pokemon, entry)
        applyHeldItems(pokemon, entry)

        val entryKey = if (regionId != null) RegionEntityTracker.entryKey(entry) else null
        val spawnId = if (regionId != null) UUID.randomUUID().toString() else null
        if (regionId != null && entryKey != null && spawnId != null) {
            pokemon.persistentData.putString(RegionEntityTracker.REGION_KEY, regionId)
            pokemon.persistentData.putString(RegionEntityTracker.ENTRY_KEY, entryKey)
            pokemon.persistentData.putString(RegionEntityTracker.SPAWN_ID_KEY, spawnId)
            pokemon.persistentData.putLong(RegionEntityTracker.SPAWNED_AT_MS_KEY, System.currentTimeMillis())
            pokemon.persistentData.putLong(RegionEntityTracker.SPAWNED_AT_WORLD_TIME_KEY, world.time)
            pokemon.persistentData.putString(RegionEntityTracker.DIMENSION_KEY, world.registryKey.value.toString())
        }

        val spawnX = spawnPos.x + 0.5
        val spawnY = spawnPos.y.toDouble()
        val spawnZ = spawnPos.z + 0.5
        entity.refreshPositionAndAngles(spawnX, spawnY, spawnZ, entity.yaw, entity.pitch)

        // ── 6. Bounding-box collision check ──────────────────────────────────
        val bb = entity.boundingBox
        logger.info("[CSR-SPAWN] Entity bounding box: minX=%.2f minY=%.2f minZ=%.2f maxX=%.2f maxY=%.2f maxZ=%.2f"
            .format(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ))

        val collisions = world.getBlockCollisions(entity, bb).toList()
        if (collisions.isNotEmpty()) {
            logger.warn("[CSR-SPAWN] WARNING — ${collisions.size} block collision(s) inside entity BB:")
            collisions.take(5).forEach { logger.warn("  collision shape: $it") }
        } else {
            logger.info("[CSR-SPAWN] No block collisions inside bounding box — clear to spawn")
        }

        // ── 7. spawnEntity call ───────────────────────────────────────────────
        logger.info("[CSR-SPAWN] Calling world.spawnEntity for '${pokemon.species.name}' lv$level at ($spawnX, $spawnY, $spawnZ)")
        return if (world.spawnEntity(entity)) {
            logger.info("[CSR-SPAWN] SUCCESS — spawned '${pokemon.species.name}' lv$level @ $spawnPos")

            // ── 8. Track if this is a region-managed spawn ───────────────────
            if (regionId != null && entryKey != null && spawnId != null) {
                RegionEntityTracker.track(regionId, entryKey, spawnId, entity.uuid)
                RegionWanderingGoalManager.attachIfConfigured(entity)
                logger.info("[CSR-SPAWN] Tagged & tracked UUID=${entity.uuid} region=$regionId entry=$entryKey")
            }

            // ── 9. Flying setup if spawned 2+ blocks above solid ground ──────
            // Cobblemon's pathing checks PokemonEntity.isFlying(), which is backed
            // by the FLYING behaviour flag. The pose alone is only visual.
            if (isFlyingPosition(world, spawnPos) && entity.canFly()) {
                entity.setFlying(true)
                logger.info(
                    "[CSR-SPAWN] Flying spawn — Cobblemon flying flag set " +
                            "for '${pokemon.species.name}' @ $spawnPos"
                )
            }

            entity
        } else {
            logger.warn("[CSR-SPAWN] FAIL — world.spawnEntity returned false for '${pokemon.species.name}' @ $spawnPos")
            logger.warn("[CSR-SPAWN] Entity type: ${entity.type}  UUID: ${entity.uuid}  removed=${entity.isRemoved}")
            null
        }
    }

    /**
     * Pick a random scanned floor from [SpawnPointStore] whose floor block matches
     * one of [allowedBlocks]. If the list is empty, any floor is accepted.
     */
    fun pickRandomSpawnPos(regionId: String, allowedBlocks: List<String>): BlockPos? {
        val region = RegionsConfig.getRegion(regionId) ?: return null
        if (SpawnPointStore.isEmpty(regionId)) return null

        if (allowedBlocks.isEmpty()) {
            val all = mutableListOf<BlockPos>()
            SpawnPointStore.forEach(regionId) { pos, _, _ ->
                if (RegionsConfig.isControllingRegion(regionId, pos, region.dimension)) all.add(pos)
            }
            return if (all.isEmpty()) null else all[random.nextInt(all.size)]
        }

        val wantSolid  = "#solid" in allowedBlocks
        val wantWater  = "#water" in allowedBlocks
        val wantAir    = "#air"   in allowedBlocks
        val literalIds = allowedBlocks
            .filter { !it.startsWith("#") }
            .map    { it.lowercase() }.toSet()

        val matches = mutableListOf<BlockPos>()
        SpawnPointStore.forEach(regionId) { pos, block, type ->
            if (!RegionsConfig.isControllingRegion(regionId, pos, region.dimension)) return@forEach

            val id         = Registries.BLOCK.getId(block).toString().lowercase()
            val isAirSpawn = type == SpawnType.AIR
            val isWater    = type == SpawnType.WATER
            val isSolid    = type == SpawnType.SOLID

            val passes = id in literalIds
                    || (wantAir   && isAirSpawn)
                    || (wantWater && isWater)
                    || (wantSolid && isSolid)

            if (passes) matches.add(pos)
        }
        if (matches.isEmpty()) return null
        return matches[random.nextInt(matches.size)]
    }

    /**
     * - INDEPENDENT entries each roll their own chance; if any pass, one is returned.
     * - If none pass, a weighted pick is made across COMPETITIVE entries.
     */
    fun selectPokemonByWeight(eligible: List<PokemonSpawnEntry>): PokemonSpawnEntry? {
        if (eligible.isEmpty()) return null

        val kotlinRandom = object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int): Int = random.nextInt() and ((1 shl bitCount) - 1)
            override fun nextDouble(): Double = random.nextDouble()
        }

        val independent = eligible.filter { it.spawnChanceType == SpawnChanceType.INDEPENDENT }
        val competitive = eligible.filter { it.spawnChanceType == SpawnChanceType.COMPETITIVE }

        val independentHits = independent.filter { random.nextDouble() * 100.0 <= it.spawnChance }
        if (independentHits.isNotEmpty()) return independentHits.random(kotlinRandom)

        val totalCompetitive = competitive.sumOf { it.spawnChance }
        if (totalCompetitive <= 0.0) return null

        val roll = kotlinRandom.nextDouble() * totalCompetitive
        var cumulative = 0.0
        for (entry in competitive) {
            cumulative += entry.spawnChance
            if (roll <= cumulative) return entry
        }
        return null
    }

    /** null = conditions pass; non-null string = blocked reason. */
    fun checkBasicSpawnConditions(world: ServerWorld, entry: PokemonSpawnEntry): String? {
        val timeOfDay = world.timeOfDay % 24000
        when (entry.spawnSettings.spawnTime.uppercase()) {
            "DAY"   -> if (timeOfDay !in 0..12000) return "Not daytime"
            "NIGHT" -> if (timeOfDay in 0..12000) return "Not nighttime"
            "ALL"   -> {}
            else    -> logger.warn("Invalid spawn time '${entry.spawnSettings.spawnTime}' for ${entry.pokemonName}")
        }
        when (entry.spawnSettings.spawnWeather.uppercase()) {
            "CLEAR"   -> if (world.isRaining) return "Not clear weather"
            "RAIN"    -> if (!world.isRaining || world.isThundering) return "Not raining"
            "THUNDER" -> if (!world.isThundering) return "Not thundering"
            "ALL"     -> {}
            else      -> logger.warn("Invalid weather '${entry.spawnSettings.spawnWeather}' for ${entry.pokemonName}")
        }
        return null
    }

    // ── Timer helpers ─────────────────────────────────────────────────────────

    fun isSpawnReady(regionId: String): Boolean {
        val region = RegionsConfig.getRegion(regionId) ?: return false
        val last = RegionsConfig.lastSpawnTicks[regionId] ?: return true
        return System.currentTimeMillis() >= last + region.spawnTimerTicks * 50L
    }

    fun markSpawnAttempted(regionId: String) {
        RegionsConfig.lastSpawnTicks[regionId] = System.currentTimeMillis()
    }

    fun resetSpawnTimer(regionId: String) {
        RegionsConfig.lastSpawnTicks.remove(regionId)
    }

    // ════════════════════════════════════════════════════════════════════════
    // CAP HELPERS  (tracker-based — counts unloaded entities too)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns true when [entry] is still below its [PokemonSpawnEntry.maxSpawnCount]
     * cap, or has no cap set. Uses [RegionEntityTracker] so hibernated (unloaded)
     * entities are counted.
     */
    fun isEntryUnderCap(regionId: String, entry: PokemonSpawnEntry): Boolean {
        if (entry.maxSpawnCount <= 0) return true
        val count = RegionEntityTracker.countForEntry(regionId, RegionEntityTracker.entryKey(entry))
        return count < entry.maxSpawnCount
    }

    fun isEntryUnderCap(world: ServerWorld, region: RegionData, entry: PokemonSpawnEntry): Boolean {
        if (entry.maxSpawnCount <= 0) return true

        val entryKey = RegionEntityTracker.entryKey(entry)
        val trackedCount = RegionEntityTracker.countForEntry(region.regionId, entryKey)
        val loadedManagedCount = world.getEntitiesByClass(PokemonEntity::class.java, regionBoundingBox(region)) { entity ->
            val data = entity.pokemon.persistentData
            data.getString(RegionEntityTracker.REGION_KEY) == region.regionId &&
                    data.getString(RegionEntityTracker.ENTRY_KEY) == entryKey
        }.size

        return maxOf(trackedCount, loadedManagedCount) < entry.maxSpawnCount
    }

    // ════════════════════════════════════════════════════════════════════════
    // UTILITY HELPERS (kept for external / command use)
    // ════════════════════════════════════════════════════════════════════════

    /** Builds an AABB covering the full region volume (inclusive on both ends). */
    fun regionBoundingBox(region: RegionData): Box = Box(
        minOf(region.pos1.x, region.pos2.x).toDouble(),
        minOf(region.pos1.y, region.pos2.y).toDouble(),
        minOf(region.pos1.z, region.pos2.z).toDouble(),
        maxOf(region.pos1.x, region.pos2.x).toDouble() + 1.0,
        maxOf(region.pos1.y, region.pos2.y).toDouble() + 1.0,
        maxOf(region.pos1.z, region.pos2.z).toDouble() + 1.0
    )

    /** Counts every [PokemonEntity] currently loaded inside [box]. */
    fun countPokemonInBox(world: ServerWorld, box: Box): Int =
        world.getEntitiesByClass(PokemonEntity::class.java, box) { true }.size

    /**
     * Counts loaded [PokemonEntity] instances matching [entry]'s species/form/aspects
     * inside [box]. Useful for debugging; cap enforcement now uses [RegionEntityTracker].
     */
    fun countMatchingInBox(world: ServerWorld, box: Box, entry: PokemonSpawnEntry): Int {
        val targetName    = entry.pokemonName.lowercase()
        val targetAspects = entry.aspects.map { it.lowercase() }.toSet()
        val targetForm    = entry.formName?.lowercase()

        return world.getEntitiesByClass(PokemonEntity::class.java, box) { entity ->
            val pokemon = entity.pokemon
            if (!pokemon.species.showdownId().equals(targetName, ignoreCase = true)) return@getEntitiesByClass false
            val liveAspects = pokemon.aspects.map { it.lowercase() }.toSet()
            if (liveAspects != targetAspects) return@getEntitiesByClass false
            if (!targetForm.isNullOrEmpty()
                && !targetForm.equals("normal", ignoreCase = true)
                && !targetForm.equals("default", ignoreCase = true)
            ) {
                val liveForm = pokemon.form.name.lowercase()
                if (!liveForm.equals(targetForm, ignoreCase = true)) return@getEntitiesByClass false
            }
            true
        }.size
    }

    // ════════════════════════════════════════════════════════════════════════
    // INTERNAL
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns true when [pos] is floating in air with a solid (non-air, non-water)
     * block at least 2 blocks below it, meaning the Pokémon should be displayed
     * in its flying animation rather than its standing one.
     *
     * Logic:
     *  - The block at [pos] itself and the block directly below (y-1) must both be air.
     *    If the Pokémon is standing on something, it is not flying.
     *  - Scanning downward from y-2 up to 32 blocks, the first non-air block found
     *    confirms there is solid ground far enough below to count as "airborne".
     *  - If no solid block is found within 32 blocks (e.g. above the void) we do
     *    not force the flying pose to avoid unintended edge cases.
     */
    private fun isFlyingPosition(world: ServerWorld, pos: BlockPos): Boolean {
        // Must be standing in air — if there's a block at feet or directly below, it's grounded
        if (!world.getBlockState(pos).isAir) return false
        if (!world.getBlockState(pos.down(1)).isAir) return false

        // Scan for the first solid block at least 2 blocks below the spawn pos
        for (depth in 2..32) {
            val state = world.getBlockState(pos.down(depth))
            if (!state.isAir) return true  // solid found at sufficient depth → flying
        }

        // Nothing solid within scan range (void?) — don't force flying pose
        return false
    }

    private fun buildPropertiesString(
        sanitizedName: String,
        level: Int,
        isShiny: Boolean,
        entry: PokemonSpawnEntry,
        species: com.cobblemon.mod.common.pokemon.Species
    ): String {
        val sb = StringBuilder(sanitizedName).append(" level=$level")

        entry.aspects.forEach { aspect ->
            if (aspect.contains("=")) sb.append(" ${aspect.lowercase()}")
            else sb.append(" aspect=${aspect.lowercase()}")
        }

        val formName = entry.formName
        if (!formName.isNullOrEmpty()
            && !formName.equals("normal", ignoreCase = true)
            && !formName.equals("default", ignoreCase = true)
        ) {
            val normalized = formName.lowercase().replace(Regex("[^a-z0-9]"), "")
            val matched = species.forms.find { form ->
                form.formOnlyShowdownId().lowercase().replace(Regex("[^a-z0-9]"), "") == normalized
            }
            if (matched != null) {
                if (matched.aspects.isNotEmpty()) {
                    matched.aspects.forEach { sb.append(" aspect=${it.lowercase()}") }
                } else {
                    sb.append(" form=${matched.formOnlyShowdownId()}")
                }
            } else {
                logger.warn("Form '$formName' not found for '${species.name}'. Using default form.")
            }
        }
        return sb.toString()
    }

    private fun applyCustomMoves(pokemon: Pokemon, entry: PokemonSpawnEntry, level: Int) {
        val moves = entry.moves ?: return
        if (!moves.allowCustomInitialMoves) return

        pokemon.moveSet.clear()
        val forced = moves.selectedMoves.filter { it.forced }.map { it.moveId }
        val selected = forced.toMutableList()
        val remaining = 4 - forced.size

        if (remaining > 0) {
            val grouped = moves.selectedMoves
                .filter { !it.forced && it.level <= level }
                .groupBy { it.level }
                .toSortedMap(compareByDescending { it })

            for ((_, atLevel) in grouped) {
                if (selected.size >= 4) break
                val take = minOf(4 - selected.size, atLevel.size)
                val pick = if (atLevel.firstOrNull()?.level == 1) atLevel.takeLast(take) else atLevel.take(take)
                selected.addAll(pick.map { it.moveId })
            }
        }

        if (selected.size > 4) selected.subList(4, selected.size).clear()

        selected.forEachIndexed { i, moveId ->
            val template = Moves.getByName(moveId.lowercase())
            if (template != null) pokemon.moveSet.setMove(i, template.create())
            else logger.warn("Invalid move '$moveId' for '${entry.pokemonName}'")
        }
    }

    private fun applyCustomIVs(pokemon: Pokemon, entry: PokemonSpawnEntry) {
        if (!entry.ivSettings.allowCustomIvs) return
        val iv = entry.ivSettings
        pokemon.setIV(Stats.HP,              random.nextBetween(iv.minIVHp,             iv.maxIVHp))
        pokemon.setIV(Stats.ATTACK,          random.nextBetween(iv.minIVAttack,         iv.maxIVAttack))
        pokemon.setIV(Stats.DEFENCE,         random.nextBetween(iv.minIVDefense,        iv.maxIVDefense))
        pokemon.setIV(Stats.SPECIAL_ATTACK,  random.nextBetween(iv.minIVSpecialAttack,  iv.maxIVSpecialAttack))
        pokemon.setIV(Stats.SPECIAL_DEFENCE, random.nextBetween(iv.minIVSpecialDefense, iv.maxIVSpecialDefense))
        pokemon.setIV(Stats.SPEED,           random.nextBetween(iv.minIVSpeed,          iv.maxIVSpeed))
    }

    private fun applyCustomSize(pokemon: Pokemon, entry: PokemonSpawnEntry) {
        if (!entry.sizeSettings.allowCustomSize) return
        val s = entry.sizeSettings
        pokemon.scaleModifier = random.nextFloat() * (s.maxSize - s.minSize) + s.minSize
    }

    private fun applyHeldItems(pokemon: Pokemon, entry: PokemonSpawnEntry) {
        val heldItems = entry.heldItemsOnSpawn
        if (!heldItems.allowHeldItemsOnSpawn) return
        heldItems.itemsWithChance.forEach { (itemName, chance) ->
            val id = Identifier.tryParse(itemName) ?: return@forEach
            val item = Registries.ITEM.get(id)
            if (item != Items.AIR && random.nextDouble() * 100.0 <= chance) {
                pokemon.swapHeldItem(ItemStack(item))
                return@forEach
            }
        }
    }
}
