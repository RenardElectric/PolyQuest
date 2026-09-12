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

/// Emits only after vanilla has awarded a criterion and the advancement is complete.
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("RETURN"))
    private void polyquest$afterAward(
            AdvancementHolder holder,
            String criterion,
            CallbackInfoReturnable<Boolean> callback) {
        if (!Boolean.TRUE.equals(callback.getReturnValue())) {
            return;
        }
        PlayerAdvancements self = (PlayerAdvancements) (Object) this;
        if (!self.getOrStartProgress(holder).isDone()) {
            return;
        }
        QuestRuntime.ifPresent(manager -> manager.signal(new QuestSignal.Advancement(
                player,
                player.level().getServer().getTickCount(),
                holder)));
    }
}
