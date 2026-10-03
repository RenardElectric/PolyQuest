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
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.api.PolyQuestApi;

import java.util.Collection;
import java.util.List;

public final class ResetCommand extends PolyCommand {
    public ResetCommand() {
        super(
                "reset",
                "Resets one of your quest attempts and its claim state",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(QuestArgument.questArgument("quest")
                        .executes(context -> reset(
                                context.getSource(),
                                IdentifierArgument.getId(context, "quest"),
                                List.of(context.getSource().getPlayerOrException().nameAndId())))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> reset(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "quest"),
                                        GameProfileArgument.getGameProfiles(context, "player")))));
    }

    private int reset(CommandSourceStack source, Identifier id, Collection<NameAndId> players) throws CommandSyntaxException {
        int total = 0;
        for (var player : players) total += reset(source, id, player);
        return total;
    }

    private int reset(CommandSourceStack source, Identifier id, NameAndId player) throws CommandSyntaxException {
        var manager = commandResult.require(PolyQuestApi.manager());
        var occurrence = commandResult.require(PolyQuestApi.quest(id));
        var previousStatus = manager.attempt(player, occurrence).status();
        commandResult.require(PolyQuestApi.reset(player, id));

        var message = textComponents.success("Reset ")
                .append(QuestCommandText.quest(manager, player, occurrence))
                .append(" for ").append(TextComponents.value(player.name())).append(".")
                .append(TextComponents.field("Previous status", QuestCommandText.status(previousStatus)));
        source.sendSuccess(() -> message, true);
        return 1;
    }
}
