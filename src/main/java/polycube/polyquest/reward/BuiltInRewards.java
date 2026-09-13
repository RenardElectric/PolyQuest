package polycube.polyquest.reward;

import com.mojang.brigadier.ParseResults;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.math.BigDecimal;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import polycube.polyquest.PolyQuest;

/// Built-in money, item, experience, and server-command rewards.
public final class BuiltInRewards {
    private static final Codec<BigDecimal> DECIMAL_CODEC = Codec.STRING.comapFlatMap(
            value -> {
                try {
                    return com.mojang.serialization.DataResult.success(new BigDecimal(value));
                } catch (NumberFormatException exception) {
                    return com.mojang.serialization.DataResult.error(() -> "Invalid decimal '" + value + "'");
                }
            },
            BigDecimal::toPlainString);

    public record Money(BigDecimal amount) implements RewardApi.Definition {
        public static final MapCodec<Money> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                DECIMAL_CODEC.fieldOf("amount").forGetter(Money::amount)
        ).apply(instance, Money::new));

        public static final RewardApi.Type<Money> TYPE = new RewardApi.Type<>(PolyQuest.id("money"), CODEC, BuiltInRewards::grantMoney);

        @Override
        public RewardApi.Type<Money> type() {
            return TYPE;
        }
    }

    public record Item(ItemStackTemplate stackTemplate) implements RewardApi.Definition {
        public static final MapCodec<Item> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemStackTemplate.CODEC.fieldOf("stack").forGetter(Item::stackTemplate)
        ).apply(instance, Item::new));

        public static final RewardApi.Type<Item> TYPE = new RewardApi.Type<>(PolyQuest.id("item"), CODEC, BuiltInRewards::grantItem);

        @Override
        public RewardApi.Type<Item> type() {
            return TYPE;
        }
    }

    public record Experience(int points) implements RewardApi.Definition {
        public static final MapCodec<Experience> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.fieldOf("points").forGetter(Experience::points)
        ).apply(instance, Experience::new));

        public static final RewardApi.Type<Experience> TYPE = new RewardApi.Type<>(PolyQuest.id("experience"), CODEC, BuiltInRewards::grantExperience);

        @Override
        public RewardApi.Type<Experience> type() {
            return TYPE;
        }
    }

    public record ServerCommands(List<String> commands) implements RewardApi.Definition {
        public ServerCommands {
            commands = List.copyOf(commands);
        }

        public static final MapCodec<ServerCommands> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.listOf().fieldOf("commands").forGetter(ServerCommands::commands)
        ).apply(instance, ServerCommands::new));

        public static final RewardApi.Type<ServerCommands> TYPE = new RewardApi.Type<>(PolyQuest.id("commands"), CODEC, BuiltInRewards::grantCommands);

        @Override
        public RewardApi.Type<ServerCommands> type() {
            return TYPE;
        }
    }

    public static void register() {
        RewardApi.register(Money.TYPE);
        RewardApi.register(Item.TYPE);
        RewardApi.register(Experience.TYPE);
        RewardApi.register(ServerCommands.TYPE);
    }

    private static RewardApi.GrantResult grantMoney(Money reward, RewardApi.Context context) {
        if (reward.amount().signum() <= 0) {
            return RewardApi.GrantResult.permanentFailure("Money reward amount must be positive");
        }
        return RewardApi.economyGateway().deposit(context.playerId(), reward.amount(), context.idempotencyKey());
    }

    private static RewardApi.GrantResult grantItem(Item reward, RewardApi.Context context) {
        ServerPlayer player = context.onlinePlayer();
        if (player == null) {
            return RewardApi.GrantResult.retryLater("Player must be online for an item reward");
        }
        ItemStack stack = reward.stackTemplate().create();
        boolean inserted = player.getInventory().add(stack);
        if (!inserted || !stack.isEmpty()) {
            player.drop(stack, false, Prediction.SERVER_ONLY);
        }
        return RewardApi.GrantResult.success();
    }

    private static RewardApi.GrantResult grantExperience(Experience reward, RewardApi.Context context) {
        ServerPlayer player = context.onlinePlayer();
        if (player == null) {
            return RewardApi.GrantResult.retryLater("Player must be online for an experience reward");
        }
        if (reward.points() <= 0) {
            return RewardApi.GrantResult.permanentFailure("Experience reward must be positive");
        }
        player.giveExperiencePoints(reward.points());
        return RewardApi.GrantResult.success();
    }

    private static RewardApi.GrantResult grantCommands(ServerCommands reward, RewardApi.Context context) {
        ServerPlayer player = context.onlinePlayer();
        if (player == null) {
            return RewardApi.GrantResult.retryLater("Player must be online for command rewards");
        }

        CommandSourceStack source = context.server().createCommandSourceStack()
                .withSuppressedOutput()
                .withPermission(PermissionSet.ALL_PERMISSIONS);
        Commands commandManager = context.server().getCommands();
        for (String command : reward.commands()) {
            String expanded = command
                    .replace("{player}", player.getScoreboardName())
                    .replace("{uuid}", player.getUUID().toString());
            ParseResults<CommandSourceStack> parsed = commandManager.getDispatcher().parse(expanded, source);
            commandManager.performCommand(parsed, expanded);
        }
        return RewardApi.GrantResult.success();
    }

    private BuiltInRewards() {}
}
