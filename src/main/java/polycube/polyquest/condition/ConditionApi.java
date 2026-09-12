package polycube.polyquest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import polycube.polyquest.runtime.ConditionRuntime;

/// Public condition model and its data-driven type registry.
public final class ConditionApi {
    /// Immutable, shared condition configuration decoded from a quest resource.
    public interface Definition {
        Type<? extends Definition> type();
    }

    /// Connects a definition codec to the factory for its mutable runtime instance.
    public record Type<D extends Definition>(
            Identifier id,
            MapCodec<D> codec,
            Factory<D> factory) {
        public Type {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(factory, "factory");
        }
    }

    @FunctionalInterface
    public interface Factory<D extends Definition> {
        ConditionRuntime.Instance create(D definition, ConditionRuntime.CreationContext context);
    }

    private static final Map<Identifier, Type<?>> TYPES = new LinkedHashMap<>();

    public static synchronized <D extends Definition> Type<D> register(Type<D> type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Duplicate condition type " + type.id());
        }
        return type;
    }

    public static Type<?> get(Identifier id) {
        return TYPES.get(id);
    }

    public static Map<Identifier, Type<?>> types() {
        return Collections.unmodifiableMap(TYPES);
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
                type -> type.codec());
    }

    private ConditionApi() {}
}
