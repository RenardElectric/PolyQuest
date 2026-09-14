package polycube.polyquest.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.model.QuestModel;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class ListCommand extends PolyQuestCommand {
    public ListCommand() {
        super(
                "list",
                "Lists all available quests for the player.",
                "[player]",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    protected int execute(CommandSourceStack source) throws CommandSyntaxException {
        return list(source, List.of(source.getPlayerOrException().nameAndId()));
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(context -> list(
                                context.getSource(),
                                GameProfileArgument.getGameProfiles(context, "player"))));
    }

    private static int list(CommandSourceStack source, Collection<NameAndId> players) throws CommandSyntaxException {
        int total = 0;
        for (var player : players) total += list(source, player);
        return total;
    }

    private static int list(CommandSourceStack source, NameAndId player) throws CommandSyntaxException {
        var manager = CommandResult.require(PolyQuestApi.manager());
        List<QuestEntry> quests = CommandResult.require(PolyQuestApi.availableQuests(player)).stream()
                .sorted(Comparator.comparingInt(ListCommand::displayOrder).thenComparing(occurrence -> occurrence.definition().id().toString()))
                .map(occurrence -> new QuestEntry(occurrence, manager.attempt(player, occurrence).status()))
                .toList();
        long ready = quests.stream()
                .filter(quest -> quest.status() == QuestModel.AttemptStatus.READY_TO_CLAIM)
                .count();
        long claimed = quests.stream().filter(quest -> quest.status() == QuestModel.AttemptStatus.CLAIMED).count();
        var message = CommandText.header("Quests for " + player.name())
                .append(CommandText.field("Total", CommandText.value(quests.size())))
                .append(CommandText.field("Ready", CommandText.value(ready)))
                .append(CommandText.field("Claimed", CommandText.value(claimed)));
        if (quests.isEmpty()) {
            message.append("\n\n  ").append(CommandText.muted("No quests are currently available."));
        }
        for (QuestEntry quest : quests) {
            appendQuest(message, quest, player);
        }
        source.sendSuccess(() -> message, false);
        return 1;
    }

    /// Adds one compact quest row; the hover card carries objective and reward details.
    private static void appendQuest(MutableComponent message, QuestEntry entry, NameAndId player) {
        var occurrence = entry.occurrence();
        var id = occurrence.definition().id();
        message.append("\n  ")
                .append(CommandText.status(entry.status()))
                .append("  ")
                .append(CommandText.quest(occurrence, entry.status(), player))
                .append(CommandText.muted(" • " + CommandText.availability(occurrence.definition()) + " • " + CommandText.expiry(occurrence)));
        CommandText.appendStateAction(message, entry.status(), id, player);
    }

    private static int displayOrder(QuestModel.Occurrence occurrence) {
        if (occurrence.key().scope() instanceof QuestModel.DailyScope daily) {
            return daily.slot().ordinal();
        }
        return QuestModel.Difficulty.values().length;
    }

    private record QuestEntry(QuestModel.Occurrence occurrence, QuestModel.AttemptStatus status) {}
}
