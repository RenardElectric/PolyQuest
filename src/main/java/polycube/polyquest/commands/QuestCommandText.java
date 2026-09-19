package polycube.polyquest.commands;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.model.QuestModel;

import java.time.Duration;
import java.time.Instant;

import static polycube.polycore.text.TextComponents.*;
import static polycube.polycore.text.TextCore.*;

/// Shared, vanilla-client-compatible chat formatting. Never styles a caller's component in place.
public final class QuestCommandText {
    private QuestCommandText() {}

    public static MutableComponent questDefinition(QuestModel.Definition quest) {
        return copy(quest.title(), quest.id().toString());
    }

    public static MutableComponent quest(QuestModel.Occurrence occurrence, QuestModel.AttemptStatus status, @Nullable NameAndId player) {
        return action(
                occurrence.definition().title(),
                "/" + PolyQuest.MOD_ID + " inspect " + occurrence.definition().id() + (player != null ? " " + player.name() : " "),
                questTooltip(occurrence, status).append("\n").append(muted("Click to inspect."))
        );
    }

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
            message.append("\n  ").append(Component.literal(quest.description().get(index)).withStyle(ChatFormatting.GRAY));
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

    public static MutableComponent dailyRotation() {
        return Component.literal("[PolyQuest] ").withStyle(ChatFormatting.GOLD).append("New daily quests are available at the Quest Giver.");
    }

    public static MutableComponent progressReset() {
        return Component.literal("[PolyQuest] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Some quests were updated, so your progress was reset.").withStyle(ChatFormatting.GRAY));
    }

    public static MutableComponent diagnostics(Identifier id, NameAndId player) {
        var diagnostic = PolyQuestApi.inspect(player, id);
        if (diagnostic.error().isPresent()) {
            return hover(Component.literal("[Diagnostics]"), error("Failed to retrieve diagnostics for quest " + id + " for player " + player.name() + ": " + diagnostic.error().get()));
        }
        return copy("[Diagnostics]", diagnostic.getOrThrow());
    }

    private static MutableComponent questTooltip(
            QuestModel.Occurrence occurrence,
            QuestModel.AttemptStatus status
    ) {
        QuestModel.Definition quest = occurrence.definition();
        var tooltip = Component.literal("").append(Component.literal(quest.title()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)).append("\n").append(muted(quest.id().toString()));
        int descriptionLines = Math.min(quest.description().size(), 4);
        for (int index = 0; index < descriptionLines; index++) {
            tooltip.append("\n").append(Component.literal(abbreviate(quest.description().get(index), 120)).withStyle(ChatFormatting.GRAY));
        }
        if (quest.description().size() > descriptionLines) {
            tooltip.append("\n").append(muted("…"));
        }
        tooltip.append(field("Status", status(status)))
                .append(field("Availability", value(availability(quest))))
                .append(field("Time", value(expiry(occurrence))))
                .append(field("Objective", value(quest.condition().type().id().toString())))
                .append(field("Rewards", value(rewards(quest))));
        return tooltip;
    }
}
