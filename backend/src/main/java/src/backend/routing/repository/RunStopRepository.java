package src.backend.routing.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.routing.entity.RunStop;

/**
 * {@link RunStop} 영속성 접근 — {@code run_stop} 은 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b>
 * 자원이다(ERD §6.1). 확정 배치(Phase 7)는 {@code saveAll()} 로 정차 항목을 한 번에 쌓기만 하고 조회
 * 메서드를 아직 필요로 하지 않아 상속 메서드 외에 선언하지 않는다.
 */
public interface RunStopRepository extends JpaRepository<RunStop, Long> {
}
