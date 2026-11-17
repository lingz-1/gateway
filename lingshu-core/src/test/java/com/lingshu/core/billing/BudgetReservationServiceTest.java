package com.lingshu.core.billing;

import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.common.dto.TokenUsageSource;
import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetReservationServiceTest {

    @Test
    void reservesAndRecordsPendingReservation() {
        FakeStore store = new FakeStore();
        FakeLedger ledger = new FakeLedger();
        BudgetReservationService service = service(store, ledger);

        BudgetReservation reservation = service.reserve("tenant-a", "r-1", 3);

        assertEquals("r-1", reservation.reservationId());
        assertEquals(BudgetReservationStatus.PENDING, reservation.status());
        assertEquals(List.of("tenant-a:r-1:3"), store.reserves);
        assertEquals(List.of("r-1"), ledger.records);
    }

    @Test
    void rejectsWhenRedisReservationFails() {
        FakeStore store = new FakeStore();
        store.allowReserve = false;
        FakeLedger ledger = new FakeLedger();

        assertThrows(
                BudgetExceededException.class,
                () -> service(store, ledger).reserve("tenant-a", "r-1", 3)
        );
        assertTrue(ledger.records.isEmpty());
    }

    @Test
    void releasesRedisReservationWhenLedgerWriteFails() {
        FakeStore store = new FakeStore();
        FakeLedger ledger = new FakeLedger();
        ledger.failRecord = true;

        assertThrows(
                IllegalStateException.class,
                () -> service(store, ledger).reserve("tenant-a", "r-1", 3)
        );
        assertEquals(List.of("r-1"), store.releases);
    }

    @Test
    void confirmsAndReleasesThroughLedgerThenRedis() {
        FakeStore store = new FakeStore();
        FakeLedger ledger = new FakeLedger();
        BudgetReservationService service = service(store, ledger);

        assertTrue(service.confirm("r-1"));
        assertTrue(service.release("r-2"));
        assertEquals(List.of("r-1"), ledger.confirms);
        assertEquals(List.of("r-2"), ledger.releases);
        assertEquals(List.of("r-1", "r-2"), store.transitions);
    }

    @Test
    void settlesActualUsageAndReleasesDifference() {
        FakeStore store = new FakeStore();
        FakeLedger ledger = new FakeLedger();
        ledger.settlement = settlement("r-1", 100, 60, TokenUsageSource.PROVIDER_REPORTED);

        BudgetSettlement result = service(store, ledger).settle(
                "r-1", 60, TokenUsageSource.PROVIDER_REPORTED
        );

        assertEquals(40, result.releasedUnits());
        assertEquals(List.of("r-1:60:PROVIDER_REPORTED"), store.settlements);
        assertEquals(List.of("r-1:60:PROVIDER_REPORTED"), ledger.settlements);
    }

    @Test
    void settlesOverageAndPreservesEstimatedUsageSource() {
        FakeStore store = new FakeStore();
        FakeLedger ledger = new FakeLedger();
        ledger.settlement = settlement("r-1", 100, 120, TokenUsageSource.ESTIMATED);
        ProviderResponse response = new ProviderResponse(
                "stub", "stub-echo-v1", "answer", 70, 50,
                TokenUsageSource.ESTIMATED, "stop", List.of()
        );

        BudgetSettlement result = service(store, ledger).settle("r-1", response);

        assertTrue(result.overage());
        assertEquals(-20, result.releasedUnits());
        assertEquals(TokenUsageSource.ESTIMATED, result.usageSource());
    }

    @Test
    void databaseFailureDoesNotChangeRedisBalance() {
        FakeStore store = new FakeStore();
        FakeLedger ledger = new FakeLedger();
        ledger.failSettlement = true;

        assertThrows(
                IllegalStateException.class,
                () -> service(store, ledger).settle("r-1", 60, TokenUsageSource.MIXED)
        );
        assertTrue(store.settlements.isEmpty());
    }

    @Test
    void retriesRedisAfterLedgerAlreadyPersistedSettlement() {
        FakeStore store = new FakeStore();
        store.failSettlement = true;
        FakeLedger ledger = new FakeLedger();
        ledger.settlement = settlement("r-1", 100, 60, TokenUsageSource.MIXED);
        BudgetReservationService service = service(store, ledger);

        assertThrows(
                IllegalStateException.class,
                () -> service.settle("r-1", 60, TokenUsageSource.MIXED)
        );
        store.failSettlement = false;

        BudgetSettlement recovered = service.settle("r-1", 60, TokenUsageSource.MIXED);

        assertEquals(40, recovered.releasedUnits());
        assertEquals(2, ledger.settlements.size());
        assertEquals(2, store.settlements.size());
    }

    @Test
    void retriesRedisReleaseAfterLedgerAlreadyPersistedRelease() {
        FakeStore store = new FakeStore();
        FakeLedger ledger = new FakeLedger();
        ledger.allowRelease = false;

        assertTrue(service(store, ledger).release("r-release"));

        assertEquals(List.of("r-release"), ledger.releases);
        assertEquals(List.of("r-release"), store.releases);
    }

    private BudgetSettlement settlement(
            String reservationId,
            long reserved,
            long actual,
            TokenUsageSource source
    ) {
        return new BudgetSettlement(
                reservationId, "tenant-a", reserved, actual, reserved - actual, source
        );
    }

    private BudgetReservationService service(FakeStore store, FakeLedger ledger) {
        LingShuProperties properties = new LingShuProperties();
        return new BudgetReservationService(store, ledger, properties);
    }

    private static final class FakeStore implements BudgetReservationStore {
        private boolean allowReserve = true;
        private final List<String> reserves = new ArrayList<>();
        private final List<String> releases = new ArrayList<>();
        private final List<String> transitions = new ArrayList<>();
        private final List<String> settlements = new ArrayList<>();
        private boolean failSettlement;

        @Override
        public boolean reserve(String tenantId, String reservationId, long units) {
            reserves.add(tenantId + ":" + reservationId + ":" + units);
            return allowReserve;
        }

        @Override
        public boolean confirm(String reservationId) {
            transitions.add(reservationId);
            return true;
        }

        @Override
        public boolean settle(String reservationId, long actualUnits, TokenUsageSource usageSource) {
            settlements.add(reservationId + ":" + actualUnits + ":" + usageSource);
            return !failSettlement;
        }

        @Override
        public boolean release(String reservationId) {
            releases.add(reservationId);
            transitions.add(reservationId);
            return true;
        }
    }

    private static final class FakeLedger implements BudgetLedger {
        private boolean failRecord;
        private final List<String> records = new ArrayList<>();
        private final List<String> confirms = new ArrayList<>();
        private final List<String> releases = new ArrayList<>();
        private final List<String> settlements = new ArrayList<>();
        private BudgetSettlement settlement;
        private boolean failSettlement;
        private boolean allowRelease = true;

        @Override
        public void recordReservation(BudgetReservation reservation) {
            if (failRecord) {
                throw new IllegalStateException("ledger unavailable");
            }
            records.add(reservation.reservationId());
        }

        @Override
        public boolean confirm(String reservationId) {
            confirms.add(reservationId);
            return true;
        }

        @Override
        public Optional<BudgetSettlement> settle(
                String reservationId,
                long actualUnits,
                TokenUsageSource usageSource
        ) {
            settlements.add(reservationId + ":" + actualUnits + ":" + usageSource);
            if (failSettlement) {
                throw new IllegalStateException("database unavailable");
            }
            return Optional.ofNullable(settlement);
        }

        @Override
        public boolean release(String reservationId) {
            releases.add(reservationId);
            return allowRelease;
        }
    }
}
