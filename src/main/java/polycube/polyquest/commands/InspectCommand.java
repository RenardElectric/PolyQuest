package polycube.polyquest.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.model.QuestModel;

import java.util.Collection;
import java.util.List;

public final class InspectCommand extends PolyQuestCommand {
    public InspectCommand() {
        super(
                "inspect",
                "Shows a quest's objective, rewards, availability, and current progress",
                "<quest> [player]",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(questArgument("quest")
                        .executes(context -> inspect(
                                context.getSource(),
                                IdentifierArgument.getId(context, "quest"),
                                List.of(context.getSource().getPlayerOrException().nameAndId())))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> inspect(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "quest"),
                                        GameProfileArgument.getGameProfiles(context, "player")))));
    }

    private int inspect(CommandSourceStack source, Identifier id, Collection<NameAndId> players) throws CommandSyntaxException {
        int total = 0;
        for (var player : players) total += inspect(source, id, player);
        return total;
    }

    private int inspect(CommandSourceStack source, Identifier id, NameAndId player) throws CommandSyntaxException {
        var manager = CommandResult.require(PolyQuestApi.manager());
        QuestModel.Occurrence occurrence = CommandResult.require(PolyQuestApi.quest(player, id));
        var attempt = manager.attempt(player, occurrence);
        QuestModel.Definition quest = occurrence.definition();

        var message = CommandText.header(quest.title())
                .append(CommandText.questDescription(quest))
                .append(CommandText.field("ID", CommandText.copy(id.toString(), id.toString())))
                .append(CommandText.field("Status", CommandText.status(attempt.status())))
                .append(CommandText.field("Availability", CommandText.value(CommandText.availability(quest))))
                .append(CommandText.field("Time", CommandText.value(CommandText.expiry(occurrence))))
                .append(CommandText.field("Objective", CommandText.value(quest.condition().type().id())))
                .append(CommandText.field("Rewards", CommandText.value(CommandText.rewards(quest))))
                .append("\n\n")
                .append(CommandText.technicalDetails(occurrence))
                .append(" ")
                .append(CommandText.diagnostics(id, player));
        CommandText.appendStateAction(message, attempt.status(), id, player);
        message.append(" ").append(CommandText.resetQuest(id, player));
        source.sendSuccess(() -> message, false);
        return 1;
    }
}
