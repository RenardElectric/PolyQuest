package polycube.polyquest.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import polycube.polyquest.runtime.QuestRuntime;
import polycube.polyquest.signal.QuestSignal;

/// Diverts fake quest criteria and emits completed real advancements.
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void polyquest$interceptQuestCriterion(
            AdvancementHolder holder,
            String criterion,
            CallbackInfoReturnable<Boolean> callback
    ) {
        QuestRuntime.manager().ifPresent(manager -> {
            if (manager.interceptAdvancementCriterion(player, holder, criterion)) {
                callback.setReturnValue(true);
            }
        });
    }

    @Inject(method = "award", at = @At(value = "INVOKE", target = "Lnet/minecraft/advancements/AdvancementRewards;grant(Lnet/minecraft/server/level/ServerPlayer;)V", shift = At.Shift.AFTER))
    private void polyquest$afterCompletionRewards(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> callback) {
        QuestRuntime.ifPresent(manager -> manager.signal(new QuestSignal.Advancement(player, player.level().getServer().getTickCount(), holder)));
    }
}
