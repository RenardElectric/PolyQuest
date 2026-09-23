package polycube.polyquest.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import org.jspecify.annotations.Nullable;
import polycube.polycore.commands.CommandResult;
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.runtime.QuestManager;
import polycube.polyquest.rotation.DailyRotationService;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class RerollCommand extends PolyCommand {
    public RerollCommand() {
        super(
                PolyQuest.MOD_ID,
                "reroll",
                "Rerolls daily quests or selects a specific quest for one difficulty",
                "[difficulty] [quest_id]",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    protected int execute(CommandSourceStack source) throws CommandSyntaxException {
        return rerollRandom(source, Arrays.asList(QuestModel.Difficulty.values()));
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(Commands.argument("difficulty", StringArgumentType.word())
                        .suggests((_, builder) -> SharedSuggestionProvider.suggest(
                                Arrays.stream(QuestModel.Difficulty.values()).map(QuestModel.Difficulty::getSerializedName), builder))
                        .executes(context -> {
                            var difficulty = difficulty(context.getSource(), StringArgumentType.getString(context, "difficulty"));
                            return difficulty == null ? 0 : rerollRandom(context.getSource(), List.of(difficulty));
                        })
                        .then(Commands.argument("quest_id", IdentifierArgument.id())
                                .suggests((context, builder) -> {
                                    var difficulty = QuestModel.Difficulty.byName(StringArgumentType.getString(context, "difficulty"));
                                    if (difficulty.isEmpty()) return builder.buildFuture();
                                    var manager = PolyQuestApi.manager().result();
                                    return manager.map(questManager -> SharedSuggestionProvider.suggest(
                                            questManager.dailyQuestIds(difficulty.get()).stream().map(Identifier::toString), builder)
                                    ).orElseGet(builder::buildFuture);
                                })
                                .executes(context -> {
                                    var difficulty = difficulty(context.getSource(), StringArgumentType.getString(context, "difficulty"));
                                    return difficulty == null ? 0 : rerollSelected(context.getSource(), difficulty,
                                            IdentifierArgument.getId(context, "quest_id"));
                                })));
    }

    private static QuestModel.@Nullable Difficulty difficulty(CommandSourceStack source, String raw) {
        var result = QuestModel.Difficulty.byName(raw);
        if (result.isEmpty()) {
            source.sendFailure(TextComponents.error("Expected easy, medium, or hard."));
            return null;
        }
        return result.get();
    }

    private static int rerollRandom(CommandSourceStack source, List<QuestModel.Difficulty> difficulties) throws CommandSyntaxException {
        var manager = CommandResult.require(PolyQuestApi.manager());
        var changed = manager.reroll(difficulties);
        if (changed.isEmpty()) {
            source.sendFailure(TextComponents.error("No eligible daily quests are available for " + difficultyNames(Set.copyOf(difficulties)) + "."));
            return 0;
        }

        var message = TextComponents.success("Rerolled daily slots: " + difficultyNames(changed));
        for (var difficulty : QuestModel.Difficulty.values()) {
            if (!changed.contains(difficulty)) continue;
            var selected = manager.dailyAssignment().slots().get(difficulty);
            if (selected != null) {
                message.append(TextComponents.field("Selected " + difficulty.getSerializedName(), QuestCommandText.questDefinition(selected.definition())));
            }
        }
        message.append("\n").append(TextComponents.action("[View quests]", "/" + PolyQuest.MOD_ID + " list"));
        source.sendSuccess(() -> message, true);
        return 1;
    }

    private static String difficultyNames(Set<QuestModel.Difficulty> difficulties) {
        return String.join(", ", Arrays.stream(QuestModel.Difficulty.values())
                .filter(difficulties::contains).map(QuestModel.Difficulty::getSerializedName).toList());
    }

    private static int rerollSelected(CommandSourceStack source, QuestModel.Difficulty difficulty, Identifier questId) throws CommandSyntaxException {
        var manager = CommandResult.require(PolyQuestApi.manager());
        var result = manager.reroll(difficulty, questId);
        return switch (result) {
            case CHANGED -> {
                var selected = manager.dailyAssignment().slots().get(difficulty);
                source.sendSuccess(() -> TextComponents.success("Selected " + difficulty.getSerializedName() + " daily quest")
                        .append(TextComponents.field("Quest", QuestCommandText.questDefinition(selected.definition()))), true);
                yield 1;
            }
            case ALREADY_SELECTED -> {
                source.sendSuccess(() -> Component.literal("That quest is already selected for " + difficulty.getSerializedName() + "."), false);
                yield 0;
            }
            case INVALID_QUEST -> {
                source.sendFailure(TextComponents.error("Quest " + questId + " is not an available " + difficulty.getSerializedName() + " daily quest."));
                yield 0;
            }
            case NO_CANDIDATES -> {
                source.sendFailure(TextComponents.error("No " + difficulty.getSerializedName() + " daily quests are available."));
                yield 0;
            }
        };
    }
}
