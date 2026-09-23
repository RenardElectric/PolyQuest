package polycube.polyquest.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StrictJsonParser;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.model.QuestModel;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/// Immutable server-wide settings decoded through Mojang's codec infrastructure.
public record QuestConfig(
        long dailySeed, ZoneId timeZone,
        Map<QuestModel.Difficulty, Integer> rotationHours
) {
    public QuestConfig {
        var hours = new EnumMap<>(DEFAULT_ROTATION_HOURS);
        hours.putAll(rotationHours);
        hours.values().forEach(value -> {
            if (!validRotationHours(value)) throw new IllegalArgumentException("rotation hours must divide 24 or be a multiple of 24");
        });
        rotationHours = Collections.unmodifiableMap(hours);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long DEFAULT_DAILY_SEED = 0x504F4C5951554553L;
    private static final Map<QuestModel.Difficulty, Integer> DEFAULT_ROTATION_HOURS = Map.of(
            QuestModel.Difficulty.EASY, 12,
            QuestModel.Difficulty.MEDIUM, 24,
            QuestModel.Difficulty.HARD, 48
    );

    private static final Codec<ZoneId> ZONE_ID_CODEC = Codec.STRING.comapFlatMap(
            value -> {
                try {
                    return DataResult.success(ZoneId.of(value));
                } catch (DateTimeException exception) {
                    return DataResult.error(() -> "Invalid time zone '" + value + "'");
                }
            },
            ZoneId::getId);
    private static final Codec<Integer> ROTATION_HOURS_CODEC = Codec.INT.comapFlatMap(
            value -> validRotationHours(value)
                    ? DataResult.success(value)
                    : DataResult.error(() -> "rotation hours must be a positive divisor or multiple of 24"),
            Function.identity());

    private static boolean validRotationHours(int hours) {
        return hours > 0 && (24 % hours == 0 || hours % 24 == 0);
    }

    public int rotationHours(QuestModel.Difficulty difficulty) {
        return rotationHours.get(difficulty);
    }

    public static final Codec<QuestConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("dailySeed").forGetter(QuestConfig::dailySeed),
            ZONE_ID_CODEC.fieldOf("timeZone").forGetter(QuestConfig::timeZone),
            Codec.unboundedMap(QuestModel.Difficulty.CODEC, ROTATION_HOURS_CODEC).optionalFieldOf("rotationHours", DEFAULT_ROTATION_HOURS).forGetter(QuestConfig::rotationHours)
    ).apply(instance, QuestConfig::new));

    public static QuestConfig defaults() {
        return new QuestConfig(DEFAULT_DAILY_SEED, ZoneId.systemDefault(), DEFAULT_ROTATION_HOURS);
    }

    public static QuestConfig load(Path path) {
        QuestConfig config = defaults();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                JsonElement json = StrictJsonParser.parse(reader);
                config = CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(message -> PolyQuest.LOGGER.error(
                                "Could not decode PolyQuest config {}; using defaults: {}",
                                path, message)
                        )
                        .orElse(config);
            } catch (IOException | JsonParseException exception) {
                PolyQuest.LOGGER.error("Could not read {}; using defaults", path, exception);
            }
        }
        save(path, config);
        return config;
    }

    private static void save(Path path, QuestConfig config) {
        try {
            JsonElement json = CODEC.encodeStart(JsonOps.INSTANCE, config).getOrThrow(IllegalStateException::new);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(json, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            PolyQuest.LOGGER.error("Could not write PolyQuest config {}", path, exception);
        }
    }
}
