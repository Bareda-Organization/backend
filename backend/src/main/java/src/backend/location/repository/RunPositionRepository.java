package src.backend.location.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.location.entity.RunPosition;

/**
 * {@link RunPosition} 영속성 접근(목표 3) — 적재(save) · Redis 장애 시 최신 행 대체 조회(BR-167) — 보존 정리는 일 단위 파티션 DROP 이라 여기 없다({@link src.backend.location.infrastructure.RunPositionPartitionManager}).
 */
public interface RunPositionRepository extends JpaRepository<RunPosition, Long> {

    /**
     * 회차별 최신 행 1건씩 — Redis 최신 좌표의 대체 재료(BR-167, TECH_DECISIONS §14.2). 여러 회차를 한 번에 읽어
     * 관제가 회차 수만큼 쿼리를 내지 않는다. 회차마다 인덱스 {@code ix_run_position_run_recorded (run_id, recorded_at DESC)} 의
     * 앞 1행만 읽는다(LATERAL + LIMIT 1) — {@code DISTINCT ON} 은 회차의 전 행을 정렬해 R46 조사 실측 18.0ms → 0.96ms.
     */
    @AcademyScopeExempt(reason = "Redis 최신 좌표의 대체 조회 — 호출자(RunPositionStore 의 소비자)가 이미 학원 범위로 "
            + "확인한 회차 id 만 넘기고, run_position 에는 academy_id 컬럼이 부재하다")
    @Query(value = """
            SELECT p.*
            FROM run r
            JOIN LATERAL (
                SELECT * FROM run_position
                WHERE run_id = r.id
                ORDER BY recorded_at DESC, id DESC
                LIMIT 1
            ) p ON true
            WHERE r.id IN (:runIds)
            """, nativeQuery = true)
    List<RunPosition> findLatestByRunIdIn(@Param("runIds") Collection<Long> runIds);
}
