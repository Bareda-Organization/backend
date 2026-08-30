package src.backend.routing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.routing.entity.RunStop;

/**
 * {@link RunStop} 영속성 접근 — {@code run_stop} 은 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b>
 * 자원이다(ERD §6.1). 확정 배치(Phase 7)는 {@code saveAll()} 로 정차 항목을 한 번에 쌓기만 했으나,
 * Phase 8(②구간 승인 미리보기의 "재최적화 전" 상태)이 처음으로 그 순서를 다시 읽어야 해 조회
 * 메서드를 더한다.
 * 자원이다(ERD §6.1).
 */
public interface RunStopRepository extends JpaRepository<RunStop, Long> {

    /**
     * 노선 버전 1건의 정차 순서 전체(Phase 8, 승인 미리보기의 "재최적화 전" 스냅샷) — 이미 저장된
     * 값을 그대로 읽으므로 이 조회만으로는 노선 계산이 <b>한 번도 일어나지 않는다.</b>
     *
     * <p>{@code academy_id} 컬럼이 부재해 학원 조건을 붙일 자리가 <b>{@code RouteVersion} →
     * {@code ConfirmedRoute}(PK=runId) → {@code Run} 을 거치는 이중 부모 조인뿐</b>이다
     * (ERD §6.1 부모 경유). 호출부가 이미 {@code Run} 을 학원으로 조회해 뒀어도, 이 조회 자체가
     * 학원 조건을 갖도록 다시 건다 — 횡단 규칙 7(저장소 조회 규약)이 개별 조회마다 조건을 요구한다.
     */
    @Query("""
            SELECT rs FROM RunStop rs
            JOIN RouteVersion rv ON rv.id = rs.routeVersionId
            JOIN Run r ON r.id = rv.confirmedRouteId
            WHERE rs.routeVersionId = :routeVersionId
              AND r.academyId = :academyId
            ORDER BY rs.seq ASC
            """)
    List<RunStop> findAllByRouteVersionIdAndAcademyIdOrderBySeq(@Param("routeVersionId") Long routeVersionId,
            @Param("academyId") Long academyId);

    /**
     * ③구간 미등원 토글이 잔여 0명을 확인한 뒤 건너뛸 정차 항목 1건을 찾는다(API_SPEC §3.6 ③) —
     * {@code run_stop} 은 {@code route_version_id} 로 배포 버전을 가리키고 {@code confirmed_route.
     * current_version_id} 가 그 버전을 가리키므로, 호출부가 그 체인을 먼저 따라 {@code routeVersionId}

    /**
     * 를 구한 뒤 이 조회로 그 승하차지의 정차 항목을 특정한다.
     *
     * <p>{@code routeVersionId} 근거는 {@code boarding.repository.RunRiderRepository#findByRunIdAndStudentId}

    /**
     * 와 같다 — 호출부가 이미 학원 소속을 확인한 회차의 확정 노선 버전이라는 전제다.
     */
    @AcademyScopeExempt(reason = "routeVersionId 는 호출부가 이미 학원 소속을 확인한 회차의 확정 노선 버전이라는 전제다 — "
            + "RunRepository.findByIdAndAcademyId 로 회차를 먼저 학원 범위에 좁힌 뒤 confirmed_route.current_version_id 로 "
            + "얻은 값만 넘긴다는 전제(RunRiderRepository.findByRunIdAndStudentId 와 같은 근거)")
    Optional<RunStop> findByRouteVersionIdAndStopId(Long routeVersionId, Long stopId);
}
