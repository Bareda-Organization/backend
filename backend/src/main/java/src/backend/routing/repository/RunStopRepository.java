package src.backend.routing.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 다음 미도착 승하차지 1건(근접 알림 NTF-04, API_SPEC §4.12 Ruling 207) — {@code Pageable} 의
     * limit 1 과 {@code seq} 오름차순이 "다음" 을 결정한다.
     *
     * <p>{@code stopId IS NOT NULL} 로 강제 경유지를 뺀다 — 경유지는 학생이 배정되지 않아 알릴
     * 보호자가 없다. {@code change <> 'skipped'} 로 건너뛴 정차 항목도 뺀다 — {@link RunStop
     * #markSkipped} 가 표시한 항목은 버스가 실제로 서지 않으므로 근접해도 "곧 도착합니다" 가 거짓이
     * 된다({@code change} 가 {@code null}(정상 배정)인 항목은 그대로 포함).
     *
     * <p>{@code routeVersionId} 근거는 {@link #findByRouteVersionIdAndStopId} 와 같다 — 근접 알림
     * 스케줄러가 {@code RunRepository} 로 이미 학원과 무관하게 골라낸 {@code run.id} 에서 파생된 값만
     * 넘긴다는 전제다.
     */
    @AcademyScopeExempt(reason = "routeVersionId 는 근접 알림 스케줄러가 RunRepository 로 이미 학원과 무관하게 골라낸 "
            + "run.id 에서 confirmed_route.current_version_id 로 얻은 값만 넘긴다는 전제다(findByRouteVersionIdAndStopId 와 "
            + "같은 근거) — 확정 배치가 findByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNullOrderByConfirmAtAsc 로 "
            + "전 학원을 대상으로 골라내는 것과 같은 형태")
    @Query("""
            SELECT rs FROM RunStop rs
            WHERE rs.routeVersionId = :routeVersionId
              AND rs.stopId IS NOT NULL
              AND rs.arrivedAt IS NULL
              AND (rs.change IS NULL OR rs.change <> src.backend.global.common.enums.ChangeType.SKIPPED)
            ORDER BY rs.seq ASC
            """)
    List<RunStop> findNextUnarrived(@Param("routeVersionId") Long routeVersionId, Pageable pageable);

    /**
     * 정차 항목 1건의 근접 알림을 <b>최초 1회</b>로 선점한다 — 영향받은 행 수로 성공 여부를 판정한다
     * (목표 15, Ruling 210 조건부 UPDATE 선점).
     *
     * <p><b>{@code WHERE proximity_notified_at IS NULL} 조건이 멱등성의 전부다</b>({@link
     * src.backend.run.repository.RunRepository#confirmIfIdle} 과 같은 근거) — 스케줄러 인스턴스 2개가
     * 같은 정차 항목을 동시에 판정해도 먼저 행 잠금을 얻은 쪽만 갱신하고, 나중 쪽은 커밋된 값을 다시
     * 읽어 조건이 거짓이 되어 0행을 갱신한다.
     *
     * <p><b>{@code REQUIRES_NEW} 를 쓰지 않는다</b> — 호출자({@code ProximityNotificationService})의
     * 트랜잭션에 그대로 참여해야 한다. 이 선점 뒤 이벤트 발행이 실패하면 참여한 트랜잭션이 롤백되며
     * 이 UPDATE 도 함께 취소되어 다음 틱에 다시 판정 대상이 된다 — 별도 트랜잭션이었다면 선점 표시만
     * 남고 알림은 영구히 나가지 않는다({@code RunRepository.confirmIfIdle} 과 같은 근거).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "findNextUnarrived 가 이미 학원과 무관하게 골라낸 정차 항목 id 하나를 조건부로 갱신하는 "
            + "단건 호출이다 — 그 조회가 이미 좁힌 대상이라 이 시점에 학원을 다시 물을 근거가 없다(RunRepository.confirmIfIdle 과 "
            + "같은 근거)")
    @Query("UPDATE RunStop rs SET rs.proximityNotifiedAt = :now WHERE rs.id = :id AND rs.proximityNotifiedAt IS NULL")
    int claimProximityNotice(@Param("id") Long id, @Param("now") OffsetDateTime now);
}
