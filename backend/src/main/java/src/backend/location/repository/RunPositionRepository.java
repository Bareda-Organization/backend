package src.backend.location.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.location.entity.RunPosition;

/**
 * {@link RunPosition} 영속성 접근(목표 3) — 지금은 적재(save)와 보존 정리 삭제만 필요하다. 그 외
 * 조회 메서드는 LOC-02(위치 이력 조회)가 실제로 쓰는 시점에 그 좌석이 더한다(YAGNI).
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
}
