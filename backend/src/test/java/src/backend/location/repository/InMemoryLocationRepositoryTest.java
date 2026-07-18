package src.backend.location.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import src.backend.location.dto.LocationOrigin;
import src.backend.location.dto.LocationPing;
import src.backend.location.repository.impl.InMemoryLocationRepository;

/**
 * in-memory 위치 저장소 단위 테스트 — Spring 없이 "학생별 최신 1건" 동작을 검증한다.
 */
class InMemoryLocationRepositoryTest {

    private final InMemoryLocationRepository repository = new InMemoryLocationRepository();

    @Test
    void save_then_findLatest_returns_it() {
        LocationPing ping = new LocationPing(1L, 10L, 37.5, 127.0, LocalDateTime.now(), LocationOrigin.MOCK);

        repository.save(ping);

        assertEquals(Optional.of(ping), repository.findLatest(1L));
    }

    @Test
    void save_overwrites_previous_ping() {
        LocationPing older = new LocationPing(1L, 10L, 37.5, 127.0, LocalDateTime.now(), LocationOrigin.MOCK);
        LocationPing newer = new LocationPing(1L, 10L, 37.6, 127.1, LocalDateTime.now(), LocationOrigin.GPS);

        repository.save(older);
        repository.save(newer);

        assertEquals(Optional.of(newer), repository.findLatest(1L));
    }

    @Test
    void findLatest_absent_returns_empty() {
        assertTrue(repository.findLatest(999L).isEmpty());
    }
}
