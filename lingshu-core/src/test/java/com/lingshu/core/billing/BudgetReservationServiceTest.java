package com.lingshu.core.billing;

import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    private BudgetReservationService service(FakeStore store, FakeLedger ledger) {
        LingShuProperties properties = new LingShuProperties();
        return new BudgetReservationService(store, ledger, properties);
    }

    private static final class FakeStore implements BudgetReservationStore {
        private boolean allowReserve = true;
        private final List<String> reserves = new ArrayList<>();
        private final List<String> releases = new ArrayList<>();
        private final List<String> transitions = new ArrayList<>();

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
        public boolean release(String reservationId) {
            releases.add(reservationId);
            return true;
        }
    }
}
