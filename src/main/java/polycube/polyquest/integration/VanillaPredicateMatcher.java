package polycube.polyquest.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.advancements.predicates.BlockPredicate;
import net.minecraft.advancements.predicates.DamageSourcePredicate;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import polycube.polyquest.PolyQuest;

/// Narrow compatibility boundary for invoking vanilla predicate matchers.
///
/// Predicate classes and codecs are kept directly in the domain model. Reflection is
/// confined here because Mojang has changed matcher method names and nullability shapes
/// between snapshots while keeping their serialized predicate contracts stable.
public final class VanillaPredicateMatcher {
    private static final Map<InvocationKey, Method> METHODS = new ConcurrentHashMap<>();
    private static final Method MISSING;

    static {
        try {
            MISSING = VanillaPredicateMatcher.class.getDeclaredMethod("missingMarker");
        } catch (NoSuchMethodException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    public static boolean item(ItemPredicate predicate, ItemStack stack) {
        return invoke(predicate, stack);
    }

    public static boolean entity(
            EntityPredicate predicate,
            ServerLevel level,
            Vec3 origin,
            Entity entity) {
        return invoke(predicate, level, origin, entity);
    }

    public static boolean location(
            LocationPredicate predicate,
            ServerLevel level,
            double x,
            double y,
            double z) {
        return invoke(predicate, level, x, y, z);
    }

    public static boolean damageSource(
            DamageSourcePredicate predicate,
            ServerLevel level,
            Vec3 origin,
            DamageSource source) {
        return invoke(predicate, level, origin, source);
    }

    public static boolean block(BlockPredicate predicate, ServerLevel level, BlockPos position) {
        return invoke(predicate, level, position);
    }

    private static boolean invoke(Object predicate, Object... arguments) {
        InvocationKey key = new InvocationKey(
                predicate.getClass(),
                Arrays.stream(arguments)
                        .map(value -> value == null ? Void.class : value.getClass())
                        .toList());
        Method method = METHODS.computeIfAbsent(key, ignored -> findMethod(predicate.getClass(), arguments));
        if (method == MISSING) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(predicate, arguments));
        } catch (IllegalAccessException | InvocationTargetException exception) {
            PolyQuest.LOGGER.error("Could not evaluate vanilla predicate {}", predicate.getClass().getName(), exception);
            return false;
        }
    }

    private static Method findMethod(Class<?> type, Object[] arguments) {
        for (String preferredName : List.of("matches", "test")) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(preferredName)
                        && method.getReturnType() == boolean.class
                        && accepts(method.getParameterTypes(), arguments)) {
                    return method;
                }
            }
        }
        PolyQuest.LOGGER.error(
                "No compatible matcher method exists on {} for {} argument(s)",
                type.getName(),
                arguments.length);
        return MISSING;
    }

    private static boolean accepts(Class<?>[] parameters, Object[] arguments) {
        if (parameters.length != arguments.length) {
            return false;
        }
        for (int index = 0; index < parameters.length; index++) {
            Object argument = arguments[index];
            if (argument == null) {
                if (parameters[index].isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> parameter = wrap(parameters[index]);
            if (!parameter.isAssignableFrom(argument.getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static void missingMarker() {
    }

    private record InvocationKey(Class<?> predicateClass, List<Class<?>> argumentTypes) {
    }

    private VanillaPredicateMatcher() {
    }
}
