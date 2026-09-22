package polycube.polyquest.commands;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.resources.Identifier;
import polycube.polyquest.api.PolyQuestApi;

final class QuestArgument {
    /// Creates an identifier argument suggested from the current global occurrences.
    static RequiredArgumentBuilder<CommandSourceStack, Identifier> questArgument(String argumentName) {
        return Commands.argument(argumentName, IdentifierArgument.id())
                .suggests((_, builder) ->
                        PolyQuestApi.availableQuests().result()
                                .map(occurrences -> SharedSuggestionProvider.suggestResource(
                                        occurrences.stream()
                                                .map(occurrence -> occurrence.definition().id()),
                                        builder))
                                .orElseGet(builder::buildFuture));
    }

    private QuestArgument() {}
}
