package polycube.polyquest.presentation;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;
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
    public static Presentation format(QuestManager manager, MinecraftServer server, NameAndId player, QuestModel.Occurrence occurrence) {
        return new QuestDisplay(manager).format(server, player, occurrence);
    }

    private Presentation format(MinecraftServer server, NameAndId player, QuestModel.Occurrence occurrence) {
        var definition = occurrence.definition();
        var attempt = manager.attempt(player, occurrence);
        var status = attempt.status();
        var claimed = status == QuestModel.AttemptStatus.CLAIMED;
        var unavailable = !claimed && occurrence.availableUntil()
                .map(deadline -> !deadline.isAfter(Instant.now()))
                .orElse(false);
        var conditionDiagnostic = attempt.diagnostic().getAsJsonObject("condition");

        List<Component> lines = new ArrayList<>();
        for (String line : descriptionLines(definition.description(), DESCRIPTION_WIDTH, MAX_DESCRIPTION_LINES)) {
            lines.add(styled(line, ChatFormatting.GRAY));
        }

        // Quest details section

        lines.add(Component.empty());
        lines.add(section("QUEST DETAILS"));
        var nameColor = QuestCommandText.questColor(definition);
        lines.add(detail("Quest", QuestCommandText.availability(definition), nameColor));
        lines.add(detail("Expires", QuestCommandText.expiry(occurrence), ChatFormatting.WHITE));

        // Objective section

        lines.add(Component.empty());
        lines.add(section("OBJECTIVE"));

        var forcedStatus = switch (status) {
            case CLAIMED -> ConditionStatus.CLAIMED;
            case EXHAUSTED -> ConditionStatus.EXHAUSTED;
            default -> ConditionStatus.DEFAULT;
        };
        List<Component> conditions = conditionLines(server, definition.condition(), conditionDiagnostic, forcedStatus);
        int visibleConditions = Math.min(conditions.size(), MAX_CONDITION_LINES);
        lines.addAll(conditions.subList(0, visibleConditions));
        if (conditions.size() > visibleConditions) {
            lines.add(muted("  +" + (conditions.size() - visibleConditions) + " more objective lines"));
        }

        progress(definition.condition(), conditionDiagnostic, forcedStatus).ifPresent(value -> lines.add(progressLine(value, status)));
        lines.add(detail(
                "Status",
                unavailable ? "Unavailable" : QuestCommandText.statusLabel(status),
                unavailable ? ChatFormatting.RED : statusColor(status))
        );

        // Rewards section

        lines.add(Component.empty());
        lines.add(section("REWARDS"));

        List<Component> rewards = rewardLines(definition.rewards());
        int visibleRewards = Math.min(rewards.size(), MAX_REWARD_LINES);
        lines.addAll(rewards.subList(0, visibleRewards));
        if (rewards.size() > visibleRewards) {
            lines.add(muted("  +" + (rewards.size() - visibleRewards) + " more rewards"));
        }

        Component actionHint = styled(
                actionHint(status, unavailable),
                unavailable ? ChatFormatting.RED : statusColor(status)
        );
        return new Presentation(
                questTitle(definition.title(), nameColor, status, unavailable),
                lines, actionHint, status, claimed, unavailable
        );
    }

    private List<Component> conditionLines(MinecraftServer server, ConditionApi.Definition condition, JsonObject diagnostic, ConditionStatus forcedStatus) {
        List<Component> lines = new ArrayList<>();
        appendCondition(server, lines, condition, diagnostic, "", forcedStatus, 0);
        return lines;
    }

    private void appendCondition(
            MinecraftServer server, List<Component> lines,
            ConditionApi.Definition condition,
            JsonObject diagnostic, String prefix,
            ConditionStatus forcedStatus, int depth
    ) {
        var status = conditionStatus(diagnostic, forcedStatus);
        var isActive = status == ConditionStatus.DEFAULT || status == ConditionStatus.ACTIVE;
        switch (condition) {
            case CompositeConditions.AllOf value when value.children().size() == 1 ->
                    appendCondition(
                            server, lines, value.children().getFirst(),
                            indexedJson(diagnostic, "children", 0),
                            prefix, forcedStatus, depth
                    );
            case CompositeConditions.AllOf value -> {
                var suffix = getProgressSummary(condition, diagnostic, status);
                lines.add(conditionRule(prefix, "Complete all " + count(value.children().size(), "objective") + suffix + ":", status, depth));
                for (int index : incompleteFirst(value.children().size(), diagnostic, "children", forcedStatus)) {
                    appendCondition(
                            server, lines, value.children().get(index),
                            indexedJson(diagnostic, "children", index),
                            "• ", forcedStatus, depth + 1
                    );
                }
            }
            case CompositeConditions.AnyOf value when value.children().size() == 1 ->
                    appendCondition(
                            server, lines, value.children().getFirst(),
                            indexedJson(diagnostic, "children", 0),
                            prefix, forcedStatus, depth
                    );
            case CompositeConditions.AnyOf value -> {
                lines.add(conditionRule(prefix, "Complete any one:", status, depth));
                for (int i = 0; i < value.children().size(); i++) {
                    appendCondition(
                            server, lines, value.children().get(i),
                            indexedJson(diagnostic, "children", i),
                            "• ", forcedStatus, depth + 1
                    );
                }
            }
            case CompositeConditions.Repeat value -> {
                var suffix = getProgressSummary(condition, diagnostic, status);
                lines.add(conditionRule(prefix, "Repeat " + value.times() + " times" + suffix + ":", status, depth));
                appendCondition(
                        server, lines, value.child(),
                        nestedJson(diagnostic, "child"),
                        "• ", forcedStatus, depth + 1
                );
            }
            case CompositeConditions.Sequence value -> {
                var suffix = getProgressSummary(condition, diagnostic, status);
                lines.add(conditionRule(prefix, "Complete in order" + suffix + ":", status, depth));
                for (int index : incompleteFirst(value.children().size(), diagnostic, "children", forcedStatus)) {
                    appendCondition(
                            server, lines, value.children().get(index),
                            indexedJson(diagnostic, "children", index),
                            (index + 1) + ". ", forcedStatus, depth + 1
                    );
                }
            }
            case CompositeConditions.TimeWindow value -> {
                var attempts = isActive ? attemptsRemaining(value, diagnostic) : "";
                var timeRemaining = !isActive ? "" : " • " + timeRemaining(server, diagnostic);
                lines.add(conditionRule(prefix, "Within " + duration(value.durationTicks()) + attempts + timeRemaining + ":", status, depth));

                var childDiagnostic = nestedJson(diagnostic, "child");
                var startDiagnostic = nestedJson(diagnostic, "start_condition");
                var startActive = conditionActive(startDiagnostic, forcedStatus);
                var childActive = conditionActive(childDiagnostic, forcedStatus);
                if (value.startCondition().isPresent() && !startActive && childActive) {
                    appendCondition(server, lines, value.child(), childDiagnostic, "• ", forcedStatus, depth + 1);
                    appendStartCondition(
                            server, lines, value.startCondition().get(),
                            startDiagnostic, forcedStatus, depth + 1
                    );
                } else {
                    value.startCondition().ifPresent(start -> appendStartCondition(
                            server, lines, start, startDiagnostic,
                            forcedStatus, depth + 1)
                    );
                    appendCondition(server, lines, value.child(), childDiagnostic, "• ", forcedStatus, depth + 1);
                }
            }
            case CompositeConditions.NOfM value -> {
                var suffix = getProgressSummary(condition, diagnostic, status);
                lines.add(conditionRule(
                        prefix,
                        "Complete " + value.required() + " of " + count(value.children().size(), "objective") + suffix + ":",
                        status, depth)
                );
                for (int index : incompleteFirst(value.children().size(), diagnostic, "children", forcedStatus)) {
                    appendCondition(
                            server, lines, value.children().get(index),
                            indexedJson(diagnostic, "children", index),
                            "• ", forcedStatus, depth + 1
                    );
                }
            }
            case CompositeConditions.OptionalChild value -> {
                lines.add(conditionRule(prefix, "Optional:", status, depth));
                appendCondition(
                        server, lines, value.child(), nestedJson(diagnostic, "child"),
                        "• ", forcedStatus, depth + 1);
            }
            case CompositeConditions.Choice value -> {
                lines.add(conditionRule(prefix, "Choose one path:", status, depth));
                for (int index : incompleteFirst(value.branches().size(), diagnostic, "branches", forcedStatus)) {
                    var branch = value.branches().get(index);
                    JsonObject branchDiagnostic = indexedJson(diagnostic, "branches", index);
                    lines.add(conditionRule(
                            "• ",
                            branch.name() + ":",
                            conditionStatus(branchDiagnostic, forcedStatus), depth + 1));
                    appendCondition(
                            server, lines,
                            branch.condition(), branchDiagnostic,
                            "• ", forcedStatus, depth + 2);
                }
            }
            default -> {
                var suffix = getProgressSummary(condition, diagnostic, status);
                lines.add(conditionFormat(prefix, ConditionText.summary(server, condition), suffix, ChatFormatting.WHITE, ChatFormatting.WHITE, status, depth));
            }
        }
    }

    private static String attemptsRemaining(CompositeConditions.TimeWindow condition, JsonObject diagnostic) {
        int limit = condition.timeoutAction() == CompositeConditions.TimeoutAction.EXHAUST ? 1 : condition.maxAttempts();
        if (limit == 0) return "";
        int attemptsUsed = diagnostic.has("attempts") ? diagnostic.get("attempts").getAsInt() : 0;
        return " • " + count(Math.max(0, limit - attemptsUsed), "attempt") + " left";
    }

    private static String timeRemaining(MinecraftServer server, JsonObject diagnostic) {
        if (!diagnostic.has("deadline")) return "no time limit";
        long deadline = diagnostic.get("deadline").getAsLong();
        if (deadline <= 0) return "not started";
        long remainingTicks = Math.max(0, deadline - server.getTickCount());
        return duration(remainingTicks) + " remaining";
    }

    private void appendStartCondition(
            MinecraftServer server, List<Component> lines,
            ConditionApi.Definition condition, JsonObject diagnostic,
            ConditionStatus forcedStatus, int depth
    ) {
        lines.add(conditionRule("• ", "Start when:", forcedStatus, depth));
        appendCondition(server, lines, condition, diagnostic, "• ", forcedStatus, depth + 1);
    }

    private static Component conditionRule(String prefix, String text, ConditionStatus status, int depth) {
        return conditionFormat(prefix, text, ChatFormatting.DARK_GRAY, ChatFormatting.GRAY, status, depth);
    }

    private static MutableComponent conditionFormat(String prefix, String text, ChatFormatting prefixColor, ChatFormatting titleColor, ConditionStatus status, int depth) {
        return conditionFormat(prefix, Component.literal(text), "", prefixColor, titleColor, status, depth);
    }

    private static MutableComponent conditionFormat(String prefix, Component text, String suffix, ChatFormatting prefixColor, ChatFormatting titleColor, ConditionStatus status, int depth) {
        var tColor = switch (status) {
            case CLAIMED -> ChatFormatting.GREEN;
            case EXHAUSTED -> ChatFormatting.RED;
            default -> titleColor;
        };
        var pColor = switch (status) {
            case CLAIMED -> ChatFormatting.GREEN;
            case EXHAUSTED -> ChatFormatting.RED;
            default -> prefixColor;
        };
        return styled("   ".repeat(depth) + prefix, pColor)
                .append(text.copy().withStyle(tColor))
                .append(styled(suffix + suffix(status), tColor));
    }

    private static String suffix(ConditionStatus status) {
        return switch (status) {
            case CLAIMED -> " ✓";
            case EXHAUSTED -> " ✕";
            default -> "";
        };
    }

    private String getProgressSummary(ConditionApi.Definition condition, JsonObject diagnostic, ConditionStatus status) {
        return progress(condition, diagnostic, status)
                .map(value -> " (" + value.current() + "/" + value.target() + ")")
                .orElse("");
    }

    private static ConditionStatus conditionStatus(JsonObject diagnostic, ConditionStatus forceStatus) {
        if (forceStatus != ConditionStatus.DEFAULT) return forceStatus;
        if (diagnostic.has("exhausted") && diagnostic.get("exhausted").getAsBoolean()) return ConditionStatus.EXHAUSTED;
        if (diagnostic.has("completed") && diagnostic.get("completed").getAsBoolean()) return ConditionStatus.CLAIMED;
        return ConditionStatus.DEFAULT;
    }

    private static List<Integer> incompleteFirst(int size, JsonObject diagnostic, String field, ConditionStatus forcedStatus) {
        List<Integer> indices = new ArrayList<>(size);

        for(int index = 0; index < size; ++index) {
            indices.add(index);
        }

        indices.sort((left, right) -> Boolean.compare(conditionActive(indexedJson(diagnostic, field, right), forcedStatus), conditionActive(indexedJson(diagnostic, field, left), forcedStatus)));
        return indices;
    }

    private static boolean conditionActive(JsonObject diagnostic, ConditionStatus forceStatus) {
        var status = conditionStatus(diagnostic, forceStatus);
        return status == ConditionStatus.DEFAULT || status == ConditionStatus.ACTIVE;
    }

    private static Optional<Progress> progress(ConditionApi.Definition condition, JsonObject diagnostic, ConditionStatus status) {
        if (status == ConditionStatus.CLAIMED || status == ConditionStatus.EXHAUSTED) return Optional.empty();
        if (diagnostic.has("current") && diagnostic.has("target")) {
            return Optional.of(new Progress(diagnostic.get("current").getAsInt(), diagnostic.get("target").getAsInt()));
        }
        return switch (condition) {
            case BuiltInConditions.ConsumeItems ignored -> Optional.empty();
            case CompositeConditions.Repeat value -> Optional.of(new Progress(diagnostic.get("iterations").getAsInt(), value.times()));
            case CompositeConditions.AllOf value -> Optional.of(new Progress(completedChildren(diagnostic), value.children().size()));
            case CompositeConditions.AnyOf ignored -> Optional.of(new Progress(diagnostic.get("completed").getAsBoolean() ? 1 : 0, 1));
            case CompositeConditions.Sequence value -> Optional.of(new Progress(completedChildren(diagnostic), value.children().size()));
            case CompositeConditions.TimeWindow value -> progress(value.child(), diagnostic.getAsJsonObject("child"), status);
            case CompositeConditions.NOfM value -> Optional.of(new Progress(Math.min(completedChildren(diagnostic), value.required()), value.required()));
            case CompositeConditions.OptionalChild value -> progress(value.child(), diagnostic.getAsJsonObject("child"), status);
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
            case BuiltInRewards.Money value -> bullet(value.formatedAmount());
            case BuiltInRewards.Experience value -> bullet(value.points() + " experience points");
            case BuiltInRewards.Item value -> {
                var stack = value.stackTemplate().create();
                yield styled("• " + stack.getCount() + "× ", ChatFormatting.DARK_GRAY)
                        .append(stack.getHoverName().copy().withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withItalic(false)));
            }
            case BuiltInRewards.ServerCommands commands -> bullet(commands.title());
            default -> bullet(humanize(reward.type().id()));
        };
    }

    private static MutableComponent questTitle(
            String title, ChatFormatting titleColor,
            QuestModel.AttemptStatus status, boolean unavailable
    ) {
        MutableComponent result = styled(title, titleColor, true);
        if (unavailable) return result.append(styled(" ✕", ChatFormatting.RED, true));
        return switch (status) {
            case ACTIVE -> result;
            case READY_TO_CLAIM -> result.append(styled(" !", statusColor(status), true));
            case CLAIM_PENDING -> result.append(styled(" ↻", statusColor(status), true));
            case CLAIMED -> result.append(styled(" ✓", statusColor(status), true));
            case EXHAUSTED -> result.append(styled(" ✕", statusColor(status), true));
        };
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

        /// Reuses the quest-giver details as a chat hover card.
        public Component hoverText() {
            return hoverText(actionHint);
        }

        public Component hoverText(@Nullable Component actionHint) {
            var hover = Component.empty().append(title);
            for (Component line : lines) {
                hover.append("\n").append(line);
            }
            if (actionHint != null) {
                hover.append("\n\n").append(actionHint);
            }
            return hover;
        }
    }

    private record Progress(int current, int target) {}

    private enum ConditionStatus {DEFAULT, ACTIVE, CLAIMED, EXHAUSTED}
}
