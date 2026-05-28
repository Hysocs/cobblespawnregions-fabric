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








@Mixin(ServerWorld.class)
public class RegionEntitySpawnMixin {

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void filterPokemonSpawnByRegion(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof PokemonEntity pokemonEntity)) return;

        Pokemon pokemon = pokemonEntity.getPokemon();
        if (pokemon.getPersistentData().contains("csr_region")) return;

        ServerWorld world = (ServerWorld) (Object) this;
        String dimensionId = world.getRegistryKey().getValue().toString();
        BlockPos spawnPos  = entity.getBlockPos();


        if (RegionExclusionHelper.INSTANCE.shouldExcludePokemon(pokemon, spawnPos, dimensionId)) {
            cir.setReturnValue(false);
        }
    }
}
