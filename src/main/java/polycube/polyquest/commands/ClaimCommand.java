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
import polycube.polycore.commands.CommandResult;
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.claim.QuestClaimService;
import polycube.polyquest.model.QuestModel;

import java.util.Collection;
import java.util.List;

public final class ClaimCommand extends PolyCommand {
    public ClaimCommand() {
        super(
                PolyQuest.MOD_ID,
                "claim",
                "Claims a completed quest or retries its pending reward",
                "<quest> [player]",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(QuestArgument.questArgument("quest")
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
        var result = CommandResult.require(PolyQuestApi.claim(player, id));
        var occurrence = manager.findOccurrence(id);
        var quest = occurrence.map(value -> QuestCommandText.quest(manager, player.nameAndId(), value)).orElseGet(() -> TextComponents.copy(id.toString(), id.toString()));

        if (result.successful()) {
            var message = TextComponents.success("Claimed ").append(quest);
            source.sendSuccess(() -> message, true);
            return 1;
        }
        if (result.state() == QuestClaimService.ClaimState.PENDING) {
            var message = TextComponents.warning("Reward delivery is pending for ")
                    .append(quest)
                    .append(TextComponents.field("Reason", TextComponents.value(result.message())))
                    .append("\n");
            QuestCommandText.appendStateAction(message, QuestModel.AttemptStatus.CLAIM_PENDING, id, player.nameAndId());
            source.sendSuccess(() -> message, true);
            return 1;
        }

        var message = TextComponents.error(result.message()).append(TextComponents.field("Quest", quest));
        source.sendFailure(message);
        return 0;
    }
}
