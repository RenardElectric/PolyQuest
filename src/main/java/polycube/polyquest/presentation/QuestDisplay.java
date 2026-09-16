package polycube.polyquest.presentation;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.players.NameAndId;
import polycube.polyquest.commands.QuestCommandText;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.condition.CompositeConditions;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.BuiltInRewards;
import polycube.polyquest.reward.RewardApi;
import polycube.polyquest.runtime.QuestManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static polycube.polycore.text.TextComponents.*;
import static polycube.polycore.text.TextCore.*;
import static polycube.polycore.text.TextUtil.*;

/// Builds the shared player-facing presentation of one quest occurrence.
public final class QuestDisplay {
    private static final int DESCRIPTION_WIDTH = 42;
    private static final int MAX_DESCRIPTION_LINES = 3;
    private static final int MAX_CONDITION_LINES = 6;
    private static final int MAX_REWARD_LINES = 4;

    private final QuestManager manager;

    private QuestDisplay(QuestManager manager) {
        this.manager = manager;
    }

    /// Resolves live quest state and formats it for item lore or multiline chat output.
    public static Presentation format(
            QuestManager manager,
            NameAndId player,
            QuestModel.Occurrence occurrence
    ) {
        return new QuestDisplay(manager).format(player, occurrence);
    }

    private Presentation format(NameAndId player, QuestModel.Occurrence occurrence) {
        QuestModel.Definition definition = occurrence.definition();
        boolean claimed = manager.isClaimed(player, occurrence.key());
        var attempt = manager.attempt(player, occurrence);
        QuestModel.AttemptStatus status = claimed
                ? QuestModel.AttemptStatus.CLAIMED
                : attempt.status();
        boolean unavailable = !claimed && occurrence.availableUntil()
                .map(deadline -> !deadline.isAfter(Instant.now()))
                .orElse(false);
        JsonObject conditionDiagnostic = attempt.diagnostic().getAsJsonObject("condition");

        List<Component> lines = new ArrayList<>();
        for (String line : descriptionLines(definition.description(), DESCRIPTION_WIDTH, MAX_DESCRIPTION_LINES)) {
            lines.add(styled(line, ChatFormatting.GRAY));
        }

        lines.add(Component.empty());
        lines.add(section("QUEST DETAILS"));
        ChatFormatting nameColor = difficultyColor(definition.difficulty());
        lines.add(detail("Quest", QuestCommandText.availability(definition), nameColor));
        lines.add(detail("Expires", QuestCommandText.expiry(occurrence), ChatFormatting.WHITE));
        lines.add(Component.empty());
        lines.add(section("OBJECTIVE"));

        List<Component> conditions = conditionLines(
                definition.condition(),
                conditionDiagnostic,
                status == QuestModel.AttemptStatus.CLAIMED);
        int visibleConditions = Math.min(conditions.size(), MAX_CONDITION_LINES);
        lines.addAll(conditions.subList(0, visibleConditions));
        if (conditions.size() > visibleConditions) {
            lines.add(styled(
                    "  +" + (conditions.size() - visibleConditions) + " more objective details",
                    ChatFormatting.DARK_GRAY));
        }

        progress(definition.condition(), conditionDiagnostic)
                .ifPresent(value -> lines.add(progressLine(value, status)));
        lines.add(detail(
                "Status",
                unavailable ? "Unavailable" : QuestCommandText.statusLabel(status),
                unavailable ? ChatFormatting.RED : statusColor(status)));
        lines.add(Component.empty());
        lines.add(section("REWARDS"));

        List<Component> rewards = rewardLines(definition.rewards());
        int visibleRewards = Math.min(rewards.size(), MAX_REWARD_LINES);
        lines.addAll(rewards.subList(0, visibleRewards));
        if (rewards.size() > visibleRewards) {
            lines.add(styled(
                    "  +" + (rewards.size() - visibleRewards) + " more rewards",
                    ChatFormatting.DARK_GRAY));
        }

        Component actionHint = styled(
                actionHint(status, unavailable),
                unavailable ? ChatFormatting.RED : statusColor(status));
        return new Presentation(
                questTitle(definition.title(), nameColor, status, unavailable),
                lines,
                actionHint,
                status,
                claimed,
                unavailable);
    }

    private List<Component> conditionLines(ConditionApi.Definition condition, JsonObject diagnostic, boolean forceCompleted) {
        List<Component> lines = new ArrayList<>();
        appendCondition(lines, condition, diagnostic, "", forceCompleted);
        return lines;
    }

    private void appendCondition(
            List<Component> lines, ConditionApi.Definition condition,
            JsonObject diagnostic, String prefix, boolean forceCompleted
    ) {
        boolean completed = conditionCompleted(diagnostic, forceCompleted);
        switch (condition) {
            case CompositeConditions.AllOf value when value.children().size() == 1 ->
                    appendCondition(
                            lines,
                            value.children().getFirst(),
                            indexedDiagnostic(diagnostic, "children", 0),
                            prefix,
                            forceCompleted);
            case CompositeConditions.AllOf value -> {
                lines.add(conditionRule(
                        prefix,
                        "Complete all " + count(value.children().size(), "objective") + ":",
                        completed));
                for (int index : incompleteFirst(value.children().size(), diagnostic, "children", forceCompleted)) {
                    appendCondition(
                            lines,
                            value.children().get(index),
                            indexedDiagnostic(diagnostic, "children", index),
                            prefix + "  • ",
                            forceCompleted);
                }
            }
            case CompositeConditions.AnyOf value when value.children().size() == 1 ->
                    appendCondition(
                            lines,
                            value.children().getFirst(),
                            indexedDiagnostic(diagnostic, "children", 0),
                            prefix,
                            forceCompleted);
            case CompositeConditions.AnyOf value -> {
                lines.add(conditionRule(prefix, "Complete any one:", completed));
                for (int index : incompleteFirst(value.children().size(), diagnostic, "children", false)) {
                    appendCondition(
                            lines,
                            value.children().get(index),
                            indexedDiagnostic(diagnostic, "children", index),
                            prefix + "  • ",
                            false);
                }
            }
            case CompositeConditions.Repeat value -> {
                lines.add(conditionRule(prefix, "Repeat " + value.times() + " times:", completed));
                appendCondition(
                        lines,
                        value.child(),
                        nestedDiagnostic(diagnostic, "child"),
                        prefix + "  • ",
                        forceCompleted);
            }
            case CompositeConditions.Sequence value -> {
                lines.add(conditionRule(prefix, "Complete in order:", completed));
                for (int index : incompleteFirst(value.children().size(), diagnostic, "children", forceCompleted)) {
                    appendCondition(
                            lines,
                            value.children().get(index),
                            indexedDiagnostic(diagnostic, "children", index),
                            prefix + "  " + (index + 1) + ". ",
                            forceCompleted);
                }
            }
            case CompositeConditions.TimeWindow value -> {
                String attempts = completed ? "" : " • " + attemptsRemaining(value, diagnostic);
                lines.add(conditionRule(
                        prefix,
                        "Within " + duration(value.durationTicks()) + attempts + ":",
                        completed));

                JsonObject childDiagnostic = nestedDiagnostic(diagnostic, "child");
                JsonObject startDiagnostic = nestedDiagnostic(diagnostic, "start_condition");
                boolean startCompleted = conditionCompleted(startDiagnostic, forceCompleted);
                boolean childCompleted = conditionCompleted(childDiagnostic, forceCompleted);
                if (value.startCondition().isPresent() && startCompleted && !childCompleted) {
                    appendCondition(lines, value.child(), childDiagnostic, prefix + "  • ", forceCompleted);
                    appendStartCondition(
                            lines,
                            value.startCondition().get(),
                            startDiagnostic,
                            prefix,
                            forceCompleted);
                } else {
                    value.startCondition().ifPresent(start -> appendStartCondition(
                            lines,
                            start,
                            startDiagnostic,
                            prefix,
                            forceCompleted));
                    appendCondition(lines, value.child(), childDiagnostic, prefix + "  • ", forceCompleted);
                }
            }
            case CompositeConditions.NOfM value -> {
                boolean allChildrenRequired = value.required() == value.children().size();
                lines.add(conditionRule(
                        prefix,
                        "Complete " + value.required() + " of " + count(value.children().size(), "objective") + ":",
                        completed));
                boolean forceChildren = forceCompleted && allChildrenRequired;
                for (int index : incompleteFirst(value.children().size(), diagnostic, "children", forceChildren)) {
                    appendCondition(
                            lines,
                            value.children().get(index),
                            indexedDiagnostic(diagnostic, "children", index),
                            prefix + "  • ",
                            forceChildren);
                }
            }
            case CompositeConditions.OptionalChild value -> {
                lines.add(conditionRule(prefix, "Optional:", completed));
                appendCondition(
                        lines,
                        value.child(),
                        nestedDiagnostic(diagnostic, "child"),
                        prefix + "  • ",
                        false);
            }
            case CompositeConditions.Choice value -> {
                lines.add(conditionRule(prefix, "Choose one path:", completed));
                boolean forceOnlyBranch = forceCompleted && value.branches().size() == 1;
                for (int index : incompleteFirst(value.branches().size(), diagnostic, "branches", forceOnlyBranch)) {
                    var branch = value.branches().get(index);
                    JsonObject branchDiagnostic = indexedDiagnostic(diagnostic, "branches", index);
                    lines.add(conditionRule(
                            prefix + "  • ",
                            branch.name() + ":",
                            conditionCompleted(branchDiagnostic, forceOnlyBranch)));
                    appendCondition(
                            lines,
                            branch.condition(),
                            branchDiagnostic,
                            prefix + "    ",
                            forceOnlyBranch);
                }
            }
            default -> lines.add(styled(
                    prefix + conditionSummary(condition) + (completed ? " ✓" : ""),
                    completed ? ChatFormatting.GREEN : ChatFormatting.WHITE));
        }
    }

    private static String attemptsRemaining(
            CompositeConditions.TimeWindow condition,
            JsonObject diagnostic
    ) {
        int limit = condition.timeoutAction() == CompositeConditions.TimeoutAction.EXHAUST
                ? 1
                : condition.maxAttempts();
        if (limit == 0) {
            return "unlimited attempts";
        }
        int attemptsUsed = diagnostic.has("attempts")
                ? diagnostic.get("attempts").getAsInt()
                : 0;
        return count(Math.max(0, limit - attemptsUsed), "attempt") + " left";
    }

    private void appendStartCondition(
            List<Component> lines, ConditionApi.Definition condition,
            JsonObject diagnostic, String prefix, boolean forceCompleted
    ) {
        lines.add(conditionRule(prefix + "  ", "Start when:", conditionCompleted(diagnostic, forceCompleted)));
        appendCondition(lines, condition, diagnostic, prefix + "    • ", forceCompleted);
    }

    private String conditionSummary(ConditionApi.Definition condition) {
        var ops = manager.server().registryAccess().createSerializationContext(JsonOps.INSTANCE);

        return switch (condition) {
            case BuiltInConditions.ConsumeItems value -> "Turn in " + value.count() + "× " + itemTarget(value.item());
            case BuiltInConditions.FishItem value -> "Catch " + value.count() + "× " + itemTarget(value.item());
            case BuiltInConditions.KillEntity value ->
                    "Defeat " + value.count() + "× " + entityTarget(ops, value.victim())
                            + value.damageSource().map(source -> " using " + damageTarget(ops, source)).orElse("");
            case BuiltInConditions.BreakBlock value ->
                    "Break " + value.count() + "× " + blockTarget(value.block())
                            + value.tool().map(tool -> " with " + itemTarget(tool)).orElse("");
            case BuiltInConditions.VisitLocation value when value.continuousTicks() > 1 -> "Stay at " + locationTarget(value.location()) + " for " + duration(value.continuousTicks());
            case BuiltInConditions.VisitLocation value -> "Visit " + locationTarget(value.location());
            case BuiltInConditions.PlayerDeath value -> value.damageSource()
                    .map(source -> "Die from " + damageTarget(ops, source))
                    .orElse("Die once");
            case BuiltInConditions.ObtainAdvancement value -> "Complete " + humanize(value.advancement()) + " advancement";
            case BuiltInConditions.ExplicitSignal value when value.count() > 1 -> "Trigger " + humanize(value.signal()) + " " + value.count() + " times";
            case BuiltInConditions.ExplicitSignal value -> "Trigger " + humanize(value.signal());
            case BuiltInConditions.UninterruptedFall value ->
                    "Fall " + number(value.minimumDistance()) + " blocks from "
                            + locationTarget(value.start()) + " to " + locationTarget(value.end())
                            + (value.requireSurvival() ? " and survive" : "");
            default -> humanize(condition.type().id());
        };
    }

    private static Optional<Progress> progress(
            ConditionApi.Definition condition,
            JsonObject diagnostic
    ) {
        if (diagnostic.has("current") && diagnostic.has("target")) {
            return Optional.of(new Progress(diagnostic.get("current").getAsInt(), diagnostic.get("target").getAsInt()));
        }
        return switch (condition) {
            case BuiltInConditions.ConsumeItems ignored -> Optional.empty();
            case CompositeConditions.Repeat value -> Optional.of(new Progress(diagnostic.get("iterations").getAsInt(), value.times()));
            case CompositeConditions.AllOf value -> Optional.of(new Progress(completedChildren(diagnostic), value.children().size()));
            case CompositeConditions.AnyOf ignored -> Optional.of(new Progress(diagnostic.get("completed").getAsBoolean() ? 1 : 0, 1));
            case CompositeConditions.Sequence value -> Optional.of(new Progress(completedChildren(diagnostic), value.children().size()));
            case CompositeConditions.TimeWindow value -> progress(value.child(), diagnostic.getAsJsonObject("child"));
            case CompositeConditions.NOfM value -> Optional.of(new Progress(Math.min(completedChildren(diagnostic), value.required()), value.required()));
            case CompositeConditions.OptionalChild value -> progress(value.child(), diagnostic.getAsJsonObject("child"));
            case CompositeConditions.Choice ignored -> Optional.of(new Progress(diagnostic.get("completed").getAsBoolean() ? 1 : 0, 1));
            default -> Optional.of(new Progress(diagnostic.get("completed").getAsBoolean() ? 1 : 0, 1));
        };
    }

    private static int completedChildren(JsonObject diagnostic) {
        int completed = 0;
        for (var child : diagnostic.getAsJsonArray("children")) {
            if (child.getAsJsonObject().get("completed").getAsBoolean()) completed++;
        }
        return completed;
    }

    private static Component progressLine(Progress progress, QuestModel.AttemptStatus status) {
        int current = status == QuestModel.AttemptStatus.CLAIMED
                ? progress.target()
                : progress.current();
        int filled = progress.target() == 0
                ? 0
                : (int) Math.ceil(current * 8.0 / progress.target());
        filled = Math.clamp(filled, 0, 8);
        return styled("Progress  ", ChatFormatting.DARK_GRAY)
                .append(styled("■".repeat(filled), statusColor(status)))
                .append(styled("□".repeat(8 - filled), ChatFormatting.DARK_GRAY))
                .append(styled("  " + current + "/" + progress.target(), ChatFormatting.GRAY));
    }

    private List<Component> rewardLines(RewardApi.Plan rewards) {
        if (rewards.profile().isPresent()) {
            Identifier profileId = rewards.profile().get();
            List<Component> lines = new ArrayList<>();
            lines.add(detail("Bundle", humanize(profileId), ChatFormatting.GREEN));
            manager.rewardProfile(profileId).ifPresentOrElse(
                    profile -> profile.rewards().forEach(reward -> lines.add(rewardLine(reward))),
                    () -> lines.add(styled("• Rewards unavailable", ChatFormatting.RED)));
            return lines;
        }
        if (rewards.inlineRewards().isEmpty()) {
            return List.of(styled("No rewards configured.", ChatFormatting.DARK_GRAY));
        }

        List<Component> lines = new ArrayList<>();
        for (var reward : rewards.inlineRewards()) {
            lines.add(rewardLine(reward));
        }
        return lines;
    }

    private static Component rewardLine(RewardApi.Definition reward) {
        return switch (reward) {
            case BuiltInRewards.Money value -> bullet(value.amount().toPlainString() + " money");
            case BuiltInRewards.Experience value -> bullet(value.points() + " experience points");
            case BuiltInRewards.Item value -> {
                var stack = value.stackTemplate().create();
                yield styled("• " + stack.getCount() + "× ", ChatFormatting.DARK_GRAY)
                        .append(stack.getHoverName().copy().withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withItalic(false)));
            }
            case BuiltInRewards.ServerCommands ignored -> bullet("Special server reward");
            default -> bullet(humanize(reward.type().id()));
        };
    }

    private static MutableComponent questTitle(
            String title, ChatFormatting titleColor,
            QuestModel.AttemptStatus status, boolean unavailable
    ) {
        MutableComponent result = styled(title, titleColor, true);
        if (unavailable) return result.append(styled("  ✕", ChatFormatting.RED, true));
        return switch (status) {
            case ACTIVE -> result;
            case READY_TO_CLAIM -> result.append(styled("  !", ChatFormatting.GREEN, true));
            case CLAIM_PENDING -> result.append(styled("  ↻", ChatFormatting.YELLOW, true));
            case CLAIMED -> result.append(styled("  ✓", ChatFormatting.GOLD, true));
            case EXHAUSTED -> result.append(styled("  ✕", ChatFormatting.RED, true));
        };
    }

    private static ChatFormatting difficultyColor(Optional<QuestModel.Difficulty> difficulty) {
        return difficulty.map(value -> switch (value) {
            case EASY -> ChatFormatting.GREEN;
            case MEDIUM -> ChatFormatting.GOLD;
            case HARD -> ChatFormatting.RED;
        }).orElse(ChatFormatting.LIGHT_PURPLE);
    }

    private static ChatFormatting statusColor(QuestModel.AttemptStatus status) {
        return switch (status) {
            case ACTIVE -> ChatFormatting.AQUA;
            case READY_TO_CLAIM -> ChatFormatting.GREEN;
            case CLAIM_PENDING -> ChatFormatting.YELLOW;
            case CLAIMED -> ChatFormatting.GOLD;
            case EXHAUSTED -> ChatFormatting.RED;
        };
    }

    private static String actionHint(QuestModel.AttemptStatus status, boolean unavailable) {
        if (unavailable) {
            return "Quest is no longer available";
        }
        return switch (status) {
            case ACTIVE -> "Click to check and claim";
            case READY_TO_CLAIM -> "Click to claim your rewards";
            case CLAIM_PENDING -> "Click to retry the reward";
            case CLAIMED -> "Reward already claimed";
            case EXHAUSTED -> "No attempts remaining";
        };
    }

    public record Presentation(
            Component title, List<Component> lines, Component actionHint,
            QuestModel.AttemptStatus status, boolean claimed, boolean unavailable
    ) {
        public Presentation {
            lines = List.copyOf(lines);
        }
    }

    private record Progress(int current, int target) {}
}
