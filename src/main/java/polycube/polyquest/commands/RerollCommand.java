package polycube.polyquest.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.model.QuestModel;

public final class RerollCommand extends PolyQuestCommand {
    public RerollCommand() {
        super(
                "reroll",
                "Rerolls one difficulty in today's daily quest rotation",
                "[difficulty]",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    protected int execute(CommandSourceStack source) throws CommandSyntaxException {
        return reroll(source, Arrays.stream(QuestModel.Difficulty.values()).map(QuestModel.Difficulty::getSerializedName).toList());
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(Commands.argument("difficulty", StringArgumentType.word())
                        .suggests((_, builder) -> SharedSuggestionProvider.suggest(
                                Arrays.stream(QuestModel.Difficulty.values()).map(QuestModel.Difficulty::getSerializedName), builder))
                        .executes(context -> reroll(
                                context.getSource(), List.of(StringArgumentType.getString(context, "difficulty")))));
    }

    private static int reroll(CommandSourceStack source, List<String> rawDifficulties) throws CommandSyntaxException {
        var optDifficulties = rawDifficulties.stream().map(QuestModel.Difficulty::byName).toList();
        if (optDifficulties.stream().anyMatch(Optional::isEmpty)) {
            source.sendFailure(CommandText.error("Expected easy, medium, or hard."));
            return 0;
        }

        //noinspection OptionalGetWithoutIsPresent
        var difficulties = optDifficulties.stream().map(Optional::get).toList();
        if (!CommandResult.require(PolyQuestApi.reroll(difficulties))) {
            source.sendFailure(CommandText.error("No daily quest is available for the " + difficulties.stream().map(QuestModel.Difficulty::getSerializedName).reduce((a, b) -> a + ", " + b).orElse("") + " difficulty slot(s)"));
            return 0;
        }

        var manager = CommandResult.require(PolyQuestApi.manager());
        var message = CommandText.success("Rerolled the " + difficulties.stream().map(QuestModel.Difficulty::getSerializedName).reduce((a, b) -> a + ", " + b).orElse("") + " daily slot(s)");
        for (var difficulty : difficulties) {
            var selected = manager.rotation().current().slots().get(difficulty);
            if (selected != null) {
                message.append(CommandText.field("Selected " + difficulty.getSerializedName(), CommandText.questDefinition(selected.definition())));
            }
        }
        message.append("\n").append(CommandText.action("[View quests]", "/" + PolyQuest.MOD_ID + " list"));
        source.sendSuccess(() -> message, true);
        return 1;
    }
}
