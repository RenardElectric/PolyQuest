package polycube.polyquest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/// Data-driven rewards and their runtime grant adapters.
public final class RewardApi {
    public interface Definition {
        Type<? extends Definition> type();
    }

    public record Type<D extends Definition>(Identifier id, MapCodec<D> codec, Granter<D> granter) {}

    @FunctionalInterface
    public interface Granter<D extends Definition> {
        GrantResult grant(D definition, Context context);
    }

    public record Context(MinecraftServer server, UUID playerId, @Nullable ServerPlayer onlinePlayer, String idempotencyKey) {}

    public record GrantResult(State state, String message) {
        public enum State {
            SUCCESS,
            RETRY_LATER,
            PERMANENT_FAILURE
        }

        public static GrantResult success() {
            return new GrantResult(State.SUCCESS, "");
        }

        public static GrantResult retryLater(String message) {
            return new GrantResult(State.RETRY_LATER, message);
        }

        public static GrantResult permanentFailure(String message) {
            return new GrantResult(State.PERMANENT_FAILURE, message);
        }

        public boolean successful() {
            return state == State.SUCCESS;
        }
    }

    /// Economy bridge implemented by the server's Common Economy API provider adapter.
    @FunctionalInterface
    public interface EconomyGateway {
        GrantResult deposit(UUID playerId, BigDecimal amount, String transactionId);
    }

    public record Plan(Optional<Identifier> profile, List<Definition> inlineRewards) {
        public static final Codec<Plan> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.optionalFieldOf("profile").forGetter(Plan::profile),
                RewardApi.codec().listOf().optionalFieldOf("rewards", List.of()).forGetter(Plan::inlineRewards)
        ).apply(instance, Plan::new));

        public Plan {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(inlineRewards, "inlineRewards");
            inlineRewards = List.copyOf(inlineRewards);
        }
    }

    public record Profile(Identifier id, List<Definition> rewards) {
        public Profile {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(rewards, "rewards");
            rewards = List.copyOf(rewards);
        }
    }

    private static final Map<Identifier, Type<?>> TYPES = new LinkedHashMap<>();
    private static EconomyGateway economyGateway = (player, amount, transaction) ->
            GrantResult.retryLater("No economy gateway has been registered");

    public static synchronized <D extends Definition> Type<D> register(Type<D> type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Duplicate reward type " + type.id());
        }
        return type;
    }

    public static synchronized void setEconomyGateway(EconomyGateway gateway) {
        economyGateway = Objects.requireNonNull(gateway, "gateway");
    }

    public static EconomyGateway economyGateway() {
        return economyGateway;
    }

    public static Map<Identifier, Type<?>> types() {
        return Collections.unmodifiableMap(TYPES);
    }

    /// Polymorphic reward codec whose `type` field selects a registered subtype.
    public static Codec<Definition> codec() {
        Codec<Type<?>> typeCodec = Identifier.CODEC.comapFlatMap(
                id -> {
                    Type<?> type = TYPES.get(id);
                    return type == null
                            ? DataResult.error(() -> "Unknown reward type '" + id + "'")
                            : DataResult.success(type);
                },
                Type::id);
        return typeCodec.dispatch(
                "type",
                Definition::type,
                Type::codec);
    }

    /// Dispatches a decoded reward through the granter registered with its type.
    @SuppressWarnings("unchecked")
    public static GrantResult grant(Definition definition, Context context) {
        Type<Definition> type = (Type<Definition>) definition.type();
        return type.granter().grant(definition, context);
    }

    private RewardApi() {}
}
