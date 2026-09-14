package polycube.polyquest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import polycube.polyquest.runtime.ConditionRuntime;

/// Public condition model and its data-driven type registry.
public final class ConditionApi {
    /// Immutable, shared condition configuration decoded from a quest resource.
    public interface Definition {
        Type<? extends Definition> type();
    }

    /// Connects a definition codec to its runtime factory and pure semantic behavior.
    public record Type<D extends Definition>(Identifier id, MapCodec<D> codec, Factory<D> factory, Semantics<D> semantics) {}

    /// Creates a runtime instance from a definition and context.
    @FunctionalInterface
    public interface Factory<D extends Definition> {
        ConditionRuntime.Instance create(D definition, ConditionRuntime.CreationContext context);
    }

    /// Computes intrinsic capabilities without consulting mutable player or server state.
    @FunctionalInterface
    public interface Semantics<D extends Definition> {
        Capabilities evaluate(D definition, SemanticLookup children);

        static <D extends Definition> Semantics<D> constant(Capabilities capabilities) {
            return (_, _) -> capabilities;
        }
    }

    /// Lets composite semantics recursively inspect arbitrary registered child types.
    @FunctionalInterface
    public interface SemanticLookup {
        Capabilities evaluate(Definition definition);
    }

    /// Describes how a condition participates in event progress and claim-time work.
    public record Capabilities(boolean canCompleteFromSignals, boolean canProgressFromSignals, boolean containsClaimCost) {
        public static final Capabilities SIGNAL_DRIVEN = new Capabilities(true, true, false);
        public static final Capabilities CLAIM_TIME_COST = new Capabilities(false, false, true);
    }

    private static final Map<Identifier, Type<?>> TYPES = new LinkedHashMap<>();

    public static synchronized <D extends Definition> Type<D> register(Type<D> type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Duplicate condition type " + type.id());
        }
        return type;
    }

    public static Optional<Type<?>> get(Identifier id) {
        return Optional.ofNullable(TYPES.get(id));
    }

    public static Map<Identifier, Type<?>> types() {
        return Collections.unmodifiableMap(TYPES);
    }

    /// Dispatches semantic analysis through the definition's type descriptor.
    public static Capabilities capabilities(Definition definition) {
        return capabilities(definition.type(), definition);
    }

    @SuppressWarnings("unchecked")
    private static <D extends Definition> Capabilities capabilities(Type<D> type, Definition definition) {
        return type.semantics().evaluate((D) definition, ConditionApi::capabilities);
    }

    /// Polymorphic condition codec. The selected subtype is stored in the `type` field.
    public static Codec<Definition> codec() {
        Codec<Type<?>> typeCodec = Identifier.CODEC.comapFlatMap(
                id -> {
                    Type<?> type = TYPES.get(id);
                    return type == null
                            ? DataResult.error(() -> "Unknown condition type '" + id + "'")
                            : DataResult.success(type);
                },
                Type::id);

        return typeCodec.dispatch(
                "type",
                Definition::type,
                Type::codec);
    }

    private ConditionApi() {}
}
