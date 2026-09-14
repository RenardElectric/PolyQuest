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
import polycube.polyquest.PolyQuest;
import polycube.polyquest.api.PolyQuestApi;

import java.util.Collection;
import java.util.List;

public final class ResetCommand extends PolyQuestCommand {
    public ResetCommand() {
        super(
                "reset",
                "Resets one of your quest attempts and its claim state",
                "<quest> [player]",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(questArgument("quest")
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

    private static int reset(CommandSourceStack source, Identifier id, NameAndId player) throws CommandSyntaxException {
        var manager = CommandResult.require(PolyQuestApi.manager());
        var occurrence = CommandResult.require(PolyQuestApi.quest(player, id));
        var previousStatus = manager.attempt(player, occurrence).status();
        CommandResult.require(PolyQuestApi.reset(player, id));

        var message = CommandText.success("Reset ")
                .append(CommandText.quest(occurrence, previousStatus, player))
                .append(" for ").append(CommandText.value(player.name())).append(".")
                .append(CommandText.field("Previous status", CommandText.status(previousStatus)))
                .append("\n").append(CommandText.action("[Inspect quest]", "/" + PolyQuest.MOD_ID + " inspect " + id + " " + player.name()));
        source.sendSuccess(() -> message, true);
        return 1;
    }
}
