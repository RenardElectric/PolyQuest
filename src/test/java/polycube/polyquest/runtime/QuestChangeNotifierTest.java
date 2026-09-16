package polycube.polyquest.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class QuestChangeNotifierTest {
    @Test
    void notifiesActiveListenersAndStopsAfterClose() {
        QuestChangeNotifier notifier = new QuestChangeNotifier();
        AtomicInteger calls = new AtomicInteger();
        QuestManager.Subscription subscription = notifier.addListener(calls::incrementAndGet);

        notifier.changed();
        subscription.close();
        notifier.changed();

        assertEquals(1, calls.get());
    }

    @Test
    void oneBrokenListenerDoesNotBlockTheOthers() {
        QuestChangeNotifier notifier = new QuestChangeNotifier();
        AtomicInteger calls = new AtomicInteger();
        notifier.addListener(() -> {
            throw new IllegalStateException("test failure");
        });
        notifier.addListener(calls::incrementAndGet);

        notifier.changed();

        assertEquals(1, calls.get());
    }
}
