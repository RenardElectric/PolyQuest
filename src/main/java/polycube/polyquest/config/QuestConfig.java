package polycube.polyquest.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import polycube.polyquest.PolyQuest;

/// Small server-wide settings that do not belong in quest datapacks.
public final class QuestConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public long dailySeed = 0x504F4C5951554553L;
    public String timeZone = ZoneId.systemDefault().getId();
    public double claimRadius = 12.0;
    public boolean announceRotation = true;
    public int pendingRewardRetrySeconds = 30;

    public static QuestConfig load(Path path) {
        QuestConfig config = new QuestConfig();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                QuestConfig decoded = GSON.fromJson(reader, QuestConfig.class);
                if (decoded != null) {
                    config = decoded;
                }
            } catch (IOException | RuntimeException exception) {
                PolyQuest.LOGGER.error("Could not read {}; using defaults", path, exception);
            }
        }
        config.validate();
        config.save(path);
        return config;
    }

    public ZoneId zoneId() {
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            return ZoneId.systemDefault();
        }
    }

    private void validate() {
        try {
            ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            PolyQuest.LOGGER.warn("Invalid PolyQuest time zone '{}'; using {}", timeZone, ZoneId.systemDefault());
            timeZone = ZoneId.systemDefault().getId();
        }
        if (!Double.isFinite(claimRadius) || claimRadius < 0.0) {
            claimRadius = 12.0;
        }
        if (pendingRewardRetrySeconds < 1) {
            pendingRewardRetrySeconds = 30;
        }
    }

    private void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            PolyQuest.LOGGER.error("Could not write PolyQuest config {}", path, exception);
        }
    }
}
