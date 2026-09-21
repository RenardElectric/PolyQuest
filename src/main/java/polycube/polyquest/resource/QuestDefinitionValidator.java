package polycube.polyquest.resource;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.storage.loot.ValidationContextSource;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.condition.CompositeConditions;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.BuiltInRewards;
import polycube.polyquest.reward.RewardApi;

import java.math.BigInteger;
import java.util.*;

/// Semantic validation performed after codec decoding and template expansion.
public final class QuestDefinitionValidator {
    private final Optional<HolderLookup.Provider> registries;

    public QuestDefinitionValidator() {
        this.registries = Optional.empty();
    }

    public QuestDefinitionValidator(HolderLookup.Provider registries) {
        this.registries = Optional.of(registries);
    }

    public List<String> validate(QuestModel.Definition quest, Map<Identifier, RewardApi.Profile> profiles) {
        List<String> errors = new ArrayList<>();
        String prefix = "Quest '" + quest.id() + "': ";

        if (quest.title().isBlank()) {
            errors.add(prefix + "title cannot be blank");
        }
        if (quest.availability() == QuestModel.Availability.DAILY && quest.difficulty().isEmpty()) {
            errors.add(prefix + "daily quests require a difficulty");
        }
        if (quest.availability() == QuestModel.Availability.UNIQUE && quest.difficulty().isPresent()) {
            errors.add(prefix + "unique quests must not specify a difficulty");
        }
        if (quest.rewards().profile().isPresent() && !profiles.containsKey(quest.rewards().profile().get())) {
            errors.add(prefix + "unknown reward profile '" + quest.rewards().profile().get() + "'");
        }
        if (quest.rewards().profile().isPresent() && !quest.rewards().inlineRewards().isEmpty()) {
            errors.add(prefix + "use either a reward profile or inline rewards, not both");
        }
        if (quest.rewards().profile().isEmpty() && quest.rewards().inlineRewards().isEmpty()) {
            errors.add(prefix + "at least one reward is required");
        }
        for (int index = 0; index < quest.rewards().inlineRewards().size(); index++) {
            validateReward(quest.rewards().inlineRewards().get(index), prefix + "rewards[" + index + "]", errors);
        }

        validateCondition(quest.condition(), prefix + "condition", errors);
        return errors;
    }

    public List<String> validate(RewardApi.Profile profile) {
        List<String> errors = new ArrayList<>();
        if (profile.rewards().isEmpty()) {
            errors.add("Reward profile '" + profile.id() + "' must contain at least one reward");
        }
        for (int index = 0; index < profile.rewards().size(); index++) {
            validateReward(profile.rewards().get(index), "Reward profile '" + profile.id() + "'.rewards[" + index + "]", errors);
        }
        return errors;
    }

    private void validateReward(RewardApi.Definition definition, String path, List<String> errors) {
        switch (definition) {
            case BuiltInRewards.Money(BigInteger amount, String _, Identifier _) when amount.signum() <= 0 -> errors.add(path + ": amount must be positive");
            case BuiltInRewards.Item(ItemStackTemplate stackTemplate) when stackTemplate.count() <= 0 -> errors.add(path + ": stack cannot be empty");
            case BuiltInRewards.Experience(int points) when points <= 0 -> errors.add(path + ": points must be positive");
            case BuiltInRewards.ServerCommands(String _, List<String> commands1) -> {
                if (commands1.isEmpty()) {
                    errors.add(path + ": at least one command is required");
                }
                for (int index = 0; index < commands1.size(); index++) {
                    if (commands1.get(index).isBlank()) {
                        errors.add(path + ".commands[" + index + "]: command cannot be blank");
                    }
                }
            }
            default -> {
            }
        }
    }

    /// Applies the same predicate validation Minecraft runs for advancement criteria.
    private void validateCriterion(
            BuiltInConditions.AdvancementCriterion definition,
            String path,
            List<String> errors
    ) {
        if (registries.isEmpty()) {
            return;
        }
        var problems = new ProblemReporter.Collector();
        definition.criterion().triggerInstance().validate(new ValidationContextSource(problems, registries.orElseThrow()));
        problems.forEach((childPath, problem) -> errors.add(path + childPath + ": " + problem.description()));
    }

    private void validateCondition(ConditionApi.Definition definition, String path, List<String> errors) {
        switch (definition) {
            case BuiltInConditions.AdvancementCriterion value -> validateCriterion(value, path, errors);
            case BuiltInConditions.ConsumeItems value when value.count() <= 0 -> errors.add(path + ": count must be positive");
            case BuiltInConditions.ExplicitSignal value when value.count() <= 0 -> errors.add(path + ": count must be positive");
            case CompositeConditions.AllOf value -> validateChildren(value.children(), path, true, errors);
            case CompositeConditions.AnyOf value -> validateChildren(value.children(), path, true, errors);
            case CompositeConditions.Sequence value -> {
                validateChildren(value.children(), path, true, errors);
                for (int index = 0; index + 1 < value.children().size(); index++) {
                    if (!ConditionApi.capabilities(value.children().get(index)).canCompleteFromSignals()) {
                        errors.add(path + ".children[" + index + "]: a claim-time condition can only be the final sequence step");
                    }
                }
            }
            case CompositeConditions.NOfM value -> {
                validateChildren(value.children(), path, true, errors);
                if (value.required() <= 0 || value.required() > value.children().size()) {
                    errors.add(path + ": required must be between 1 and the number of children");
                }
            }
            case CompositeConditions.Repeat value -> {
                if (value.times() <= 0) errors.add(path + ": times must be positive");
                if (!ConditionApi.capabilities(value.child()).canCompleteFromSignals()) {
                    errors.add(path + ": repeat cannot contain a claim-time-only condition");
                }
                validateCondition(value.child(), path + ".child", errors);
            }
            case CompositeConditions.OptionalChild value -> {
                if (ConditionApi.capabilities(value.child()).containsClaimCost()) {
                    errors.add(path + ": optional conditions cannot contain claim-time costs");
                }
                validateCondition(value.child(), path + ".child", errors);
            }
            case CompositeConditions.Choice value -> {
                if (value.branches().isEmpty()) errors.add(path + ": at least one branch is required");
                Set<String> names = new HashSet<>();
                for (int index = 0; index < value.branches().size(); index++) {
                    String name = value.branches().get(index).name();
                    if (name.isBlank()) {
                        errors.add(path + ".branches[" + index + "]: name cannot be blank");
                    } else if (!names.add(name)) {
                        errors.add(path + ".branches[" + index + "]: duplicate branch name '" + name + "'");
                    }
                    validateCondition(value.branches().get(index).condition(), path + ".branches[" + index + "]", errors);
                }
            }
            case CompositeConditions.TimeWindow value -> {
                if (value.durationTicks() <= 0L) errors.add(path + ": duration_ticks must be positive");
                if (value.maxAttempts() < 0) errors.add(path + ": max_attempts cannot be negative");
                if (value.startPolicy() == CompositeConditions.StartPolicy.START_CONDITION && value.startCondition().isEmpty()) {
                    errors.add(path + ": start_condition is required for start_policy=start_condition");
                }
                if (value.startPolicy() != CompositeConditions.StartPolicy.START_CONDITION && value.startCondition().isPresent()) {
                    errors.add(path + ": start_condition is only used with start_policy=start_condition");
                }
                if (value.startPolicy() == CompositeConditions.StartPolicy.START_CONDITION
                        && value.startCondition().isPresent()
                        && !ConditionApi.capabilities(value.startCondition().get()).canCompleteFromSignals()) {
                    errors.add(path + ": start_condition must be completable from quest signals");
                }
                if (value.startPolicy() == CompositeConditions.StartPolicy.FIRST_PROGRESS
                        && !ConditionApi.capabilities(value.child()).canProgressFromSignals()) {
                    errors.add(path + ": first_progress requires a child that can receive event progress");
                }
                validateCondition(value.child(), path + ".child", errors);
                value.startCondition().ifPresent(start -> validateCondition(start, path + ".start_condition", errors));
            }
            default -> {
            }
        }
    }

    private void validateChildren(List<ConditionApi.Definition> children, String path, boolean requireChildren, List<String> errors) {
        if (requireChildren && children.isEmpty()) errors.add(path + ": at least one child is required");
        for (int index = 0; index < children.size(); index++) {
            validateCondition(children.get(index), path + ".children[" + index + "]", errors);
        }
    }

}
