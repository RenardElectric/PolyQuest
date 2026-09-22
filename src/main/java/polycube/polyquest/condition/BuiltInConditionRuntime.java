package polycube.polyquest.condition;

import com.google.gson.JsonObject;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import polycube.polyquest.runtime.ConditionRuntime;
import polycube.polyquest.signal.QuestSignal;

import java.util.ArrayList;
import java.util.List;

/// Mutable implementations for the immutable definitions in {@link BuiltInConditions}.
final class BuiltInConditionRuntime {
    /// A base class for conditions that track a boolean completion state.
    private abstract static class BaseInstance<D extends ConditionApi.Definition> implements ConditionRuntime.Instance {
        protected final D definition;
        protected boolean completed;

        protected BaseInstance(D definition, ConditionRuntime.CreationContext ignored) {
            this.definition = definition;
        }

        @Override
        public D definition() {
            return definition;
        }

        @Override
        public boolean completed() {
            return completed;
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            return completed
                    ? ConditionRuntime.ClaimPreparation.readyPrep()
                    : ConditionRuntime.ClaimPreparation.blocked("Condition is not complete");
        }

        @Override
        public void reset() {
            completed = false;
        }
    }

    /// A base class for conditions that track a numeric count of progress toward completion.
    private abstract static class CounterInstance<D extends ConditionApi.Definition> extends BaseInstance<D> {
        protected int current;
        private final int target;

        protected CounterInstance(D definition, ConditionRuntime.CreationContext context, int target) {
            super(definition, context);
            if (target <= 0) {
                throw new IllegalArgumentException("Condition count must be positive");
            }
            this.target = target;
        }

        protected ConditionRuntime.Update increment(int amount) {
            if (completed || amount <= 0) {
                return ConditionRuntime.Update.NONE;
            }
            current = Math.min(target, current + amount);
            completed = current >= target;
            return ConditionRuntime.Update.changed(completed);
        }

        @Override
        public void reset() {
            super.reset();
            current = 0;
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("current", current);
            result.addProperty("target", target);
            return result;
        }
    }

    /// Completes from a fake advancement listener managed by the server-scoped criterion tracker.
    static final class AdvancementCriterionInstance extends BaseInstance<BuiltInConditions.AdvancementCriterion> {
        private final ConditionRuntime.CriterionRegistration registration;

        AdvancementCriterionInstance(BuiltInConditions.AdvancementCriterion definition, ConditionRuntime.CreationContext context) {
            super(definition, context);
            registration = context.register(definition.criterion());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (completed
                    || !(signal instanceof QuestSignal.CriteriaMatched matched)
                    || !matched.registrationIds().contains(registration.id())) {
                return ConditionRuntime.Update.NONE;
            }
            completed = true;
            registration.deactivate();
            return ConditionRuntime.Update.changed(true);
        }

        @Override
        public void reset() {
            super.reset();
            registration.activate();
        }

        @Override
        public void close() {
            registration.close();
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("trigger", String.valueOf(BuiltInRegistries.TRIGGER_TYPES.getKey(definition.criterion().trigger())));
            return result;
        }
    }

    /// A condition that completes when consuming a specific item from the player's inventory, optionally requiring a specific count.
    static final class ConsumeItemsInstance extends BaseInstance<BuiltInConditions.ConsumeItems> {
        ConsumeItemsInstance(BuiltInConditions.ConsumeItems definition, ConditionRuntime.CreationContext context) {
            super(definition, context);
            if (definition.count() <= 0) {
                throw new IllegalArgumentException("Item count must be positive");
            }
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return ConditionRuntime.Update.NONE;
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            ItemConsumption operation = new ItemConsumption(definition.item(), definition.count());
            return operation.revalidate(context)
                    ? ConditionRuntime.ClaimPreparation.readyPrep(List.of(operation))
                    : ConditionRuntime.ClaimPreparation.blocked("Requires " + definition.count() + " matching item(s)");
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("item", String.valueOf(definition.item().items().map(item -> item.stream().map(Holder::getRegisteredName).toList()).orElse(null)));
            result.addProperty("count", definition.count());
            return result;
        }
    }

    /// Counts supported loot events rather than item quantity; one source event advances at most once.
    static final class LootItemInstance extends CounterInstance<BuiltInConditions.LootItem> {
        LootItemInstance(BuiltInConditions.LootItem definition, ConditionRuntime.CreationContext context) {
            super(definition, context, definition.count());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.LootGenerated loot) || !matchesSource(loot)) {
                return ConditionRuntime.Update.NONE;
            }
            return loot.items().stream().anyMatch(definition.item())
                    ? increment(1)
                    : ConditionRuntime.Update.NONE;
        }

        private boolean matchesSource(QuestSignal.LootGenerated loot) {
            return definition.entity().map(predicate ->
                    loot.origin() == QuestSignal.LootOrigin.ENTITY
                            && predicate.matches(loot.player(), loot.sourceEntity()))
                    .orElse(true);
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("source", definition.entity().isPresent() ? "entity" : "any");
            return result;
        }
    }

    /// A condition that completes when the player obtains a specific advancement.
    static final class ObtainAdvancementInstance extends BaseInstance<BuiltInConditions.ObtainAdvancement> {
        ObtainAdvancementInstance(BuiltInConditions.ObtainAdvancement definition, ConditionRuntime.CreationContext context) {
            super(definition, context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.Advancement advancement) || completed) {
                return ConditionRuntime.Update.NONE;
            }
            if (!advancement.advancement().id().equals(definition.advancement())) {
                return ConditionRuntime.Update.NONE;
            }
            completed = true;
            return ConditionRuntime.Update.changed(true);
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("advancement", definition.advancement().toString());
            return result;
        }
    }

    /// A condition that completes when the player receives a specific explicit signal, optionally requiring a specific count.
    static final class ExplicitSignalInstance extends CounterInstance<BuiltInConditions.ExplicitSignal> {
        ExplicitSignalInstance(BuiltInConditions.ExplicitSignal definition, ConditionRuntime.CreationContext context) {
            super(definition, context, definition.count());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return signal instanceof QuestSignal.Explicit explicit
                    && explicit.signalId().equals(definition.signal())
                    ? increment(1)
                    : ConditionRuntime.Update.NONE;
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("signal", definition.signal().toString());
            return result;
        }
    }

    /// A claim operation that consumes a specific item from the player's inventory, optionally requiring a specific count.
    private record ItemConsumption(ItemPredicate predicate, int required) implements ConditionRuntime.ClaimOperation {
        @Override
        public String describe() {
            return "Consume " + required + " matching item(s)";
        }

        @Override
        public boolean revalidate(ConditionRuntime.ClaimContext context) {
            return matchingCount(context.player().getInventory()) >= required;
        }

        @Override
        public ConditionRuntime.CommitResult commit(ConditionRuntime.ClaimContext context) {
            Container inventory = context.player().getInventory();
            if (matchingCount(inventory) < required) {
                return ConditionRuntime.CommitResult.failure("Required items are no longer present");
            }

            List<ItemStack> removed = new ArrayList<>();
            int remaining = required;
            for (var stack : inventory) {
                if (remaining == 0) break;
                if (stack.isEmpty() || !predicate.test(stack)) continue;
                int amount = Math.min(remaining, stack.getCount());
                ItemStack taken = stack.copyWithCount(amount);
                stack.shrink(amount);
                removed.add(taken);
                remaining -= amount;
            }
            inventory.setChanged();

            return ConditionRuntime.CommitResult.success(() -> {
                ServerPlayer player = context.player();
                for (ItemStack stack : removed) {
                    ItemStack remainder = stack.copy();
                    player.getInventory().add(remainder);
                    if (!remainder.isEmpty()) {
                        player.drop(remainder, false, Prediction.SERVER_ONLY);
                    }
                }
            });
        }

        private int matchingCount(Container inventory) {
            int count = 0;
            for (var stack : inventory) {
                if (!stack.isEmpty() && predicate.test(stack)) {
                    count += stack.getCount();
                    if (count >= required) {
                        return count;
                    }
                }
            }
            return count;
        }
    }

    private BuiltInConditionRuntime() {}
}
