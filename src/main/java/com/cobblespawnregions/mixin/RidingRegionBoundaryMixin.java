package com.cobblespawnregions.mixin;

import com.cobblemon.mod.common.api.riding.behaviour.RidingController;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblespawnregions.utils.RegionExclusionHelper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(RidingController.class)
public class RidingRegionBoundaryMixin {
    @Shadow @Final private PokemonEntity entity;

    @Unique private long cobblespawnregions$lastCheckedBlock = Long.MIN_VALUE;
    @Unique private String cobblespawnregions$lastCheckedDimension;

    @Inject(method = "tick", at = @At("HEAD"))
    private void cobblespawnregions$checkRidingRegionEntry(CallbackInfo ci) {
        if (entity.getWorld().isClient()
                || !(entity.getControllingPassenger() instanceof ServerPlayerEntity player)) {
            cobblespawnregions$resetBoundaryCache();
            return;
        }

        long block = entity.getBlockPos().asLong();
        String dimension = entity.getWorld().getRegistryKey().getValue().toString();
        if (block == cobblespawnregions$lastCheckedBlock
                && dimension.equals(cobblespawnregions$lastCheckedDimension)) {
            return;
        }

        cobblespawnregions$lastCheckedBlock = block;
        cobblespawnregions$lastCheckedDimension = dimension;
        if (!RegionExclusionHelper.shouldBlockRiding(entity.getPokemon(), entity.getBlockPos(), dimension)) return;

        player.stopRiding();
        player.sendMessage(
                Text.literal("Riding is disabled in this region.").formatted(Formatting.RED),
                true
        );
        cobblespawnregions$resetBoundaryCache();
    }

    @Unique
    private void cobblespawnregions$resetBoundaryCache() {
        cobblespawnregions$lastCheckedBlock = Long.MIN_VALUE;
        cobblespawnregions$lastCheckedDimension = null;
    }
}
