package src.backend.location.repository.impl;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import src.backend.location.dto.BusLocationPing;
import src.backend.location.repository.spec.BusLocationRepository;

/**
 * {@link BusLocationRepository}의 in-memory 구현 — 버스 id → 최신 좌표를 프로세스 메모리에 보관.
 * {@link InMemoryLocationRepository}와 동일한 설계(단일 서버·데모 용도, 재시작 시 휘발)를 미러링한다(F1).
 *
 * <p>현재 실제로 주입되는 구현은 {@link RedisBusLocationRepository}다 — 그쪽이 {@code @Primary}라
 * 이 클래스를 대체한다. 학생 좌표의 {@link InMemoryLocationRepository}가 non-primary로 남아 있는
 * 선례를 따라, Redis 없이 도는 환경을 위한 대안 구현으로 유지한다.
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
