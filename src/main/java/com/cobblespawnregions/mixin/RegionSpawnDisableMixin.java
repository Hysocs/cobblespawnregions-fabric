package com.cobblespawnregions.mixin;

import com.cobblemon.mod.common.api.spawning.SpawnCause;
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner;
import com.cobblemon.mod.common.api.spawning.spawner.SpawningZoneInput;
import com.cobblespawnregions.utils.RegionExclusionHelper;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts Cobblemon's PlayerSpawner before it builds a spawn zone.
 * If the player is standing inside a region whose
 * spawnRestrictions.disableAll = true, we return null — the same signal
 * the vanilla spawner uses to skip the tick entirely.
 *
 * remap = false because getZoneInput is a Cobblemon method, not a vanilla one.
 */
@Mixin(value = PlayerSpawner.class, remap = false)
public class RegionSpawnDisableMixin {

    @Inject(method = "getZoneInput", at = @At("HEAD"), cancellable = true, remap = false)
    private void cancelSpawnZoneInDisabledRegion(SpawnCause cause, CallbackInfoReturnable<SpawningZoneInput> cir) {
        Entity entity = cause.getEntity();
        if (!(entity instanceof ServerPlayerEntity player)) return;

        World world = player.getWorld();
        String dimensionId = world.getRegistryKey().getValue().toString();
        BlockPos playerPos  = player.getBlockPos();

        if (RegionExclusionHelper.INSTANCE.isSpawnDisabledAt(playerPos, dimensionId)) {
            cir.setReturnValue(null);
        }
    }
}
