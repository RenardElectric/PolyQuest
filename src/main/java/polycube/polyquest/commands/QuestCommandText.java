package polycube.polyquest.commands;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.presentation.ConditionText;
import polycube.polyquest.presentation.QuestDisplay;
import polycube.polyquest.runtime.QuestManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static polycube.polycore.text.TextComponents.*;
import static polycube.polycore.text.TextCore.*;
import static polycube.polycore.text.TextUtil.descriptionLines;

/// Shared, vanilla-client-compatible chat formatting. Never styles a caller's component in place.
public final class QuestCommandText {
    private static final int MAX_CLAIMABLE_NAMES = 4;

    private QuestCommandText() {}

    public static MutableComponent status(QuestModel.AttemptStatus status) {
        return colored(statusLabel(status), switch (status) {
            case ACTIVE -> ChatFormatting.AQUA;
            case READY_TO_CLAIM -> ChatFormatting.GREEN;
            case CLAIM_PENDING -> ChatFormatting.YELLOW;
            case CLAIMED -> ChatFormatting.GOLD;
            case EXHAUSTED -> ChatFormatting.RED;
        }).withStyle(ChatFormatting.BOLD);
    }

    public static String statusLabel(QuestModel.AttemptStatus status) {
        return switch (status) {
            case ACTIVE -> "Active";
            case READY_TO_CLAIM -> "Ready";
            case CLAIM_PENDING -> "Reward pending";
            case CLAIMED -> "Claimed";
            case EXHAUSTED -> "Exhausted";
        };
    }

    public static String availability(QuestModel.Definition quest) {
        if (quest.availability() == QuestModel.Availability.UNIQUE) {
            return "Unique";
        }
        return quest.difficulty()
                .map(difficulty -> "Daily • " + titleCase(difficulty.getSerializedName()))
                .orElse("Daily");
    }

    /// Matches the quest giver's daily-difficulty and unique-quest title colors.
    public static ChatFormatting questColor(QuestModel.Definition quest) {
        return quest.difficulty().map(difficulty -> switch (difficulty) {
            case EASY -> ChatFormatting.GREEN;
            case MEDIUM -> ChatFormatting.GOLD;
            case HARD -> ChatFormatting.RED;
        }).orElse(ChatFormatting.LIGHT_PURPLE);
    }

    public static MutableComponent quest(QuestManager manager, NameAndId player, QuestModel.Occurrence occurrence) {
        var server = manager.server();
        var isAdmin = server.getPlayerList().isOp(player);
        var details = QuestDisplay.format(manager, server, player, occurrence).hoverText(isAdmin ? TextComponents.muted("Click to inspect.") : null);
        var text = styled("[" + occurrence.definition().title() + "]", questColor(occurrence.definition()), true);
        var command = "/" + PolyQuest.MOD_ID + " inspect " + occurrence.definition().id() + " " + player.name();
        return isAdmin ? action(text, command, details) : hover(text, details);
    }

    public static String expiry(QuestModel.Occurrence occurrence) {
        if (occurrence.availableUntil().isEmpty()) {
            return "No expiry";
        }
        Duration remaining = Duration.between(Instant.now(), occurrence.availableUntil().get());
        if (remaining.isNegative() || remaining.isZero()) {
            return "Expired";
        }
        long minutes = Math.max(1L, remaining.toMinutes());
        long days = minutes / (24L * 60L);
        long hours = minutes % (24L * 60L) / 60L;
        long trailingMinutes = minutes % 60L;
        if (days > 0L) {
            return days + "d " + hours + "h left";
        }
        if (hours > 0L) {
            return hours + "h " + trailingMinutes + "m left";
        }
        return trailingMinutes + "m left";
    }

    public static String rewards(QuestModel.Definition quest) {
        if (quest.rewards().profile().isPresent()) {
            return "Profile " + quest.rewards().profile().get();
        }
        var rewards = quest.rewards().inlineRewards();
        if (rewards.isEmpty()) {
            return "None";
        }
        String types = rewards.stream()
                .map(reward -> reward.type().id().toString())
                .distinct()
                .reduce((first, second) -> first + ", " + second)
                .orElse("");
        return rewards.size() + " inline (" + types + ")";
    }

    public static void appendStateAction(MutableComponent message, QuestModel.AttemptStatus status, Identifier id, NameAndId player) {
        var command = "/" + PolyQuest.MOD_ID + " claim " + id + " " + player.name();
        if (status == QuestModel.AttemptStatus.READY_TO_CLAIM) {
            message.append(" ").append(action("[Claim]", command));
        } else if (status == QuestModel.AttemptStatus.CLAIM_PENDING) {
            message.append(" ").append(action("[Retry reward]", command));
        }
    }

    public static MutableComponent questDescription(QuestModel.Definition quest) {
        var message = Component.empty();
        int visibleLines = Math.min(quest.description().size(), 4);
        for (int index = 0; index < visibleLines; index++) {
            message.append("\n  ").append(colored(quest.description().get(index), ChatFormatting.GRAY));
        }
        if (quest.description().size() > visibleLines) {
            message.append("\n  ").append(muted("… " + (quest.description().size() - visibleLines) + " more line(s)"));
        }
        return message;
    }

    public static MutableComponent resetQuest(Identifier id, NameAndId player) {
        return action("[Reset]", "/" + PolyQuest.MOD_ID + " reset " + id + " " + player.name());
    }

    public static MutableComponent technicalDetails(QuestModel.Occurrence occurrence) {
        QuestModel.Definition quest = occurrence.definition();
        return copy("[Technical details]", "Quest: " + quest.id() + "\nOccurrence: " + occurrence.key().persistentKey());
    }

    public static MutableComponent questChanged() {
        return message().append("Quests changed.");
    }

    public static MutableComponent questCompleted(QuestManager manager, NameAndId player, QuestModel.Occurrence occurrence) {
        return message()
                .append(colored("Quest completed: ", ChatFormatting.GREEN))
                .append(quest(manager, player, occurrence))
                .append(colored(". Visit the Quest Giver to claim your reward.", ChatFormatting.GRAY));
    }

    public static MutableComponent questClaimed(QuestManager manager, NameAndId player, QuestModel.Occurrence occurrence) {
        return message()
                .append(colored("Successfully claimed quest: ", ChatFormatting.GREEN))
                .append(quest(manager, player, occurrence))
                .append(colored(".", ChatFormatting.GRAY));
    }

    /// Keeps the chat summary short while retaining the total count and quest-giver hover details.
    public static Optional<MutableComponent> unclaimedSummary(
            QuestManager manager, NameAndId player,
            List<QuestModel.Occurrence> ready
    ) {
        if (ready.isEmpty()) return Optional.empty();
        var message = message();
        message.append(colored(count(ready.size(), "quest") + " ready to claim: ", ChatFormatting.GREEN));
        int visible = Math.min(ready.size(), MAX_CLAIMABLE_NAMES);
        for (int index = 0; index < visible; index++) {
            if (index > 0) message.append(colored(", ", ChatFormatting.GRAY));
            var occurrence = ready.get(index);
            message.append(quest(manager, player, occurrence));
        }
        if (ready.size() > visible) {
            message.append(colored(", ... and " + (ready.size() - visible) + " more", ChatFormatting.GRAY));
        }
        return Optional.of(message.append(colored(". Visit the Quest Giver.", ChatFormatting.GRAY)));
    }

    public static MutableComponent diagnostics(Identifier id, NameAndId player) {
        var diagnostic = PolyQuestApi.inspect(player, id);
        if (diagnostic.error().isPresent()) {
            return hover(Component.literal("[Copy diagnostics]"), error("Failed to retrieve diagnostics for quest " + id + " for player " + player.name() + ": " + diagnostic.error().get()));
        }
        return copy("[Copy diagnostics]", diagnostic.getOrThrow(), muted("Click to copy full quest diagnostics."));
    }

    private static MutableComponent questTooltip(
            MinecraftServer server,
            QuestModel.Occurrence occurrence,
            QuestModel.AttemptStatus status
    ) {
        QuestModel.Definition quest = occurrence.definition();
        var tooltip = Component.empty().append(styled(quest.title(), ChatFormatting.GOLD, true))
                .append("\n").append(muted(quest.id().toString()));
        for (String line : descriptionLines(quest.description(), 120, 4)) {
            tooltip.append("\n").append(colored(line, ChatFormatting.GRAY));
        }
        tooltip.append(field("Status", status(status)))
                .append(field("Availability", value(availability(quest))))
                .append(field("Time", value(expiry(occurrence))))
                .append(field("Objective", ConditionText.summary(server, quest.condition())))
                .append(field("Rewards", value(rewards(quest))));
        return tooltip;
    }
}
