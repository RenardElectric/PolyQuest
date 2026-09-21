package polycube.polyquest.commands.commandArguments;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.resources.Identifier;
import polycube.polyquest.api.PolyQuestApi;

public class QuestArgument {
    /// Creates a Minecraft identifier argument suggested from the player's current occurrences.
    public static RequiredArgumentBuilder<CommandSourceStack, Identifier> questArgument(String argumentName) {
        return Commands.argument(argumentName, IdentifierArgument.id())
                .suggests((_, builder) ->
                        PolyQuestApi.availableQuests().result()
                                .map(occurrences -> SharedSuggestionProvider.suggestResource(
                                        occurrences.stream()
                                                .map(occurrence -> occurrence.definition().id()),
                                        builder))
                                .orElseGet(builder::buildFuture));
    }
}
