package com.cobblespawnregions.utils

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

// ── Serializable position ─────────────────────────────────────────────────────

data class SerializableBlockPos(val x: Int = 0, val y: Int = 0, val z: Int = 0) {
    fun toBlockPos(): BlockPos = BlockPos(x, y, z)
    companion object {
        fun fromBlockPos(pos: BlockPos) = SerializableBlockPos(pos.x, pos.y, pos.z)
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

/**
 * Fine-grained claim nested inside a parent [RegionData].
 * Inherits dimension from the parent.
 * Has its own [spawnRestrictions] for per-sub-region control.
 */
data class SubRegionData(
    val subRegionId: String = "",
    var subRegionName: String = "unnamed_sub_region",
    val pos1: SerializableBlockPos = SerializableBlockPos(),
    val pos2: SerializableBlockPos = SerializableBlockPos(),
    var spawnRestrictions: RegionRestrictionConfig = RegionRestrictionConfig()
)

/**
 * Top-level region saved as its own file.
 * Contains [subRegions] for finer spawn control within the region.
 * Natural spawns are filtered using [spawnRestrictions] (or the matching sub-region's config).
 */
data class RegionData(
    override val version: String = "1.0.0",
    override val configId: String = "cobblespawnregions",
    val regionId: String = "",
    var regionName: String = "unnamed_region",
    val pos1: SerializableBlockPos = SerializableBlockPos(),
    val pos2: SerializableBlockPos = SerializableBlockPos(),
    val dimension: String = "minecraft:overworld",
    val mode: String = "COORDS",
    var spawnRestrictions: RegionRestrictionConfig = RegionRestrictionConfig(),
    val subRegions: MutableList<SubRegionData> = mutableListOf()
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
                        watcherSettings = WatcherSettings(
                            enabled = true,
                            autoSaveEnabled = true
                        )
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
        return true
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fileNameForId(id: String) = "regions/region_$id.jsonc"
    private fun updateDebugState() = LogDebug.setDebugEnabledForMod(MOD_ID, config.debugEnabled)

    private class SerializableBlockPosAdapter : TypeAdapter<SerializableBlockPos>() {
        override fun write(out: JsonWriter, value: SerializableBlockPos?) {
            if (value == null) {
                out.nullValue(); return
            }
            out.beginObject()
            out.name("x").value(value.x)
            out.name("y").value(value.y)
            out.name("z").value(value.z)
            out.endObject()
        }

        override fun read(reader: JsonReader): SerializableBlockPos {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull(); return SerializableBlockPos()
            }
            val map = mutableMapOf<String, Int>()
            reader.beginObject()
            while (reader.hasNext()) {
                map[reader.nextName()] = reader.nextInt()
            }
            reader.endObject()
            return SerializableBlockPos(
                x = map["x"] ?: map["field_11175"] ?: map["field_11176"] ?: 0,
                y = map["y"] ?: map["field_11174"] ?: map["field_11177"] ?: 0,
                z = map["z"] ?: map["field_11173"] ?: map["field_11178"] ?: 0
            )
        }
    }


// ── GUI helpers ───────────────────────────────────────────────────────────

    /**
     * Returns the live [RegionRestrictionConfig] for a region or one of its
     * sub-regions. GUI code calls this instead of digging through the data
     * classes directly, so the same GUI works for both scopes.
     */
    fun getRestriction(regionId: String, subRegionId: String? = null): RegionRestrictionConfig? {
        val region = getRegion(regionId) ?: return null
        return if (subRegionId != null)
            region.subRegions.find { it.subRegionId == subRegionId }?.spawnRestrictions
        else
            region.spawnRestrictions
    }

    /** Human-readable label used in GUI titles. */
    fun scopeLabel(regionId: String, subRegionId: String?): String {
        val region = getRegion(regionId)
        return if (subRegionId != null) {
            val sub = region?.subRegions?.find { it.subRegionId == subRegionId }
            sub?.subRegionName ?: subRegionId
        } else {
            region?.regionName ?: regionId
        }
    }
}