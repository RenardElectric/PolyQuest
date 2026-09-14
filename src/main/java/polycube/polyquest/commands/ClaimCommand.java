package polycube.polyquest.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.claim.QuestClaimService;
import polycube.polyquest.model.QuestModel;

import java.util.Collection;
import java.util.List;

public final class ClaimCommand extends PolyQuestCommand {
    public ClaimCommand() {
        super(
                "claim",
                "Claims a completed quest or retries its pending reward",
                "<quest> [player]",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(questArgument("quest")
                        .executes(context -> claim(
                                context.getSource(),
                                IdentifierArgument.getId(context, "quest"),
                                List.of(context.getSource().getPlayerOrException())))
                        .then(Commands.argument("player", EntityArgument.players())
                                .executes(context -> claim(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "quest"),
                                        EntityArgument.getPlayers(context, "player")))));
    }

    private static int claim(CommandSourceStack source, Identifier id, Collection<ServerPlayer> players) throws CommandSyntaxException {
        int total = 0;
        for (var player : players) {
            total += claim(source, id, player);
        }
        return total;
    }

    private static int claim(CommandSourceStack source, Identifier id, ServerPlayer player) throws CommandSyntaxException {
        var manager = CommandResult.require(PolyQuestApi.manager());
        var occurrence = manager.engine().findOccurrence(player.getUUID(), id);
        var result = CommandResult.require(PolyQuestApi.claim(player, id));
        var quest = occurrence.map(value -> {
            var status = manager.attempt(player.nameAndId(), value).status();
            return CommandText.quest(value, status, player.nameAndId());
        }).orElseGet(() -> CommandText.copy(id.toString(), id.toString()));

        if (result.successful()) {
            var message = CommandText.success("Claimed ").append(quest);
            source.sendSuccess(() -> message, true);
            return 1;
        }
        if (result.state() == QuestClaimService.ClaimState.PENDING) {
            var message = CommandText.warning("Reward delivery is pending for ")
                    .append(quest)
                    .append(CommandText.field("Reason", CommandText.value(result.message())))
                    .append("\n");
            CommandText.appendStateAction(message, QuestModel.AttemptStatus.CLAIM_PENDING, id, player.nameAndId());
            source.sendSuccess(() -> message, true);
            return 1;
        }

        var message = CommandText.error(result.message()).append(CommandText.field("Quest", quest));
        source.sendFailure(message);
        return 0;
    }
}
