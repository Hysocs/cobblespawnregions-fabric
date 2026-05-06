package com.cobblespawnregions.utils

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import com.everlastingutils.config.ConfigData
import com.everlastingutils.config.ConfigManager
import com.everlastingutils.config.ConfigMetadata
import com.everlastingutils.config.WatcherSettings
import com.everlastingutils.utils.LogDebug
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.runBlocking
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// ── Serializable position ─────────────────────────────────────────────────────

data class SerializableBlockPos(val x: Int = 0, val y: Int = 0, val z: Int = 0) {
    fun toBlockPos(): BlockPos = BlockPos(x, y, z)
    companion object {
        fun fromBlockPos(pos: BlockPos) = SerializableBlockPos(pos.x, pos.y, pos.z)
    }
}

// ── Pokémon spawn-entry data classes ──────────────────────────────────────────

enum class SpawnChanceType { COMPETITIVE, INDEPENDENT }

data class LeveledMove(
    val level: Int,
    val moveId: String,
    val forced: Boolean = false
)

data class MovesSettings(
    val allowCustomInitialMoves: Boolean = false,
    val selectedMoves: List<LeveledMove> = emptyList()
) {
    val initialMoves: List<String> get() = selectedMoves.map { it.moveId }
    val initialMovesWithLevels: List<LeveledMove> get() = selectedMoves
}

data class CaptureSettings(
    var isCatchable: Boolean = true,
    var restrictCaptureToLimitedBalls: Boolean = false,
    var requiredPokeBalls: List<String> = listOf("poke_ball")
)

data class IVSettings(
    var allowCustomIvs: Boolean = false,
    var minIVHp: Int = 0, var maxIVHp: Int = 31,
    var minIVAttack: Int = 0, var maxIVAttack: Int = 31,
    var minIVDefense: Int = 0, var maxIVDefense: Int = 31,
    var minIVSpecialAttack: Int = 0, var maxIVSpecialAttack: Int = 31,
    var minIVSpecialDefense: Int = 0, var maxIVSpecialDefense: Int = 31,
    var minIVSpeed: Int = 0, var maxIVSpeed: Int = 31
)

data class EVSettings(
    var allowCustomEvsOnDefeat: Boolean = false,
    var evHp: Int = 0, var evAttack: Int = 0, var evDefense: Int = 0,
    var evSpecialAttack: Int = 0, var evSpecialDefense: Int = 0, var evSpeed: Int = 0
)

/**
 * Spawn-gate conditions for a single [PokemonSpawnEntry].
 *
 * - [spawnTime]    : "DAY", "NIGHT", or "ALL"
 * - [spawnWeather] : "CLEAR", "RAIN", "THUNDER", or "ALL"
 * - [allowedBlocks]: list of block identifiers (e.g. "minecraft:grass_block").
 *                    Matched against the floor block recorded by the scanner.
 *                    **Empty list means any block is allowed.**
 */
data class SpawnSettings(
    var spawnTime: String = "ALL",
    var spawnWeather: String = "ALL",
    var allowedBlocks: List<String> = emptyList()
)

data class SizeSettings(
    var allowCustomSize: Boolean = false,
    var minSize: Float = 1.0f,
    var maxSize: Float = 1.0f
)

data class HeldItemsOnSpawn(
    var allowHeldItemsOnSpawn: Boolean = false,
    var itemsWithChance: Map<String, Double> = mapOf(
        "minecraft:cobblestone" to 0.1,
        "cobblemon:pokeball" to 100.0
    )
)

data class PokemonSpawnEntry(
    val pokemonName: String,
    var formName: String? = null,
    var aspects: Set<String> = emptySet(),
    var spawnChance: Double,
    var spawnChanceType: SpawnChanceType = SpawnChanceType.COMPETITIVE,
    var minLevel: Int,
    var maxLevel: Int,
    var sizeSettings: SizeSettings = SizeSettings(),
    val captureSettings: CaptureSettings = CaptureSettings(),
    val ivSettings: IVSettings = IVSettings(),
    val evSettings: EVSettings = EVSettings(),
    val spawnSettings: SpawnSettings = SpawnSettings(),
    var heldItemsOnSpawn: HeldItemsOnSpawn = HeldItemsOnSpawn(),
    var moves: MovesSettings? = null,
    var maxSpawnCount: Int = 5,
)

// ── Region data classes ───────────────────────────────────────────────────────

data class SubRegionData(
    val subRegionId: String = "",
    var subRegionName: String = "unnamed_sub_region",
    val pos1: SerializableBlockPos = SerializableBlockPos(),
    val pos2: SerializableBlockPos = SerializableBlockPos(),
    var spawnRestrictions: RegionRestrictionConfig = RegionRestrictionConfig()
)

data class RegionData(
    override val version: String = "1.0.0",
    override val configId: String = "cobblespawnregions",
    val regionId: String = "",
    var regionName: String = "unnamed_region",
    val pos1: SerializableBlockPos = SerializableBlockPos(),
    val pos2: SerializableBlockPos = SerializableBlockPos(),
    val dimension: String = "minecraft:overworld",
    val mode: String = "COORDS",

    // Spawn control
    var spawnTimerTicks: Long = 200,
    var selectedPokemon: MutableList<PokemonSpawnEntry> = mutableListOf(),

    var spawnRestrictions: RegionRestrictionConfig = RegionRestrictionConfig(),
    val subRegions: MutableList<SubRegionData> = mutableListOf(),

    var maxTotalSpawns: Int = 20
) : ConfigData

data class RegionsMainConfig(
    override val version: String = "1.0.0",
    override val configId: String = "cobblespawnregions",
    var debugEnabled: Boolean = false
) : ConfigData

// ── Config manager ────────────────────────────────────────────────────────────

object RegionsConfig {

    private val logger = LoggerFactory.getLogger("RegionsConfig")
    private const val MOD_ID = "cobblespawnregions"
    private const val CURRENT_VERSION = "1.0.0"

    private val modConfigDir = File("config/cobblespawnregions")
    private val regionsDir = File(modConfigDir, "regions")

    private lateinit var configManager: ConfigManager<RegionsMainConfig>
    private var isInitialized = false

    private val regionFileMap = ConcurrentHashMap<String, String>()

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .registerTypeAdapter(SerializableBlockPos::class.java, SerializableBlockPosAdapter())
        .create()

    /** Per-region last spawn attempt timestamp (epoch ms). */
    val lastSpawnTicks: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    val config: RegionsMainConfig
        get() = if (::configManager.isInitialized) configManager.getCurrentConfig() else RegionsMainConfig()

    val regions: Map<String, RegionData>
        get() = regionFileMap.keys.mapNotNull { id ->
            val fn = regionFileMap[id] ?: return@mapNotNull null
            configManager.getSecondaryConfig<RegionData>(fn)?.let { id to it }
        }.toMap()

    // ── Init ──────────────────────────────────────────────────────────────────

    fun initializeAndLoad() {
        if (isInitialized) return
        LogDebug.init(MOD_ID, false)
        modConfigDir.mkdirs()
        regionsDir.mkdirs()

        configManager = ConfigManager(
            currentVersion = CURRENT_VERSION,
            defaultConfig = RegionsMainConfig(),
            configClass = RegionsMainConfig::class,
            configDir = Paths.get("config"),
            metadata = ConfigMetadata(
                headerComments = listOf("CobbleSpawnRegions Main Configuration"),
                watcherSettings = WatcherSettings(enabled = true, autoSaveEnabled = true)
            )
        )

        updateDebugState()
        runBlocking { loadRegionsFromDisk() }
        isInitialized = true
        logger.info("RegionsConfig initialized — ${regionFileMap.size} region(s) loaded.")
    }

    fun reloadBlocking() {
        runBlocking {
            if (::configManager.isInitialized) {
                configManager.reloadConfig()
                loadRegionsFromDisk()
            }
        }
        updateDebugState()
    }

    // ── Disk I/O ──────────────────────────────────────────────────────────────

    private suspend fun loadRegionsFromDisk() {
        regionFileMap.clear()
        val files = regionsDir.listFiles { _, n -> n.endsWith(".json") || n.endsWith(".jsonc") } ?: return
        files.forEach { file ->
            try {
                val relativeName = "regions/${file.name}"
                val reader = JsonReader(file.reader()).apply { isLenient = true }
                val raw: RegionData = gson.fromJson(reader, RegionData::class.java) ?: return@forEach
                regionFileMap[raw.regionId] = relativeName
                configManager.registerSecondaryConfig(
                    fileName = relativeName,
                    configClass = RegionData::class,
                    defaultConfig = raw,
                    fileMetadata = ConfigMetadata(
                        watcherSettings = WatcherSettings(enabled = true, autoSaveEnabled = true)
                    )
                )
                configManager.saveSecondaryConfig(relativeName, raw)
            } catch (e: Exception) {
                logger.error("Failed to load region file: ${file.name}", e)
            }
        }
    }

    fun saveRegion(regionId: String) {
        val fileName = regionFileMap[regionId] ?: return
        val data = getRegion(regionId) ?: return
        runBlocking { configManager.saveSecondaryConfig(fileName, data) }
    }

    // ── Region CRUD ───────────────────────────────────────────────────────────

    fun addRegion(data: RegionData): Boolean {
        if (regionFileMap.containsKey(data.regionId)) return false
        val fileName = fileNameForId(data.regionId)
        regionFileMap[data.regionId] = fileName
        runBlocking {
            configManager.registerSecondaryConfig(
                fileName = fileName,
                configClass = RegionData::class,
                defaultConfig = data,
                fileMetadata = ConfigMetadata(watcherSettings = WatcherSettings(enabled = true, autoSaveEnabled = true))
            )
            configManager.saveSecondaryConfig(fileName, data)
        }
        return true
    }

    fun getRegion(regionId: String): RegionData? {
        val fn = regionFileMap[regionId] ?: return null
        return configManager.getSecondaryConfig(fn)
    }

    fun removeRegion(regionId: String): Boolean {
        val fileName = regionFileMap.remove(regionId) ?: return false
        configManager.unregisterConfig(fileName)
        File(modConfigDir, fileName).takeIf { it.exists() }?.delete()
        lastSpawnTicks.remove(regionId)
        return true
    }

    fun updateRegion(regionId: String, update: (RegionData) -> Unit): RegionData? {
        val region = getRegion(regionId) ?: return null
        update(region)
        saveRegion(regionId)
        return region
    }

    // ── Sub-region CRUD ───────────────────────────────────────────────────────

    fun addSubRegion(regionId: String, sub: SubRegionData): Boolean {
        val region = getRegion(regionId) ?: return false
        if (region.subRegions.any { it.subRegionId == sub.subRegionId }) return false
        region.subRegions.add(sub)
        saveRegion(regionId)
        return true
    }

    fun removeSubRegion(regionId: String, subRegionId: String): Boolean {
        val region = getRegion(regionId) ?: return false
        val removed = region.subRegions.removeIf { it.subRegionId == subRegionId }
        if (removed) saveRegion(regionId)
        return removed
    }

    fun getSubRegions(regionId: String): List<SubRegionData> =
        getRegion(regionId)?.subRegions ?: emptyList()

    fun getSubRegion(regionId: String, subRegionId: String): SubRegionData? =
        getRegion(regionId)?.subRegions?.find { it.subRegionId == subRegionId }

    // ── Pokémon-entry CRUD (on regions) ───────────────────────────────────────

    fun addPokemonToRegion(regionId: String, entry: PokemonSpawnEntry): Boolean {
        var success = false
        updateRegion(regionId) { region ->
            val exists = region.selectedPokemon.any {
                it.pokemonName.equals(entry.pokemonName, ignoreCase = true) &&
                        (it.formName?.equals(entry.formName, ignoreCase = true) ?: (entry.formName == null)) &&
                        it.aspects.map { a -> a.lowercase() }.toSet() ==
                        entry.aspects.map { a -> a.lowercase() }.toSet()
            }
            if (!exists) {
                region.selectedPokemon.add(entry)
                success = true
            }
        }
        return success
    }

    fun removePokemonFromRegion(
        regionId: String,
        pokemonName: String,
        formName: String? = null,
        aspects: Set<String> = emptySet()
    ): Boolean {
        var success = false
        updateRegion(regionId) { region ->
            val removed = region.selectedPokemon.removeIf {
                it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                        (it.formName?.equals(formName, ignoreCase = true) ?: (formName == null)) &&
                        it.aspects.map { a -> a.lowercase() }.toSet() ==
                        aspects.map { a -> a.lowercase() }.toSet()
            }
            if (removed) success = true
        }
        return success
    }

    fun getPokemonFromRegion(
        regionId: String,
        pokemonName: String,
        formName: String? = null,
        aspects: Set<String> = emptySet()
    ): PokemonSpawnEntry? {
        val region = getRegion(regionId) ?: return null
        return region.selectedPokemon.find {
            it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                    (it.formName?.equals(formName, ignoreCase = true) ?: (formName == null)) &&
                    it.aspects.map { a -> a.lowercase() }.toSet() ==
                    aspects.map { a -> a.lowercase() }.toSet()
        }
    }

    fun updatePokemonInRegion(
        regionId: String,
        pokemonName: String,
        formName: String? = null,
        aspects: Set<String> = emptySet(),
        update: (PokemonSpawnEntry) -> Unit
    ): PokemonSpawnEntry? {
        var result: PokemonSpawnEntry? = null
        updateRegion(regionId) { region ->
            val entry = region.selectedPokemon.find {
                it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                        (it.formName?.equals(formName, ignoreCase = true) ?: (formName == null)) &&
                        it.aspects.map { a -> a.lowercase() }.toSet() ==
                        aspects.map { a -> a.lowercase() }.toSet()
            }
            if (entry != null) {
                update(entry)
                entry.sizeSettings.minSize = roundToOneDecimal(entry.sizeSettings.minSize)
                entry.sizeSettings.maxSize = roundToOneDecimal(entry.sizeSettings.maxSize)
                result = entry
            }
        }
        return result
    }

    fun createDefaultPokemonEntry(
        pokemonName: String,
        formName: String? = null,
        aspects: Set<String> = emptySet()
    ): PokemonSpawnEntry {
        val species = PokemonSpecies.getByName(pokemonName.lowercase())
            ?: throw IllegalArgumentException("Unknown Pokémon: $pokemonName")
        val defaultMoves = getDefaultInitialMoves(species)

        return PokemonSpawnEntry(
            pokemonName = pokemonName,
            formName = formName,
            aspects = aspects,
            spawnChance = if (aspects.any { it.equals("shiny", ignoreCase = true) }) 0.0122 else 50.0,
            spawnChanceType = SpawnChanceType.COMPETITIVE,
            minLevel = 1,
            maxLevel = 100,
            sizeSettings = SizeSettings(),
            captureSettings = CaptureSettings(),
            ivSettings = IVSettings(),
            evSettings = EVSettings(),
            spawnSettings = SpawnSettings(
                spawnTime = "ALL",
                spawnWeather = "ALL",
                allowedBlocks = listOf("#solid", "#water", "#air")
            ),
            heldItemsOnSpawn = HeldItemsOnSpawn(),
            moves = MovesSettings(
                allowCustomInitialMoves = false,
                selectedMoves = defaultMoves
            )
        )
    }

    fun getDefaultInitialMoves(species: Species): List<LeveledMove> {
        val list = mutableListOf<LeveledMove>()
        species.moves.levelUpMoves.forEach { (level, moves) ->
            if (level > 0) moves.forEach { list.add(LeveledMove(level, it.name)) }
        }
        list.sortBy { it.level }
        return list
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fileNameForId(id: String) = "regions/region_$id.jsonc"
    private fun updateDebugState() = LogDebug.setDebugEnabledForMod(MOD_ID, config.debugEnabled)
    private fun roundToOneDecimal(v: Float) = (v * 10).roundToInt() / 10f

    private class SerializableBlockPosAdapter : TypeAdapter<SerializableBlockPos>() {
        override fun write(out: JsonWriter, value: SerializableBlockPos?) {
            if (value == null) { out.nullValue(); return }
            out.beginObject()
            out.name("x").value(value.x)
            out.name("y").value(value.y)
            out.name("z").value(value.z)
            out.endObject()
        }

        override fun read(reader: JsonReader): SerializableBlockPos {
            if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return SerializableBlockPos() }
            val map = mutableMapOf<String, Int>()
            reader.beginObject()
            while (reader.hasNext()) map[reader.nextName()] = reader.nextInt()
            reader.endObject()
            return SerializableBlockPos(
                x = map["x"] ?: map["field_11175"] ?: map["field_11176"] ?: 0,
                y = map["y"] ?: map["field_11174"] ?: map["field_11177"] ?: 0,
                z = map["z"] ?: map["field_11173"] ?: map["field_11178"] ?: 0
            )
        }
    }

    // ── GUI helpers ───────────────────────────────────────────────────────────

    fun getRestriction(regionId: String, subRegionId: String? = null): RegionRestrictionConfig? {
        val region = getRegion(regionId) ?: return null
        return if (subRegionId != null)
            region.subRegions.find { it.subRegionId == subRegionId }?.spawnRestrictions
        else
            region.spawnRestrictions
    }

    fun scopeLabel(regionId: String, subRegionId: String?): String {
        val region = getRegion(regionId)
        return if (subRegionId != null) {
            region?.subRegions?.find { it.subRegionId == subRegionId }?.subRegionName ?: subRegionId
        } else {
            region?.regionName ?: regionId
        }
    }

    fun updateSubRegion(regionId: String, subRegionId: String, update: (SubRegionData) -> Unit): SubRegionData? {
        val region = getRegion(regionId) ?: return null
        val sub = region.subRegions.find { it.subRegionId == subRegionId } ?: return null
        update(sub)
        saveRegion(regionId)
        return sub
    }
}