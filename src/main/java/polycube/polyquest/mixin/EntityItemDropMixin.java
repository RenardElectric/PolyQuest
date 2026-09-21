package polycube.polyquest.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import polycube.polyquest.integration.LootSignals;

/// Observes the final `spawnAtLocation` overload used by vanilla entity drops.
@Mixin(Entity.class)
abstract class EntityItemDropMixin {
    @Inject(method = "spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"))
    private void polyquest$captureDeathDrop(ServerLevel level, ItemStack itemStack, Vec3 offset, CallbackInfoReturnable<@Nullable ItemEntity> callback) {
        LootSignals.captureEntityLoot((Entity) (Object) this, itemStack);
    }
}
