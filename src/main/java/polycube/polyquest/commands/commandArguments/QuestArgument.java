package polycube.polyquest.commands.commandArguments;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import polycube.polyquest.api.PolyQuestApi;

public class QuestArgument {
    /// Creates a Minecraft identifier argument suggested from the player's current occurrences.
    public static RequiredArgumentBuilder<CommandSourceStack, Identifier> questArgument(String argumentName) {
        return Commands.argument(argumentName, IdentifierArgument.id())
                .suggests((context, builder) -> {
                    if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
                        return builder.buildFuture();
                    }
                    return PolyQuestApi.availableQuests(player.nameAndId()).result()
                            .map(occurrences -> SharedSuggestionProvider.suggestResource(
                                    occurrences.stream()
                                            .map(occurrence -> occurrence.definition().id()),
                                    builder))
                            .orElseGet(builder::buildFuture);
                });
    }
}
