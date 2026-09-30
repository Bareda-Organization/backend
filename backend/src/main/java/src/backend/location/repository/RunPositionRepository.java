package src.backend.location.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.location.entity.RunPosition;

/**
 * {@link RunPosition} 영속성 접근(목표 3) — 적재(save) · 보존 정리 삭제 · Redis 장애 시 최신 행 대체 조회(BR-167).
 */
public interface RunPositionRepository extends JpaRepository<RunPosition, Long> {

    /**
     * 보존 정리 배치 후보 id(목표 6, Phase 14 T2) — {@code recorded_at} 기준으로 자른다(ERD §7.2
     * "run_position — 미확정 — 법정 요건 검토 대기", {@link src.backend.global.retention.RetentionPolicy}
     * 잠정값 참고).
     *
     * <p>전 학원의 만료 위치 기록이 대상이라 학원 조건을 걸지 않는다 — {@code run_position} 은 애초에
     * {@code academy_id} 컬럼이 부재하다(§4 FK 미설정, 회차 경유로만 학원을 알 수 있다).
     */
    @AcademyScopeExempt(reason = "보존 정리 배치(Phase 14 목표 6) — 전 학원의 만료 위치 기록 전건이 대상이고, "
            + "academy_id 컬럼 자체가 부재해 학원 조건을 걸 수단이 없다")
    @Query("select p.id from RunPosition p where p.recordedAt < :cutoff order by p.id")
    List<Long> findIdsForRetentionCleanup(@Param("cutoff") OffsetDateTime cutoff, Limit limit);

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
