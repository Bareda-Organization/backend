package src.backend.location.repository.impl;

import src.backend.location.repository.spec.LocationRepository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import src.backend.location.dto.LocationPing;

/**
 * {@link LocationRepository} 의 in-memory 구현 — 학생 id → 최신 좌표를 프로세스 메모리에 보관.
 *
 * <p>{@link ConcurrentHashMap} 을 써서 Mock 시뮬레이터(쓰기)와 조회 요청(읽기)이 동시에 접근해도
 * 안전하다. 단일 서버·데모 용도이며, 재시작하면 사라진다(휘발성). 수평 확장이나 좌표 TTL 이
 * 필요해지면 같은 인터페이스를 구현한 Redis 저장소로 교체한다.
 */
@Repository
public class InMemoryLocationRepository implements LocationRepository {

    private final ConcurrentHashMap<Long, LocationPing> latestByStudent = new ConcurrentHashMap<>();

    @Override
    public void save(LocationPing ping) {
        latestByStudent.put(ping.studentId(), ping);
    }

    @Override
    public Optional<LocationPing> findLatest(Long studentId) {
        return Optional.ofNullable(latestByStudent.get(studentId));
    }
}
