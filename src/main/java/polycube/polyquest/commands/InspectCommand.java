package polycube.polyquest.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import polycube.polycore.commands.CommandResult;
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.presentation.ConditionText;
import polycube.polyquest.presentation.QuestDisplay;

import java.util.Collection;
import java.util.List;

public final class InspectCommand extends PolyCommand {
    public InspectCommand() {
        super(
                PolyQuest.MOD_ID,
                "inspect",
                "Shows a quest's objective, rewards, availability, and current progress",
                "<quest> [player]",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(QuestArgument.questArgument("quest")
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
        QuestModel.Occurrence occurrence = CommandResult.require(PolyQuestApi.quest(id));
        QuestModel.Definition quest = occurrence.definition();
        var display = QuestDisplay.format(manager, source.getServer(), player, occurrence);

        var message = TextComponents.message().append(display.title());
        for (Component line : display.lines()) {
            message.append("\n").append(line);
        }

        message.append("\n\n")
                .append(Component.literal("ADMIN DETAILS").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(TextComponents.field(
                        "Player",
                        TextComponents.value(player.name())
                                .append(" ")
                                .append(TextComponents.copy("[UUID]", player.id().toString()))))
                .append(TextComponents.field("Quest ID", TextComponents.copy(id.toString(), id.toString())))
                .append(TextComponents.field(
                        "Occurrence",
                        TextComponents.copy("[Copy key]", occurrence.key().persistentKey())))
                .append(TextComponents.field("Ledger claimed", TextComponents.yesNo(display.claimed())))
                .append(TextComponents.field(
                        "Objective",
                        TextComponents.hover(ConditionText.summary(source.getServer(), quest.condition()),
                                TextComponents.muted("Condition type: " + quest.condition().type().id()))))
                .append(TextComponents.field(
                        "Available from",
                        TextComponents.value(occurrence.availableFrom())))
                .append(TextComponents.field(
                        "Available until",
                        TextComponents.value(occurrence.availableUntil().map(Object::toString).orElse("Never"))))
                .append("\n\n")
                .append(Component.literal("ADMIN ACTIONS").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append("\n  ");

        if (display.status() == QuestModel.AttemptStatus.ACTIVE && !display.unavailable()) {
            message.append(TextComponents.action(
                    "[Try claim]",
                    "/" + PolyQuest.MOD_ID + " claim " + id + " " + player.name()));
        } else {
            QuestCommandText.appendStateAction(message, display.status(), id, player);
        }
        message.append(" ").append(QuestCommandText.resetQuest(id, player))
                .append("\n\n")
                .append(Component.literal("DEBUG TOOLS").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append("\n  ")
                .append(QuestCommandText.technicalDetails(occurrence))
                .append(" ")
                .append(QuestCommandText.diagnostics(id, player));
        source.sendSuccess(() -> message, false);
        return 1;
    }
}
