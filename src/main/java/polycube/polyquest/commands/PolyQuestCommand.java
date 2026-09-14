package polycube.polyquest.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.api.PolyQuestApi;

import java.util.ArrayList;
import java.util.List;

public abstract class PolyQuestCommand {
    private final String name;
    private final String description;
    private final String usage;
    private final PermissionLevel permissionLevel;
    private final boolean hasQuickAlias;
    private final List<String> aliases;

    public PolyQuestCommand(String name, String description, String usage, PermissionLevel permissionLevel) {
        this(name, description, usage, permissionLevel, false);
    }

    public PolyQuestCommand(String name, String description, String usage, PermissionLevel permissionLevel, boolean hasQuickAlias) {
        this(name, description, usage, permissionLevel, hasQuickAlias, List.of());
    }

    public PolyQuestCommand(String name, String description, String usage, PermissionLevel permissionLevel, boolean hasQuickAlias, List<String> aliases) {
        this.name = name;
        this.description = description;
        this.usage = usage;
        this.permissionLevel = permissionLevel;
        this.hasQuickAlias = hasQuickAlias;
        this.aliases = aliases;
    }

    protected String getName() {
        return name;
    }

    protected String getDescription() {
        return description.endsWith(".") ? description : description + ".";
    }

    protected Component getFullDescription() {
        var message = CommandText.header("/" + PolyQuest.MOD_ID + " " + name)
                .append("\n" + getDescription());
        if (permissionLevel != PermissionLevel.ALL) message.append(CommandText.muted(" (Admin only)"));
        for (String variant : usage.split(" \\| ")) {
            String command = "/" + PolyQuest.MOD_ID + " " + name;
            message.append("\n  ").append(CommandText.action(
                    command + (variant.isBlank() ? "" : " " + variant),
                    command + (variant.isBlank() ? "" : " ")));
        }
        if (hasQuickAlias) {
            var shortcuts = new ArrayList<String>();
            shortcuts.add("/" + name);
            for (String alias : aliases) shortcuts.add("/" + alias);
            message.append(CommandText.field("Shortcuts", CommandText.value(String.join(", ", shortcuts))));
        }
        return message.append("\n<...> required • [...] optional.");
    }

    protected PermissionLevel getPermissionLevel() {
        return this.permissionLevel;
    }

    protected boolean hasQuickAlias() {
        return this.hasQuickAlias;
    }

    protected List<String> getAliases() {
        return this.aliases;
    }

    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return Commands.literal(name)
                .requires(source -> hasPermission(source, permissionLevel))
                .executes(e -> execute(e.getSource()))
                .then(Commands.literal("help").executes(e -> {
                    e.getSource().sendSuccess(this::getFullDescription, false);
                    return 1;
                }));

    }

    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name, CommandBuildContext buildContext) {
        return getCommand(name);
    }

    public List<LiteralArgumentBuilder<CommandSourceStack>> getCommands(CommandBuildContext buildContext) {
        var commands = new ArrayList<LiteralArgumentBuilder<CommandSourceStack>>();
        var aliases = new ArrayList<>(getAliases());
        aliases.add(name);
        for (String alias : aliases) {
            commands.add(getCommand(alias, buildContext));
        }
        return commands;
    }

    protected boolean hasPermission(CommandSourceStack source, PermissionLevel permissionLevel) {
        return source.permissions().hasPermission(new Permission.HasCommandLevel(permissionLevel));
    }

    /// Creates a Minecraft identifier argument suggested from the player's current occurrences.
    protected RequiredArgumentBuilder<CommandSourceStack, Identifier> questArgument(String argumentName) {
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

    protected int execute(CommandSourceStack source) throws CommandSyntaxException {
        source.sendFailure(CommandText.error("Incomplete command. Choose one of the forms below.")
                .append("\n").append(getFullDescription()));
        return 0;
    }
}
