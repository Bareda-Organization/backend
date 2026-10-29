package src.backend.run.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.boarding.entity.RiderStatus;
import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.global.security.authz.Permissions;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;

/**
 * {@link RunRepository} 의 이동 중 회차 조회 묶음 — 근접 판정 · 유실 집계가 읽는 이동 중 회차와, 운행일이 지났는데 끝나지 않은 회차(Ruling 701 · 724)의 지표 · 목록 · 강제 종료.
 * 원래 저장소가 이 인터페이스를 상속하므로 메서드 이름 · 시그니처 · 애너테이션은 그대로이고 호출부는 {@link RunRepository} 를 쓴다.
 */
@NoRepositoryBean
public interface MovingRunQueries extends Repository<Run, Long> {

    /**
     * <b>끝나지 않은 이동 중 회차</b>의 조건 한 벌(Ruling 701 · Ruling 724) — 미취소 {@code moving} 이고 운행일이
     * {@code :before} 보다 이르다. {@link #countStaleMoving}(경보 지표) · {@link #findStaleMoving}(관리자 목록) ·
     * {@link #finishIfStaleMoving}(강제 종료)가 이 문자열을 같이 이어 쓴다 — 조건이 두 벌이면 경보가 센 회차와 목록에 뜬 회차가
     * 어긋나고, 강제 종료가 목록에 없는 회차를 끝낼 수 있다. {@code :before} 는 {@code MovingRunWindowPolicy#earliestServiceDate}.
     */
    String STALE_MOVING = "r.status = src.backend.run.entity.RunStatus.MOVING AND r.canceledAt IS NULL "
            + "AND r.serviceDate < :before";

    /**
     * 근접 알림 스케줄러(NTF-04)의 조회 대상 — 시각 문턱이 없다는 점이
     * {@link #findDueForConfirmation} 와 다르다.
     * 근접 판정은 "판정 시각이 지났는가" 가 아니라 "지금 운행 중인가" 만 묻는다({@code RunStatus.MOVING}) —
     * 실제 좌표가 300m 안에 들어왔는지는 이 조회가 아니라 정차 항목별 판정이 담당한다.
     *
     * <p>{@code canceled_at IS NULL} 을 더한 이유는 {@link #confirmIfIdle} 과 같다 — 조회와 판정
     * 사이에 회차가 취소돼도 {@code status} 는 그대로일 수 있어, 취소 배제를 별도로 걸어야 한다.
     *
     * <p>{@code afterId} 보다 큰 id 만 {@code pageable} 크기씩 읽는다 — 판정 뒤에도 회차는 {@code moving}
     * 그대로라 늘 0쪽만 집으면 다음 틱도 같은 묶음이다. 호출부가 마지막 id 를 넘겨 끝까지 이어 읽는다
     * (BR-011). 쪽 번호 대신 id 를 쓰는 이유는 읽는 사이에 회차가 끝나 목록이 줄어도 건너뛰는 행이 없게
     * 하려는 것이다.
     *
     * <p><b>{@code service_date >= since}</b> 로 지난 운행일의 이동 중 회차를 뺀다(Ruling 701) — 잔류 인원을
     * 정리하지 못해 끝나지 않은 회차가 처리 집합에 영구히 남으면 틱마다 읽는 양이 날마다 늘고, 위치가 없는 그 회차가 유실
     * 경보를 계속 켜 둔다. {@code since} 는 {@code MovingRunWindowPolicy#earliestServiceDate} 다. 빠진 회차의 수는
     * {@link #countStaleMoving} 이 센다. {@code ix_run_moving}(부분 인덱스)이 이동 중 회차만 골라 주므로 날짜 조건은 그 소수에
     * 대한 걸러내기다. {@code status} 를 바인딩 파라미터가 아니라 <b>리터럴</b>로 둔 것도 이 인덱스 때문이다 — 부분 인덱스는 리터럴일 때만 쓰여, 파라미터로 받으면
     * 일반(generic) 계획이 {@code run_pkey} 로 전 행을 훑는다(14.6만 행 실측 20.7ms · 리터럴은 0.033ms, {@code ix_run_status_confirm_at} 주석과 같은
     * 근거). 읽기 전용 트랜잭션 한 개로 묶는다 — 근접 판정 시험이 한 틱의 트랜잭션 수를 세는데, 이 조회가
     * 그중 하나다.
     */
    @AcademyScopeExempt(reason = "근접 알림 스케줄러는 시각이 촉발하는 전 학원 대상 조회라 좁힐 학원이 부재하다 — "
            + "findDueForConfirmation 와 같은 근거. 호출부는 "
            + "배치(ProximityNotificationScheduler · RunPositionLostGaugeScheduler)뿐이라는 전제 — 요청 경로에서 부르면 이 예외가 우회로가 된다")
    @Transactional(readOnly = true)
    @Query("SELECT r FROM Run r WHERE r.status = src.backend.run.entity.RunStatus.MOVING AND r.canceledAt IS NULL "
            + "AND r.serviceDate >= :since AND r.id > :afterId ORDER BY r.id ASC")
    List<Run> findMovingFromServiceDate(@Param("since") LocalDate since, @Param("afterId") Long afterId,
            Pageable pageable);

    /**
     * 처리 집합에서 빠진 <b>끝나지 않은 이동 중 회차</b> 수 — 운행일이 {@code before} 보다 이른 미취소 {@code moving} 회차
     * (Ruling 701). {@link #findMovingFromServiceDate} 가 집지 않는 바로 그 회차라, 근접 판정·유실 집계·노선
     * 잠금이 못 보는 대신 {@code schoolbus.run.moving.stale} 게이지로 사람에게 드러낸다.
     */
    @AcademyScopeExempt(reason = "끝나지 않은 이동 중 회차 게이지는 시각이 촉발하는 전 학원 대상 집계라 좁힐 학원이 부재하다 — "
            + "countOverdueUnconfirmed 와 같은 근거. 호출부는 관측 스케줄러(StaleMovingRunGaugeScheduler)뿐이라는 전제")
    @Query("SELECT COUNT(r) FROM Run r WHERE " + STALE_MOVING)
    long countStaleMoving(@Param("before") LocalDate before);

    /**
     * 끝나지 않은 이동 중 회차 목록(Ruling 724) — {@link #countStaleMoving} 이 세는 바로 그 회차를 운행일 오름차순으로,
     * 학원 이름 · 호차 · 아직 {@code boarded} 인 탑승자 수와 함께 읽는다(그 상태는 §9.1 대로 파라미터 — 호출부가 {@code BOARDED} 를 넘긴다). 학원·차량은 theta 조인이라 회차마다 한 번에 읽힌다.
     * {@code limit} 은 목록 상한이다({@code PageParams#UNPAGED_LIST_MAX}) — 오래된 회차부터 자르므로 처리하면 다음 회차가 올라온다.
     */
    @AcademyScopeExempt(reason = "메인 관리자 콘솔의 끝나지 않은 이동 중 회차 목록(AdminStaleMovingRunQueryService)은 전 학원 대상 조회라 "
            + "좁힐 학원이 부재하다 — countStaleMoving 과 같은 근거. 호출부는 @Can(Permissions.MONITOR_ALL) 로 보호되는 그 서비스뿐이라는 전제")
    @Query("SELECT r.id AS runId, r.academyId AS academyId, a.name AS academyName, a.contact AS academyContact, r.serviceDate AS serviceDate, "
            + "r.direction AS direction, b.busNo AS busNo, r.startedAt AS startedAt, r.finishPending AS finishPending, "
            + "(SELECT COUNT(rr) FROM RunRider rr WHERE rr.runId = r.id "
            + "AND rr.status = :boarded) AS boardedCount "
            + "FROM Run r, Academy a, Bus b WHERE a.id = r.academyId AND b.id = r.busId AND " + STALE_MOVING
            + " ORDER BY r.serviceDate ASC, r.id ASC")
    List<StaleMovingRunRow> findStaleMoving(@Param("before") LocalDate before, @Param("boarded") RiderStatus boarded,
            Limit limit);

    /**
     * 끝나지 않은 이동 중 회차 1건을 {@code finished} 로 닫는다(Ruling 724) — 영향받은 행 수가 성공 여부다.
     *
     * <p><b>{@link #STALE_MOVING} 이 조건에 그대로 들어 있는 것이 동시성 방어의 전부다.</b> 동승자가 같은 순간 마지막 하차를
     * 눌러 {@code RunCompletionService} 가 회차 행을 잠그고 종료하면, 이 갱신은 그 커밋을 기다렸다가 {@code status = 'moving'}
     * 조건이 거짓이 되어 0행을 갱신한다 — 반대로 이 갱신이 먼저면 그쪽의 다시 읽기가 {@code finished} 를 보고 종료를 건너뛴다.
     * 먼저 읽어 판정한 뒤 갱신하면 그 사이에 둘 다 통과한다. 탑승자 상태·하차 기록은 건드리지 않는다(지난 운행의 하차 시각을 지어낼 수 없다).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "메인 관리자 콘솔이 목록(findStaleMoving)에서 고른 run.id 하나를 조건부로 닫는 단건 호출이다 — "
            + "그 목록이 이미 전 학원 대상이라 이 시점에 학원을 다시 물을 근거가 없다. 호출부는 @Can(Permissions.RUN_FORCE_FINISH) 으로 보호되는 "
            + "RunForceFinishCommandService 뿐이라는 전제")
    @Query("UPDATE Run r SET r.status = src.backend.run.entity.RunStatus.FINISHED, r.finishedAt = :finishedAt, "
            + "r.finishPending = false WHERE r.id = :id AND " + STALE_MOVING)
    int finishIfStaleMoving(@Param("id") Long id, @Param("before") LocalDate before,
            @Param("finishedAt") OffsetDateTime finishedAt);
}
