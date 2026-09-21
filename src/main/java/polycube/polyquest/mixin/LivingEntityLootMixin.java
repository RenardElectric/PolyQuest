package polycube.polyquest.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import polycube.polyquest.integration.LootSignals;

/// Scopes item-drop capture around Minecraft's complete, polymorphic death-loot routine.
@Mixin(LivingEntity.class)
abstract class LivingEntityLootMixin {
    @Shadow
    protected abstract void dropAllDeathLoot(ServerLevel level, DamageSource source);

    @Redirect(method = "die", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;dropAllDeathLoot(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V"))
    private void polyquest$captureDeathLoot(LivingEntity entity, ServerLevel level, DamageSource source) {
        if (entity.getLastHurtByPlayerMemoryTime() <= 0 || !(entity.getLastHurtByPlayer() instanceof ServerPlayer player)) {
            dropAllDeathLoot(level, source);
            return;
        }

        LootSignals.beginEntityLoot(player, entity);
        boolean successful = false;
        try {
            dropAllDeathLoot(level, source);
            successful = true;
        } finally {
            LootSignals.finishEntityLoot(entity, successful);
        }
    }
}
