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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Function;

/// Immutable server-wide settings decoded through Mojang's codec infrastructure.
public record QuestConfig(
        long dailySeed, ZoneId timeZone,
        boolean announceRotation, int pendingRewardRetrySeconds
) {
    public QuestConfig {
        if (pendingRewardRetrySeconds < 1) {
            throw new IllegalArgumentException("pendingRewardRetrySeconds must be positive");
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long DEFAULT_DAILY_SEED = 0x504F4C5951554553L;
    private static final int DEFAULT_RETRY_SECONDS = 30;

    private static final Codec<ZoneId> ZONE_ID_CODEC = Codec.STRING.comapFlatMap(
            value -> {
                try {
                    return DataResult.success(ZoneId.of(value));
                } catch (DateTimeException exception) {
                    return DataResult.error(() -> "Invalid time zone '" + value + "'");
                }
            },
            ZoneId::getId);
    private static final Codec<Integer> RETRY_SECONDS_CODEC = Codec.INT.comapFlatMap(
            value -> value >= 1
                    ? DataResult.success(value)
                    : DataResult.error(() -> "pendingRewardRetrySeconds must be positive"),
            Function.identity());

    public static final Codec<QuestConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("dailySeed").forGetter(QuestConfig::dailySeed),
            ZONE_ID_CODEC.fieldOf("timeZone").forGetter(QuestConfig::timeZone),
            Codec.BOOL.fieldOf("announceRotation").forGetter(QuestConfig::announceRotation),
            RETRY_SECONDS_CODEC.fieldOf("pendingRewardRetrySeconds").forGetter(QuestConfig::pendingRewardRetrySeconds)
    ).apply(instance, QuestConfig::new));

    public static QuestConfig defaults() {
        return new QuestConfig(
                DEFAULT_DAILY_SEED, ZoneId.systemDefault(),
                true, DEFAULT_RETRY_SECONDS
        );
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
