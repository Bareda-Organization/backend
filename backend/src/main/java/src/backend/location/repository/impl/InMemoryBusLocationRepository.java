package src.backend.location.repository.impl;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import src.backend.location.dto.BusLocationPing;
import src.backend.location.repository.spec.BusLocationRepository;

/**
 * {@link BusLocationRepository}의 in-memory 구현 — 버스 id → 최신 좌표를 프로세스 메모리에 보관.
 * {@link InMemoryLocationRepository}와 동일한 설계(단일 서버·데모 용도, 재시작 시 휘발)를 미러링한다(F1).
 */
@Repository
public class InMemoryBusLocationRepository implements BusLocationRepository {

    private final ConcurrentHashMap<Long, BusLocationPing> latestByBus = new ConcurrentHashMap<>();

    @Override
    public void save(BusLocationPing ping) {
        latestByBus.put(ping.busId(), ping);
    }

    @Override
    public Optional<BusLocationPing> findLatest(Long busId) {
        return Optional.ofNullable(latestByBus.get(busId));
    }
}
