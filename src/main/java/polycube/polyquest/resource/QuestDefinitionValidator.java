package polycube.polyquest.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.condition.CompositeConditions;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;

/// Semantic validation performed after codec decoding and template expansion.
public final class QuestDefinitionValidator {
    public List<String> validate(
            QuestModel.Definition quest,
            Map<Identifier, RewardApi.Profile> profiles) {
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
        if (quest.rewards().profile().isPresent()
                && !profiles.containsKey(quest.rewards().profile().get())) {
            errors.add(prefix + "unknown reward profile '" + quest.rewards().profile().get() + "'");
        }
        if (quest.rewards().profile().isPresent() && !quest.rewards().inlineRewards().isEmpty()) {
            errors.add(prefix + "use either a reward profile or inline rewards, not both");
        }
        if (quest.rewards().profile().isEmpty() && quest.rewards().inlineRewards().isEmpty()) {
            errors.add(prefix + "at least one reward is required");
        }

        validateCondition(quest.condition(), prefix + "condition", errors);
        return errors;
    }

    private void validateCondition(
            ConditionApi.Definition definition,
            String path,
            List<String> errors) {
        if (definition instanceof BuiltInConditions.ConsumeItems value && value.count() <= 0) {
            errors.add(path + ": count must be positive");
        } else if (definition instanceof BuiltInConditions.FishItem value && value.count() <= 0) {
            errors.add(path + ": count must be positive");
        } else if (definition instanceof BuiltInConditions.KillEntity value && value.count() <= 0) {
            errors.add(path + ": count must be positive");
        } else if (definition instanceof BuiltInConditions.BreakBlock value && value.count() <= 0) {
            errors.add(path + ": count must be positive");
        } else if (definition instanceof BuiltInConditions.VisitLocation value
                && value.continuousTicks() <= 0) {
            errors.add(path + ": continuous_ticks must be positive");
        } else if (definition instanceof BuiltInConditions.ExplicitSignal value && value.count() <= 0) {
            errors.add(path + ": count must be positive");
        } else if (definition instanceof BuiltInConditions.UninterruptedFall value
                && value.minimumDistance() < 0.0) {
            errors.add(path + ": minimum_distance cannot be negative");
        } else if (definition instanceof CompositeConditions.AllOf value) {
            validateChildren(value.children(), path, true, errors);
        } else if (definition instanceof CompositeConditions.AnyOf value) {
            validateChildren(value.children(), path, true, errors);
        } else if (definition instanceof CompositeConditions.Sequence value) {
            validateChildren(value.children(), path, true, errors);
        } else if (definition instanceof CompositeConditions.NOfM value) {
            validateChildren(value.children(), path, true, errors);
            if (value.required() <= 0 || value.required() > value.children().size()) {
                errors.add(path + ": required must be between 1 and the number of children");
            }
        } else if (definition instanceof CompositeConditions.Repeat value) {
            if (value.times() <= 0) {
                errors.add(path + ": times must be positive");
            }
            validateCondition(value.child(), path + ".child", errors);
        } else if (definition instanceof CompositeConditions.OptionalChild value) {
            validateCondition(value.child(), path + ".child", errors);
        } else if (definition instanceof CompositeConditions.Choice value) {
            if (value.branches().isEmpty()) {
                errors.add(path + ": at least one branch is required");
            }
            for (int index = 0; index < value.branches().size(); index++) {
                validateCondition(
                        value.branches().get(index).condition(),
                        path + ".branches[" + index + "]",
                        errors);
            }
        } else if (definition instanceof CompositeConditions.TimeWindow value) {
            if (value.durationTicks() <= 0L) {
                errors.add(path + ": duration_ticks must be positive");
            }
            if (value.maxAttempts() < 0) {
                errors.add(path + ": max_attempts cannot be negative");
            }
            if (value.startPolicy() == CompositeConditions.StartPolicy.START_CONDITION
                    && value.startCondition().isEmpty()) {
                errors.add(path + ": start_condition is required for start_policy=start_condition");
            }
            validateCondition(value.child(), path + ".child", errors);
            value.startCondition().ifPresent(start ->
                    validateCondition(start, path + ".start_condition", errors));
        }
    }

    private void validateChildren(
            List<ConditionApi.Definition> children,
            String path,
            boolean requireChildren,
            List<String> errors) {
        if (requireChildren && children.isEmpty()) {
            errors.add(path + ": at least one child is required");
        }
        for (int index = 0; index < children.size(); index++) {
            validateCondition(children.get(index), path + ".children[" + index + "]", errors);
        }
    }
}
