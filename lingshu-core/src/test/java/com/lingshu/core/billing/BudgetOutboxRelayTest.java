package com.lingshu.core.billing;

import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BudgetOutboxRelayTest {

    @Test
    void publishesClaimedEventsAndMarksThemPublished() {
        BudgetOutboxEvent event = new BudgetOutboxEvent(
                1,
                "reservation-1",
                "CONFIRMED",
                "tenant-a",
                3,
                Instant.now(),
                "claim-1"
        );
        FakeStore store = new FakeStore(event);
        FakePublisher publisher = new FakePublisher();
        BudgetOutboxRelay relay = new BudgetOutboxRelay(store, publisher, new LingShuProperties());

        relay.relay();

        assertEquals(List.of(event), publisher.published);
        assertEquals(List.of(event), store.marked);
    }

    private static final class FakeStore implements BudgetOutboxStore {
        private final BudgetOutboxEvent event;
        private final List<BudgetOutboxEvent> marked = new ArrayList<>();

        private FakeStore(BudgetOutboxEvent event) {
            this.event = event;
        }

        @Override
        public List<BudgetOutboxEvent> claimUnpublished(int limit) {
            return List.of(event);
        }

        @Override
        public boolean markPublished(BudgetOutboxEvent event) {
            marked.add(event);
            return true;
        }
    }

    private static final class FakePublisher implements BudgetEventPublisher {
        private final List<BudgetOutboxEvent> published = new ArrayList<>();

        @Override
        public void publish(BudgetOutboxEvent event) {
            published.add(event);
        }
    }
}
