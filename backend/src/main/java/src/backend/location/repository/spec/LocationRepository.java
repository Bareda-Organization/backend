package src.backend.location.repository.spec;

import java.util.Optional;

import src.backend.location.dto.LocationPing;

/**
 * 실시간 위치 저장소의 계약(인터페이스) — "학생별 최신 좌표 1건"을 보관한다.
 *
 * <p>실시간 위치는 초 단위로 갱신되는 휘발성 데이터라 관계형 DB(JPA)에 매 좌표를 쌓지 않는다.
 * MVP 는 프로세스 메모리({@code InMemoryLocationRepository})로 구현하고, 다중 인스턴스로
 * 확장하거나 TTL·pub/sub 이 필요해지면 Redis 구현체로 교체한다 — 서비스 계층은 이 계약만 안다.
 */
public interface LocationRepository {

    /** 학생의 최신 좌표를 저장(있으면 덮어쓴다). */
    void save(LocationPing ping);

    /** 학생의 최신 좌표. 아직 보고가 없으면 empty. */
    Optional<LocationPing> findLatest(Long studentId);
}
