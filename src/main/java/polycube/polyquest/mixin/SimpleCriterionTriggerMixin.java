package polycube.polyquest.mixin;

import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import polycube.polyquest.runtime.QuestRuntime;

import java.util.function.Predicate;

/// Preserves the boundary of one vanilla trigger event while fake criteria are awarded.
@Mixin(SimpleCriterionTrigger.class)
abstract class SimpleCriterionTriggerMixin {
    @Inject(method = "trigger", at = @At("HEAD"))
    private void polyquest$beginCriterionBatch(
            ServerPlayer player,
            Predicate<?> matcher,
            CallbackInfo callback
    ) {
        QuestRuntime.ifPresent(manager -> manager.beginAdvancementCriterionBatch(player));
    }

    @Inject(method = "trigger", at = @At("RETURN"))
    private void polyquest$endCriterionBatch(
            ServerPlayer player,
            Predicate<?> matcher,
            CallbackInfo callback
    ) {
        QuestRuntime.ifPresent(manager -> manager.endAdvancementCriterionBatch(player));
    }
}
