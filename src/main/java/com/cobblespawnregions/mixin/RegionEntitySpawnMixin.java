package com.cobblespawnregions.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblespawnregions.utils.RegionExclusionHelper;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts every entity spawn attempt on the server.
 * For PokemonEntity specifically, checks whether the spawn position falls
 * inside a region (or sub-region) and, if so, applies that region's
 * spawnRestrictions to decide whether to cancel the spawn.
 *
 * Sub-region restrictions override parent-region restrictions — the helper
 * returns the most specific matching config automatically.
 */
@Mixin(ServerWorld.class)
public class RegionEntitySpawnMixin {

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void filterPokemonSpawnByRegion(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof PokemonEntity pokemonEntity)) return;

        Pokemon pokemon = pokemonEntity.getPokemon();
        ServerWorld world = (ServerWorld) (Object) this;
        String dimensionId = world.getRegistryKey().getValue().toString();
        BlockPos spawnPos  = entity.getBlockPos();

        // ✅ SINGLE CALL — does bounds check internally, skips extraction if not in region
        if (RegionExclusionHelper.INSTANCE.shouldExcludePokemon(pokemon, spawnPos, dimensionId)) {
            cir.setReturnValue(false);
        }
    }
}