package polycube.polyquest.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polyquest.api.PolyQuestApi;

import java.util.Collection;
import java.util.List;

public final class SignalCommand extends PolyQuestCommand {
    public SignalCommand() {
        super(
                "signal",
                "Emits an explicit quest signal for yourself",
                "<id> [player]",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(Commands.argument("id", IdentifierArgument.id())
                        .executes(context -> signal(
                                context.getSource(),
                                IdentifierArgument.getId(context, "id"),
                                List.of(context.getSource().getPlayerOrException())))
                        .then(Commands.argument("player", EntityArgument.players())
                                .executes(context -> signal(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "id"),
                                        EntityArgument.getPlayers(context, "player")))));
    }

    private int signal(CommandSourceStack source, Identifier id, Collection<ServerPlayer> players) throws CommandSyntaxException {
        int total = 0;
        for (var player : players) total += signal(source, id, player);
        return total;
    }

    private static int signal(CommandSourceStack source, Identifier id, ServerPlayer player) throws CommandSyntaxException {
        var manager = CommandResult.require(PolyQuestApi.emit(player, id));
        var details = Component.literal("Player: " + player.getScoreboardName()
                + "\nServer tick: " + manager.server().getTickCount()
                + "\nClick to copy the signal ID.");
        var signal = CommandText.hover(CommandText.copy(id.toString(), id.toString()), details);
        source.sendSuccess(() -> CommandText.success("Emitted quest signal ").append(signal), true);
        return 1;
    }
}
