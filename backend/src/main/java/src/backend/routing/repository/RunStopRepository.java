package src.backend.routing.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.routing.dto.PositionStopView;
import src.backend.routing.entity.RunStop;
import src.backend.run.entity.RunStatus;

/**
 * {@link RunStop} 영속성 접근 — {@code run_stop} 은 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b>
 * 자원이다(ERD §6.1). 확정 배치(Phase 7)는 {@code saveAll()} 로 정차 항목을 한 번에 쌓기만 했으나,
 * Phase 8(②구간 승인 미리보기의 "재최적화 전" 상태)이 처음으로 그 순서를 다시 읽어야 해 조회
 * 메서드를 더한다.
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
     * 위치 수신(2초마다)용 — 그 회차의 <b>확정 노선 현재 판본</b> 정차 항목을 이름과 함께 한 문장으로 읽는다(R46-LATERBE L2). 확정 노선 ·
     * 정차 목록 · 정차 이름 세 조회를 합친 것이다. 확정 노선이 없으면 빈 목록이다. 이름은 승하차지({@code stop.name}) 또는
     * 경유지({@code waypoint.label}) — 학원 도착지 항목은 둘 다 없어 {@code null}. 학원 조건은 회차({@code Run}) 에 건다
     * ({@link #findAllByRouteVersionIdAndAcademyIdOrderBySeq} 와 같은 부모 경유 근거).
     */
    @Query("""
            SELECT new src.backend.routing.dto.PositionStopView(rs.seq, rs.arrivedAt, rs.eta, COALESCE(s.name, w.label))
            FROM Run r
            JOIN ConfirmedRoute cr ON cr.runId = r.id
            JOIN RunStop rs ON rs.routeVersionId = cr.currentVersionId
            LEFT JOIN Stop s ON s.id = rs.stopId
            LEFT JOIN Waypoint w ON w.id = rs.waypointId
            WHERE r.id = :runId AND r.academyId = :academyId
            ORDER BY rs.seq ASC
            """)
    List<PositionStopView> findPositionStops(@Param("runId") Long runId, @Param("academyId") Long academyId);

    /**
     * 노선 판본 여러 개의 정차 항목을 한 번에(BR-247) — {@link #findAllByRouteVersionIdAndAcademyIdOrderBySeq} 를 회차마다
     * 부르던 관제 조회용이다. 정렬은 {@code seq} 뿐이라 판본별 순서는 호출부가 판본 id 로 묶어 유지한다.
     */
    @Query("""
            SELECT rs FROM RunStop rs
            JOIN RouteVersion rv ON rv.id = rs.routeVersionId
            JOIN Run r ON r.id = rv.confirmedRouteId
            WHERE rs.routeVersionId IN :routeVersionIds
              AND r.academyId = :academyId
            ORDER BY rs.seq ASC
            """)
    List<RunStop> findAllByRouteVersionIdInAndAcademyId(@Param("routeVersionIds") Collection<Long> routeVersionIds,
            @Param("academyId") Long academyId);

    /**
     * 이 승하차지들 중 하나라도 <b>운행 중</b> 회차의 현재 노선에 서는가 — 서면 좌표를 고칠 수 없다
     * (ARCHITECTURE §8.5 운행 시작과 동시에 노선 잠금, BR-052). 근접 알림·출발 판정이 {@code stop} 좌표를
     * 매번 다시 읽어, 고치는 순간 운행 중인 버스의 판정 좌표가 바뀐다.
     *
     * <p>운행일이 {@code since} 보다 이른 회차는 세지 않는다(R46-KFIXBE K-1, Ruling 701) — 끝나지 않은 채 남은 옛 회차가
     * 그 노선 승하차지의 좌표 수정을 영구히 막지 않게 한다. {@code since} 는 {@code MovingRunWindowPolicy#earliestServiceDate}.
     */
    @Query("""
            SELECT COUNT(rs) > 0 FROM RunStop rs
            JOIN ConfirmedRoute cr ON cr.currentVersionId = rs.routeVersionId
            JOIN Run r ON r.id = cr.runId
            WHERE rs.stopId IN :stopIds
              AND r.status = :movingStatus
              AND r.serviceDate >= :since
              AND r.academyId = :academyId
            """)
    boolean existsOnMovingRun(@Param("stopIds") Collection<Long> stopIds, @Param("academyId") Long academyId,
            @Param("movingStatus") RunStatus movingStatus, @Param("since") LocalDate since);

    /**
     * ③구간 미등원 토글이 잔여 0명을 확인한 뒤 건너뛸 정차 항목 1건을 찾는다(API_SPEC §3.6 ③) —
     * {@code run_stop} 은 {@code route_version_id} 로 배포 버전을 가리키고 {@code confirmed_route.
     * current_version_id} 가 그 버전을 가리키므로, 호출부가 그 체인을 먼저 따라 {@code routeVersionId}
     * 를 구한 뒤 이 조회로 그 승하차지의 정차 항목을 특정한다.
     *
     * <p>{@code routeVersionId} 근거는 {@code boarding.repository.RunRiderRepository#findByRunIdAndStudentId}
     * 와 같다 — 호출부가 이미 학원 소속을 확인한 회차의 확정 노선 버전이라는 전제다.
     */
    @AcademyScopeExempt(reason = "routeVersionId 는 호출부가 이미 학원 소속을 확인한 회차의 확정 노선 버전이라는 전제다 — "
            + "RunRepository.findByIdAndAcademyId 로 회차를 먼저 학원 범위에 좁힌 뒤 confirmed_route.current_version_id 로 "
            + "얻은 값만 넘긴다는 전제(RunRiderRepository.findByRunIdAndStudentId 와 같은 근거)")
    Optional<RunStop> findByRouteVersionIdAndStopId(Long routeVersionId, Long stopId);

    /**
     * {@link #findByRouteVersionIdAndStopId} 와 같은 정차 항목을 <b>행 잠금</b>으로 읽는다(BR-229) — 같은 승하차지의 마지막 두
     * 명이 동시에 미승차 처리되면 서로의 미커밋 변경을 못 봐 둘 다 "아직 1명 남음" 으로 세고 어느 쪽도 건너뜀을 표시하지
     * 못했다. 잔여 판정 <b>앞</b>에서 이 잠금을 잡으면 뒤 요청은 앞 요청이 커밋한 뒤에 센다.
     */
    @AcademyScopeExempt(reason = "findByRouteVersionIdAndStopId 와 같은 근거 — 호출부가 학원 범위로 좁힌 회차의 확정 노선 "
            + "버전 id 만 넘긴다는 전제")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rs FROM RunStop rs WHERE rs.routeVersionId = :routeVersionId AND rs.stopId = :stopId")
    Optional<RunStop> findLockedByRouteVersionIdAndStopId(@Param("routeVersionId") Long routeVersionId,
            @Param("stopId") Long stopId);

    /**
     * 다음 미도착 승하차지 1건(근접 알림 NTF-04, API_SPEC §4.12 Ruling 207) — {@code Pageable} 의
     * limit 1 과 {@code seq} 오름차순이 "다음" 을 결정한다.
     *
     * <p>{@code stopId IS NOT NULL} 로 강제 경유지를 뺀다 — 경유지는 학생이 배정되지 않아 알릴
     * 보호자가 없다. {@code change <> 'skipped'} 로 건너뛴 정차 항목도 뺀다 — {@link RunStop
     * #markSkipped} 가 표시한 항목은 버스가 실제로 서지 않으므로 근접해도 "곧 도착합니다" 가 거짓이
     * 된다({@code change} 가 {@code null}(정상 배정)인 항목은 그대로 포함).
     *
     * <p>{@code departedAt IS NULL} 로 <b>이미 지나친 항목</b>도 뺀다(R51 H2 부작용) — 도착을 누르지 않은 채 다음 승하차지
     * 도착으로 강제 출발된 항목은 {@code arrived_at} 이 비고 {@code departed_at} 만 있다. 이를 "다음" 으로 계속 잡으면 버스가 이미
     * 지나친 곳을 기다리느라 그 뒤 승하차지의 "곧 도착" 알림이 멈춘다.
     *
     * <p>{@code routeVersionId} 근거는 {@link #findByRouteVersionIdAndStopId} 와 같다 — 근접 알림
     * 스케줄러가 {@code RunRepository} 로 이미 학원과 무관하게 골라낸 {@code run.id} 에서 파생된 값만
     * 넘긴다는 전제다.
     */
    @AcademyScopeExempt(reason = "routeVersionId 는 근접 알림 스케줄러가 RunRepository 로 이미 학원과 무관하게 골라낸 "
            + "run.id 에서 confirmed_route.current_version_id 로 얻은 값만 넘긴다는 전제다(findByRouteVersionIdAndStopId 와 "
            + "같은 근거) — 확정 배치가 findDueForConfirmation 로 "
            + "전 학원을 대상으로 골라내는 것과 같은 형태")
    @Query("""
            SELECT rs FROM RunStop rs
            WHERE rs.routeVersionId = :routeVersionId
              AND rs.stopId IS NOT NULL
              AND rs.arrivedAt IS NULL
              AND rs.departedAt IS NULL
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

    /**
     * 도착했지만 아직 출발 처리되지 않은 정차지 1건(Ruling 307, 출발 판정) — {@code Pageable} 의
     * limit 1 과 {@code seq} 오름차순이 "먼저 도착한 정차지를 먼저 판정" 순서를 정한다.
     *
     * <p>{@code stopId IS NOT NULL} 로 강제 경유지를 뺀다 — 경유지는 학생이 배정되지 않아 출발
     * 판정의 대상일 필요가 없다({@link #findNextUnarrived} 와 같은 근거).
     *
     * <p>{@code routeVersionId} 근거는 {@link #findByRouteVersionIdAndStopId} 와 같다 — 출발 판정
     * 스케줄러가 {@code RunRepository} 로 이미 학원과 무관하게 골라낸 {@code run.id} 에서 파생된 값만
     * 넘긴다는 전제다({@link #findNextUnarrived} 와 같은 근거).
     */
    @AcademyScopeExempt(reason = "routeVersionId 는 근접 알림과 같은 출발 판정 스케줄러가 RunRepository 로 이미 학원과 무관하게 "
            + "골라낸 run.id 에서 confirmed_route.current_version_id 로 얻은 값만 넘긴다는 전제다(findNextUnarrived 와 같은 근거)")
    @Query("""
            SELECT rs FROM RunStop rs
            WHERE rs.routeVersionId = :routeVersionId
              AND rs.stopId IS NOT NULL
              AND rs.arrivedAt IS NOT NULL
              AND rs.departedAt IS NULL
            ORDER BY rs.seq ASC
            """)
    List<RunStop> findFirstArrivedNotDeparted(@Param("routeVersionId") Long routeVersionId, Pageable pageable);

    /**
     * 아직 출발 처리되지 않은 정차지 <b>전부</b> — <b>도착 처리 여부와 무관</b>하다(Ruling 312, R51 H2). 운행 종료 시
     * 강제 적용(목표 8)이 "남은 전부"를 한 번에 쓸어야 해서 {@link #findFirstArrivedNotDeparted} 의 limit 1 로는 못 쓴다.
     * 기사가 도착을 누르지 않은 정차지도 포함하는 이유는 FEATURE_SPEC "위치 유실·기사의 도착 미처리로 출발 판정이 안
     * 되면 다음 승하차지 도착 시 강제 발송" 때문이다 — {@code arrived_at IS NOT NULL} 을 걸면 그 정차지의 승차·
     * 미승차 알림이 끝내 나가지 않는다. 강제 경유지·도착지는 학생이 없어 {@code stopId IS NOT NULL} 로 뺀다.
     */
    @AcademyScopeExempt(reason = "findFirstArrivedNotDeparted 와 같은 근거 — routeVersionId 는 호출부(StopDepartureService)가 "
            + "이미 학원과 무관하게 골라낸 회차에서 confirmed_route.current_version_id 로 얻은 값만 넘긴다는 전제다")
    @Query("""
            SELECT rs FROM RunStop rs
            WHERE rs.routeVersionId = :routeVersionId
              AND rs.stopId IS NOT NULL
              AND rs.departedAt IS NULL
            ORDER BY rs.seq ASC
            """)
    List<RunStop> findAllNotDeparted(@Param("routeVersionId") Long routeVersionId);

    /**
     * {@link #findAllNotDeparted} 중 <b>방금 도착 처리하는 정차 항목보다 앞 순번</b>만(R51 H2) — 다음 승하차지 도착이
     * 이전 정차지에 거는 폴백(목표 7)이다. 도착하는 항목 자신과 뒤 순번은 아직 버스가 지나지 않았으므로 뺀다.
     * {@code targetRunStopId} 가 그 버전의 항목이 아니면 하위 질의가 비어 아무 행도 돌려주지 않는다.
     */
    @AcademyScopeExempt(reason = "findAllNotDeparted 와 같은 근거 — routeVersionId 는 호출부가 학원 범위로 좁힌 회차의 "
            + "확정 노선 버전 id 만 넘긴다는 전제다")
    @Query("""
            SELECT rs FROM RunStop rs
            WHERE rs.routeVersionId = :routeVersionId
              AND rs.stopId IS NOT NULL
              AND rs.departedAt IS NULL
              AND rs.seq < (SELECT t.seq FROM RunStop t
                            WHERE t.id = :targetRunStopId AND t.routeVersionId = :routeVersionId)
            ORDER BY rs.seq ASC
            """)
    List<RunStop> findAllNotDepartedBefore(@Param("routeVersionId") Long routeVersionId,
            @Param("targetRunStopId") Long targetRunStopId);

    /**
     * 정차 항목 1건의 출발을 <b>최초 1회</b>로 선점한다(Ruling 307) — {@link #claimProximityNotice} 와
     * 같은 조건부 UPDATE 형태다. 근거도 같다 — {@code WHERE departed_at IS NULL} 조건이 멱등성의
     * 전부이고, 호출자({@code ProximityNotificationService})의 트랜잭션에 그대로 참여해 실패 시 함께
     * 롤백된다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "findFirstArrivedNotDeparted 가 이미 학원과 무관하게 골라낸 정차 항목 id 하나를 조건부로 "
            + "갱신하는 단건 호출이다 — 그 조회가 이미 좁힌 대상이라 이 시점에 학원을 다시 물을 근거가 없다"
            + "(claimProximityNotice 와 같은 근거)")
    @Query("UPDATE RunStop rs SET rs.departedAt = :now WHERE rs.id = :id AND rs.departedAt IS NULL")
    int claimDeparture(@Param("id") Long id, @Param("now") OffsetDateTime now);
}
