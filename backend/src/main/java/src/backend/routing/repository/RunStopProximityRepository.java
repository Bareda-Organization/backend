package src.backend.routing.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.routing.entity.RunStop;

/**
 * {@link RunStop} 중 근접 알림·출발 판정(위치 수신 쪽)만 쓰는 조회와 선점 — 판본 묶음에서 판정 대상 정차 항목을 고르고,
 * 정차 항목 1건의 근접 알림·출발 관측을 조건부 UPDATE 로 최초 1회 선점한다. 다른 모듈이 부르는 조회는
 * {@link RunStopRepository} 에 있다.
 */
public interface RunStopProximityRepository extends JpaRepository<RunStop, Long> {

    /**
     * 판본마다 {@code seq} 가 가장 작은 미도착 정차 항목 하나씩(근접 알림 NTF-04, API_SPEC §4.12 Ruling 207 · DA-1, Ruling 899) — 판본
     * 목록을 한 번에 읽어 근접 판정이 틱마다 회차 수만큼 쿼리를 내지 않게 한다. 같은 조건을 서브쿼리로도 걸어 판본마다 첫 항목만 남긴다.
     *
     * <p>{@code stopId IS NOT NULL} 로 강제 경유지를 뺀다 — 경유지는 학생이 배정되지 않아 알릴 보호자가 없다. {@code change <> 'skipped'}
     * 로 건너뛴 정차 항목도 뺀다 — {@link RunStop#markSkipped} 가 표시한 항목은 버스가 실제로 서지 않으므로 근접해도 "곧 도착합니다" 가
     * 거짓이 된다({@code change} 가 {@code null}(정상 배정)인 항목은 그대로 포함).
     *
     * <p>{@code departedAt IS NULL} 로 <b>이미 지나친 항목</b>도 뺀다 — 도착을 누르지 않은 채 다음 승하차지 도착으로 강제 출발된 항목은
     * {@code arrived_at} 이 비고 {@code departed_at} 만 있다. 이를 "다음" 으로 계속 잡으면 버스가 이미 지나친 곳을 기다리느라 그 뒤
     * 승하차지의 "곧 도착" 알림이 멈춘다.
     *
     * <p>{@code routeVersionIds} 근거는 {@link RunStopRepository#findByRouteVersionIdAndStopId} 와 같다 — 근접 알림 스케줄러가 {@code RunRepository} 로
     * 이미 학원과 무관하게 골라낸 {@code run.id} 에서 파생된 값만 넘긴다는 전제다.
     */
    @AcademyScopeExempt(reason = "RunStopRepository.findByRouteVersionIdAndStopId 와 같은 근거 — 판본 id 는 근접 알림 스케줄러가 RunRepository 로 이미 학원과 무관하게 "
            + "골라낸 run.id 에서 confirmed_route.current_version_id 로 얻은 값만 넘긴다는 전제다")
    @Query("""
            SELECT rs FROM RunStop rs
            WHERE rs.routeVersionId IN :routeVersionIds
              AND rs.stopId IS NOT NULL
              AND rs.arrivedAt IS NULL
              AND rs.departedAt IS NULL
              AND (rs.change IS NULL OR rs.change <> src.backend.global.common.enums.ChangeType.SKIPPED)
              AND rs.seq = (SELECT MIN(x.seq) FROM RunStop x
                            WHERE x.routeVersionId = rs.routeVersionId
                              AND x.stopId IS NOT NULL
                              AND x.arrivedAt IS NULL
                              AND x.departedAt IS NULL
                              AND (x.change IS NULL OR x.change <> src.backend.global.common.enums.ChangeType.SKIPPED))
            """)
    List<RunStop> findNextUnarrivedOfVersions(@Param("routeVersionIds") Collection<Long> routeVersionIds);

    /**
     * 정차 항목 1건의 근접 알림을 <b>최초 1회</b>로 선점한다 — 영향받은 행 수로 성공 여부를 판정한다
     * (Ruling 210 조건부 UPDATE 선점).
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
    @AcademyScopeExempt(reason = "findNextUnarrivedOfVersions 가 이미 학원과 무관하게 골라낸 정차 항목 id 하나를 조건부로 갱신하는 "
            + "단건 호출이다 — 그 조회가 이미 좁힌 대상이라 이 시점에 학원을 다시 물을 근거가 없다(RunRepository.confirmIfIdle 과 "
            + "같은 근거)")
    @Query("UPDATE RunStop rs SET rs.proximityNotifiedAt = :now WHERE rs.id = :id AND rs.proximityNotifiedAt IS NULL")
    int claimProximityNotice(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /**
     * 판본마다 {@code seq} 가 가장 작은 도착·미출발 정차 항목 하나씩(Ruling 307 출발 판정 · DA-1) — {@code seq} 오름차순이 "먼저 도착한
     * 정차지를 먼저 판정" 순서를 정한다. {@code stopId IS NOT NULL} 로 강제 경유지를 뺀다 — 경유지는 학생이 배정되지 않아 출발 판정의
     * 대상일 필요가 없다. {@code routeVersionIds} 근거는 {@link #findNextUnarrivedOfVersions} 와 같다.
     */
    @AcademyScopeExempt(reason = "findFirstArrivedNotDepartedOfVersions 와 같은 근거 — 판본 id 는 출발 판정 스케줄러가 RunRepository 로 이미 학원과 "
            + "무관하게 골라낸 run.id 에서 confirmed_route.current_version_id 로 얻은 값만 넘긴다는 전제다")
    @Query("""
            SELECT rs FROM RunStop rs
            WHERE rs.routeVersionId IN :routeVersionIds
              AND rs.stopId IS NOT NULL
              AND rs.arrivedAt IS NOT NULL
              AND rs.departedAt IS NULL
              AND rs.seq = (SELECT MIN(x.seq) FROM RunStop x
                            WHERE x.routeVersionId = rs.routeVersionId
                              AND x.stopId IS NOT NULL
                              AND x.arrivedAt IS NOT NULL
                              AND x.departedAt IS NULL)
            """)
    List<RunStop> findFirstArrivedNotDepartedOfVersions(@Param("routeVersionIds") Collection<Long> routeVersionIds);

    /**
     * 도착 처리 뒤 그 승하차지 100m 안 관측을 <b>최초 1회</b> 기록한다(Ruling 875) — {@link RunStopRepository#claimDeparture} 와 같은 조건부 UPDATE
     * 형태다. 이미 출발 처리된 항목에는 쓰지 않는다. 영향받은 행 수는 쓰지 않는다 — 다른 인스턴스가 먼저 기록했어도 결과가 같다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "findFirstArrivedNotDepartedOfVersions 가 이미 학원과 무관하게 골라낸 정차 항목 id 하나를 조건부로 "
            + "갱신하는 단건 호출이다 — 그 조회가 이미 좁힌 대상이라 이 시점에 학원을 다시 물을 근거가 없다"
            + "(RunStopRepository.claimDeparture 와 같은 근거)")
    @Query("UPDATE RunStop rs SET rs.nearObservedAt = :now "
            + "WHERE rs.id = :id AND rs.nearObservedAt IS NULL AND rs.departedAt IS NULL")
    int claimNearObservation(@Param("id") Long id, @Param("now") OffsetDateTime now);
}
