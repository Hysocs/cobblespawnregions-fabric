package com.cobblespawnregions.utils

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokemon.stats.SidemodEvSource
import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor
import com.cobblemon.mod.common.pokemon.Pokemon
import com.everlastingutils.scheduling.SchedulerManager
import com.everlastingutils.utils.logDebug
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RegionBattleTracker {

    private enum class BattleEndCause {
        NORMAL_VICTORY,
        FLED,
        CAPTURED,
        UNKNOWN
    }

    private data class BattleInfo(
        val battleId: UUID,
        var actors: List<BattleActor>,
        val originalEVMap: ConcurrentHashMap<UUID, Map<Stat, Int>> = ConcurrentHashMap(),
        var isOpponentFromRegion: Boolean = false,
        var regionId: String? = null,
        var entryKey: String? = null,
        var currentActivePlayerPokemon: Pokemon? = null,
        var opponentPokemon: Pokemon? = null,
        var valuesApplied: Boolean = false,
        var endCause: BattleEndCause = BattleEndCause.UNKNOWN,
        val startTime: Long = System.currentTimeMillis()
    )

    private val ongoingBattles = ConcurrentHashMap<UUID, BattleInfo>()
    private val maxBattleDurationMs = 10 * 60 * 1000L

    fun registerEvents() {
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe { event ->
            ongoingBattles[event.battle.battleId] = BattleInfo(event.battle.battleId, emptyList())
        }
        CobblemonEvents.BATTLE_STARTED_POST.subscribe { event ->
            handleBattleStartPost(event.battle.battleId, event.battle.actors.toList())
        }
        CobblemonEvents.POKEMON_SENT_POST.subscribe { event ->
            handlePokemonSent(event.pokemon)
        }
        CobblemonEvents.BATTLE_VICTORY.subscribe { event ->
            finishBattle(event.battle.battleId, BattleEndCause.NORMAL_VICTORY, applyValues = true)
        }
        CobblemonEvents.BATTLE_FLED.subscribe { event ->
            finishBattle(event.battle.battleId, BattleEndCause.FLED, applyValues = false)
        }
        CobblemonEvents.POKEMON_CAPTURED.subscribe { event ->
            findBattleIdByPokemon(event.pokemon)?.let {
                finishBattle(it, BattleEndCause.CAPTURED, applyValues = false)
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            findBattleIdByPlayer(handler.player)?.let {
                finishBattle(it, BattleEndCause.UNKNOWN, applyValues = false)
            }
        }
    }

    fun startCleanupScheduler(server: MinecraftServer) {
        SchedulerManager.scheduleAtFixedRate(
            "cobblespawnregions-battle-cleanup",
            server,
            0,
            1000,
            TimeUnit.MILLISECONDS
        ) {
            cleanupStaleBattles(server)
        }
    }

    private fun handleBattleStartPost(battleId: UUID, actors: List<BattleActor>) {
        val battleInfo = ongoingBattles[battleId] ?: return
        battleInfo.actors = actors

        actors.forEach { actor ->
            when (actor) {
                is PlayerBattleActor -> handlePlayerActivePokemon(battleId, actor.pokemonList.firstOrNull()?.effectedPokemon)
                is PokemonBattleActor -> handleOpponentActivePokemon(battleId, actor.pokemonList.firstOrNull()?.effectedPokemon)
            }
        }
    }

    private fun handlePokemonSent(pokemon: Pokemon) {
        val battleId = findBattleIdByPokemon(pokemon) ?: return
        if (pokemon.entity?.owner is ServerPlayerEntity) {
            handlePlayerActivePokemon(battleId, pokemon)
        } else {
            handleOpponentActivePokemon(battleId, pokemon)
        }
    }

    private fun handlePlayerActivePokemon(battleId: UUID, pokemon: Pokemon?) {
        if (pokemon == null) return
        val battleInfo = ongoingBattles[battleId] ?: return
        synchronized(battleInfo) {
            battleInfo.currentActivePlayerPokemon = pokemon
            if (battleInfo.isOpponentFromRegion) saveOriginalEVs(battleInfo, pokemon)
        }
    }

    private fun handleOpponentActivePokemon(battleId: UUID, pokemon: Pokemon?) {
        if (pokemon == null) return
        val battleInfo = ongoingBattles[battleId] ?: return
        val entity = pokemon.entity ?: return
        val data = entity.pokemon.persistentData
        val regionId = data.getString(RegionEntityTracker.REGION_KEY)
        val entryKey = data.getString(RegionEntityTracker.ENTRY_KEY)
        if (regionId.isEmpty() || entryKey.isEmpty()) return

        val entry = resolveEntry(regionId, entryKey) ?: return
        if (!entry.evSettings.allowCustomEvsOnDefeat) return

        synchronized(battleInfo) {
            battleInfo.isOpponentFromRegion = true
            battleInfo.regionId = regionId
            battleInfo.entryKey = entryKey
            battleInfo.opponentPokemon = pokemon
            battleInfo.currentActivePlayerPokemon?.let { saveOriginalEVs(battleInfo, it) }
        }
    }

    private fun finishBattle(battleId: UUID, cause: BattleEndCause, applyValues: Boolean) {
        val battleInfo = ongoingBattles[battleId] ?: return
        synchronized(battleInfo) {
            battleInfo.endCause = cause
            if (applyValues) applyValuesAfterBattle(battleInfo)
        }
        ongoingBattles.remove(battleId)
    }

    private fun cleanupStaleBattles(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        val battlesToCleanup = mutableListOf<UUID>()

        ongoingBattles.forEach { (battleId, battleInfo) ->
            val battleEnded = battleInfo.actors.any { actor ->
                actor is PlayerBattleActor && actor.getPlayerUUIDs().any { uuid ->
                    server.playerManager.getPlayer(uuid)?.let { playerIsNotInBattle(it) } ?: true
                }
            }
            if (battleEnded || now - battleInfo.startTime > maxBattleDurationMs) {
                synchronized(battleInfo) {
                    battleInfo.endCause = BattleEndCause.UNKNOWN
                }
                battlesToCleanup.add(battleId)
            }
        }

        battlesToCleanup.forEach { ongoingBattles.remove(it) }
    }

    private fun applyValuesAfterBattle(battleInfo: BattleInfo) {
        if (!battleInfo.isOpponentFromRegion || battleInfo.valuesApplied) return
        if (battleInfo.endCause != BattleEndCause.NORMAL_VICTORY) return

        val playerPokemon = battleInfo.currentActivePlayerPokemon ?: return
        val regionId = battleInfo.regionId ?: return
        val entryKey = battleInfo.entryKey ?: return
        val entry = resolveEntry(regionId, entryKey) ?: return
        if (!entry.evSettings.allowCustomEvsOnDefeat) return

        revertEVsAfterChange(battleInfo, playerPokemon)
        applyCustomEVs(playerPokemon, entry)
        battleInfo.valuesApplied = true
    }

    private fun saveOriginalEVs(battleInfo: BattleInfo, pokemon: Pokemon) {
        battleInfo.originalEVMap[pokemon.uuid] = Stats.PERMANENT.associateWith { pokemon.evs.get(it) ?: 0 }
    }

    private fun revertEVsAfterChange(battleInfo: BattleInfo, pokemon: Pokemon) {
        battleInfo.originalEVMap[pokemon.uuid]?.forEach { (stat, ev) ->
            pokemon.evs.set(stat, ev)
        }
    }

    private fun applyCustomEVs(playerPokemon: Pokemon, entry: PokemonSpawnEntry) {
        val customEvs = mapOf(
            Stats.HP to entry.evSettings.evHp,
            Stats.ATTACK to entry.evSettings.evAttack,
            Stats.DEFENCE to entry.evSettings.evDefense,
            Stats.SPECIAL_ATTACK to entry.evSettings.evSpecialAttack,
            Stats.SPECIAL_DEFENCE to entry.evSettings.evSpecialDefense,
            Stats.SPEED to entry.evSettings.evSpeed
        )
        val evSource = SidemodEvSource("cobblespawnregions", playerPokemon)
        customEvs.forEach { (stat, ev) ->
            if (ev > 0) playerPokemon.evs.add(stat, ev, evSource)
        }
        logDebug("Applied custom EVs to ${playerPokemon.species.name}: $customEvs", "cobblespawnregions")
    }

    private fun resolveEntry(regionId: String, entryKey: String): PokemonSpawnEntry? {
        val region = RegionsConfig.getRegion(regionId) ?: return null
        return region.selectedPokemon.find { RegionEntityTracker.entryKey(it) == entryKey }
    }

    private fun findBattleIdByPokemon(pokemon: Pokemon): UUID? =
        ongoingBattles.values.find { battleInfo ->
            battleInfo.actors.any { actor ->
                actor.pokemonList.any { it.effectedPokemon.uuid == pokemon.uuid }
            }
        }?.battleId

    private fun findBattleIdByPlayer(player: ServerPlayerEntity): UUID? =
        ongoingBattles.values.find { battleInfo ->
            battleInfo.actors.any { actor ->
                actor is PlayerBattleActor && actor.getPlayerUUIDs().contains(player.uuid)
            }
        }?.battleId

    private fun playerIsNotInBattle(player: ServerPlayerEntity): Boolean = getActiveBattleAndActor(player) == null

    private fun getActiveBattleAndActor(player: ServerPlayerEntity): Pair<PokemonBattle, BattleActor>? {
        val battle = BattleRegistry.getBattleByParticipatingPlayer(player)
        val actor = battle?.getActor(player)
        return if (battle != null && actor != null) Pair(battle, actor) else null
    }
}
