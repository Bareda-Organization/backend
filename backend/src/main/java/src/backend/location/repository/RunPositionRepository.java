package src.backend.location.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.location.entity.RunPosition;

/**
 * {@link RunPosition} 영속성 접근(목표 3) — 지금은 적재(save)만 필요하다. 조회 메서드는 LOC-02
 * (위치 이력 조회)가 실제로 쓰는 시점에 그 좌석이 더한다(YAGNI).
 */
public interface RunPositionRepository extends JpaRepository<RunPosition, Long> {
}
