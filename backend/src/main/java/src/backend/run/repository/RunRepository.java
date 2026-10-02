package src.backend.run.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import src.backend.global.common.enums.Direction;
import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunCancelSource;
import src.backend.run.entity.RunStatus;

/** {@link Run} 영속성 접근 — 조회는 전부 학원으로 좁혀져 호출부가 조건을 빼먹을 자리가 부재하다. */
public interface RunRepository extends JpaRepository<Run, Long> {

    /**
     * <b>끝나지 않은 이동 중 회차</b>의 조건 한 벌(R46-KFIXBE K-1 Ruling 701 · R47 Ruling 724) — 미취소 {@code moving} 이고 운행일이
     * {@code :before} 보다 이르다. {@link #countStaleMoving}(경보 지표) · {@link #findStaleMoving}(관리자 목록) ·
     * {@link #finishIfStaleMoving}(강제 종료)가 이 문자열을 같이 이어 쓴다 — 조건이 두 벌이면 경보가 센 회차와 목록에 뜬 회차가
     * 어긋나고, 강제 종료가 목록에 없는 회차를 끝낼 수 있다. {@code :before} 는 {@code MovingRunWindowPolicy#earliestServiceDate}.
     */
    String STALE_MOVING = "r.status = src.backend.run.entity.RunStatus.MOVING AND r.canceledAt IS NULL "
            + "AND r.serviceDate < :before";

    /**
     * 한 학원의 <b>그 날짜</b> 회차 목록(SCH-02 결과 조회, §5.10 {@code GET /staff/runs}).
     *
     * <p>날짜 조건이 <b>쿼리에 고정</b>돼 있는 것이 요점이다 — 조건이 빠져도 목록은 그럴듯하게
     * 동작하고(ARCHITECTURE §6.1), 그 상태에서는 오늘 화면에 지난달 회차가 함께 뜬다.
     *
     * <p>취소된 회차({@code canceled_at} 이 채워진 것)도 싣는다 — 임시 취소는 <b>표시</b>이지 삭제가
     * 아니고(API_SPEC §5.10), 거르면 관계자가 무엇을 취소했는지 되읽을 경로가 사라진다.
     */
    List<Run> findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(Long academyId, LocalDate serviceDate);

    /**
     * 한 학원에서 그 상태인 회차 목록 — 지금은 {@code MOVING} 하나만 호출부(데모 시뮬레이터의 운행
     * 중 버스 판정 · {@code AdminAcademyQueryService} 의 학원 상세 "운행 중 차량 수" 집계)가 넘긴다.
     * §6.8 메인 관리자 관제는 <b>이 메서드를 쓰지 않는다</b> — 오늘 회차를 상태 무관 전부 반환하도록
     * 바뀌며(Ruling 315) 날짜 조건이 있는 {@code findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc}
     * 로 옮겨 갔다. 상태 조건을 고정하지 않고 파라미터로 두는 이유는 서비스 계층이 "운행 중" 이라는
     * 판정을 이 메서드 이름이 아니라 자신의 자바독에 명시하게 하기 위함이다.
     */
    List<Run> findAllByAcademyIdAndStatusOrderByDepartTimeAsc(Long academyId, RunStatus status);

    /**
     * 임시 취소·배치 대상 회차 1건(SCH-03 · MGR-05, §5.10·§5.14) — 학원이 어긋나면 빈 결과이고
     * 호출부가 그것을 {@code 404 RUN_NOT_FOUND} 로 답한다(Ruling 163: {@code {id}} 지목은 404).
     */
    Optional<Run> findByIdAndAcademyId(Long id, Long academyId);

    /**
     * 회차 1건을 <b>행 잠금</b>으로 읽는다 — 경유 지점 배포(§5.15)가 배포 트랜잭션 안에서 운행 구간을 다시 볼 때
     * 쓴다(BR-021). 운행 시작({@code run.start})도 이 행을 갱신하므로 둘이 순서대로 서, "구간을 본 뒤 운행이
     * 시작돼 잠긴 노선에 새 판본이 나가는" 경우가 생기지 않는다. 같은 회차의 배포끼리도 줄을 선다.
     *
     * <p>강제 추가·이동 저장(BR-044)도 같은 잠금으로 읽는다 — {@link #confirmIfIdle} 과 같은 행이라 둘 중 늦은
     * 쪽은 먼저 커밋된 상태를 본다. 저장이 먼저면 확정 저장이 그 행을 다시 세고, 확정이 먼저면 이 조회가
     * {@code confirmed} 를 본다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Run> findLockedByIdAndAcademyId(Long id, Long academyId);

    /**
     * 회차 id 목록을 학원으로 다시 좁혀 읽는다(§4.1 {@code GET /manager/runs}) — 순서는 보장하지
     * 않는다.
     *
     * <p>호출부(매니저 배치 목록 조회)가 넘기는 id 목록은 이미 {@code AssignmentRepository
     * #findByManagerIdAndAcademyIdAndServiceDate} 가 학원으로 좁힌 것이지만, 이 조회에서도 조건을
     * 다시 건다 — {@link #findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc} 를 비롯해 이 저장소가
     * 조회마다 학원 조건을 직접 갖는 관례를 여기서도 지킨다.
     */
    List<Run> findAllByIdInAndAcademyId(Collection<Long> ids, Long academyId);

    /**
     * 같은 유일성 조합의 회차가 이미 있는지 본다 — {@code uk_run_bus_date_direction_depart} 위반을
     * 저장 전에 막는다.
     *
     * <p>제약 자체에는 {@code academy_id} 가 없는데 학원 조건을 더한 것은 격리 규칙(횡단 규칙 7)을
     * 지키기 위함이다 — 차량이 한 학원에만 속하므로 결과가 달라지지 않는다.
     *
     * <p><b>이 선검사는 방어의 전부가 아니다.</b> 동시 실행 2건은 서로의 미커밋 INSERT 를 보지 못한 채
     * 둘 다 여기를 지나며, 그때 막는 것은 UNIQUE 제약이다.
     */
    boolean existsByAcademyIdAndBusIdAndServiceDateAndDirectionAndDepartTime(Long academyId, Long busId,
            LocalDate serviceDate, Direction direction, OffsetDateTime departTime);

    /**
     * 확정 배치(RTE-08)의 조회 대상 — <b>실행 시각</b>({@code now}) 이 <b>판정 시각</b>
     * ({@code confirm_at}, 회차 생성 시점에 이미 계산해 저장한 값)을 지난 idle 회차만 고른다
     * (ARCHITECTURE §9.2 두 시계). 여기서 {@code depart_time - 30분} 을 다시 계산하지 않는다 — 실행
     * 시각으로 재계산하면 배치가 늦게 돈 회차의 판정 기준이 실행 시각 쪽으로 밀린다.
     *
     * <p>{@code pageable} 은 한 틱이 한 번에 집는 상한이다(목표 6) — 상한 없이 전건을 집으면 회차가
     * 몰린 틱 하나가 커넥션·워커 풀을 오래 붙든다. {@code ix_run_status_confirm_at} 이 이 조회를 받친다.
     *
     * <p><b>영구 실패 회차가 상한을 차지하지 못하게</b> 두 조건을 건다(BR-001, ARCHITECTURE §9.4 회차 단위
     * 격리). ① {@code service_date >= today} — 지난 날짜 회차는 확정해도 운행할 수 없고, 빼지 않으면 노선
     * 미편성 회차가 날마다 쌓여 상한을 전부 차지한다. ② {@code consecutive_failures} 오름차순 — 오늘 이미
     * 실패한 회차는 처음 도래한 회차 뒤로 밀려 빈 자리에서만 재시도된다. 실패 횟수로 <b>제외</b>하지 않는
     * 이유는 관계자가 노선을 편성한 뒤에도 그 회차가 확정돼야 하기 때문이다.
     *
     * <p>③ {@code confirm_retry_at} — 실패한 회차는 실패 횟수에 따라 늘어나는 간격(30초부터 두 배 · 최대 10분)이 지난 뒤에야 다시
     * 집힌다(R46-KFIXBE K-2, Ruling 703). 영구 실패 회차를 30초마다 다시 시도하면 하루 2,880회 시도와 스택 로그가 쌓인다. 이 시각은
     * <b>실행 시각</b>이고 판정 시각({@code confirm_at})은 그대로다 — 노선·학원 좌표를 저장하면 {@link #resetConfirmationFailures}
     * 가 비워 다음 틱에 바로 다시 시도한다.
     */
    @AcademyScopeExempt(reason = "확정 배치(RTE-08)는 시각이 촉발하는 전 학원 대상 조회라 좁힐 학원이 부재하다 — "
            + "학원 하나로 좁히면 나머지 학원의 회차가 확정되지 않는다. 호출부는 배치(RunConfirmationScheduler)뿐이라는 "
            + "전제 — 요청 경로에서 부르면 이 예외가 우회로가 된다(ScheduleRepository.findAllByWeekdayAndActiveIsTrue 와 같은 근거)")
    @Query("SELECT r FROM Run r WHERE r.status = :idleStatus AND r.confirmAt <= :now "
            + "AND r.serviceDate >= :today AND r.canceledAt IS NULL "
            + "AND (r.confirmRetryAt IS NULL OR r.confirmRetryAt <= :now) "
            + "ORDER BY r.consecutiveFailures ASC, r.confirmAt ASC")
    List<Run> findDueForConfirmation(@Param("now") OffsetDateTime now, @Param("today") LocalDate today,
            @Param("idleStatus") RunStatus idleStatus, Pageable pageable);

    /**
     * 근접 알림 스케줄러(NTF-04, 목표 13·15)의 조회 대상 — 시각 문턱이 없다는 점이
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
     * <p><b>{@code service_date >= since}</b> 로 지난 운행일의 이동 중 회차를 뺀다(R46-KFIXBE K-1, Ruling 701) — 잔류 인원을
     * 정리하지 못해 끝나지 않은 회차가 처리 집합에 영구히 남으면 틱마다 읽는 양이 날마다 늘고, 위치가 없는 그 회차가 유실
     * 경보를 계속 켜 둔다. {@code since} 는 {@code MovingRunWindowPolicy#earliestServiceDate} 다. 빠진 회차의 수는
     * {@link #countStaleMoving} 이 센다. {@code ix_run_moving}(부분 인덱스)이 이동 중 회차만 골라 주므로 날짜 조건은 그 소수에
     * 대한 걸러내기다. {@code status} 를 바인딩 파라미터가 아니라 <b>리터럴</b>로 둔 것도 이 인덱스 때문이다 — 부분 인덱스는 리터럴일 때만 쓰여, 파라미터로 받으면
     * 일반(generic) 계획이 {@code run_pkey} 로 전 행을 훑는다(14.6만 행 실측 20.7ms · 리터럴은 0.033ms, {@code ix_run_status_confirm_at} 주석과 같은
     * 근거). 읽기 전용 트랜잭션 한 개로 묶는다 — 근접 판정 시험(R46-LATERBE L5)이 한 틱의 트랜잭션 수를 세는데, 이 조회가
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
     * (R46-KFIXBE K-1, Ruling 701). {@link #findMovingFromServiceDate} 가 집지 않는 바로 그 회차라, 근접 판정·유실 집계·노선
     * 잠금이 못 보는 대신 {@code schoolbus.run.moving.stale} 게이지로 사람에게 드러낸다.
     */
    @AcademyScopeExempt(reason = "끝나지 않은 이동 중 회차 게이지는 시각이 촉발하는 전 학원 대상 집계라 좁힐 학원이 부재하다 — "
            + "countOverdueUnconfirmed 와 같은 근거. 호출부는 관측 스케줄러(StaleMovingRunGaugeScheduler)뿐이라는 전제")
    @Query("SELECT COUNT(r) FROM Run r WHERE " + STALE_MOVING)
    long countStaleMoving(@Param("before") LocalDate before);

    /**
     * 끝나지 않은 이동 중 회차 목록(R47 Ruling 724) — {@link #countStaleMoving} 이 세는 바로 그 회차를 운행일 오름차순으로,
     * 학원 이름 · 호차 · 아직 {@code boarded} 인 탑승자 수와 함께 읽는다. 학원·차량은 theta 조인이라 회차마다 한 번에 읽힌다.
     * {@code limit} 은 목록 상한이다({@code PageParams#UNPAGED_LIST_MAX}) — 오래된 회차부터 자르므로 처리하면 다음 회차가 올라온다.
     */
    @AcademyScopeExempt(reason = "메인 관리자 콘솔의 끝나지 않은 이동 중 회차 목록(AdminStaleMovingRunQueryService)은 전 학원 대상 조회라 "
            + "좁힐 학원이 부재하다 — countStaleMoving 과 같은 근거. 호출부는 @CanMonitorAll 로 보호되는 그 서비스뿐이라는 전제")
    @Query("SELECT r.id AS runId, r.academyId AS academyId, a.name AS academyName, r.serviceDate AS serviceDate, "
            + "r.direction AS direction, b.busNo AS busNo, r.startedAt AS startedAt, r.finishPending AS finishPending, "
            + "(SELECT COUNT(rr) FROM RunRider rr WHERE rr.runId = r.id "
            + "AND rr.status = src.backend.boarding.entity.RiderStatus.BOARDED) AS boardedCount "
            + "FROM Run r, Academy a, Bus b WHERE a.id = r.academyId AND b.id = r.busId AND " + STALE_MOVING
            + " ORDER BY r.serviceDate ASC, r.id ASC")
    List<StaleMovingRunRow> findStaleMoving(@Param("before") LocalDate before, Limit limit);

    /**
     * 끝나지 않은 이동 중 회차 1건을 {@code finished} 로 닫는다(R47 Ruling 724) — 영향받은 행 수가 성공 여부다.
     *
     * <p><b>{@link #STALE_MOVING} 이 조건에 그대로 들어 있는 것이 동시성 방어의 전부다.</b> 동승자가 같은 순간 마지막 하차를
     * 눌러 {@code RunCompletionService} 가 회차 행을 잠그고 종료하면, 이 갱신은 그 커밋을 기다렸다가 {@code status = 'moving'}
     * 조건이 거짓이 되어 0행을 갱신한다 — 반대로 이 갱신이 먼저면 그쪽의 다시 읽기가 {@code finished} 를 보고 종료를 건너뛴다.
     * 먼저 읽어 판정한 뒤 갱신하면 그 사이에 둘 다 통과한다. 탑승자 상태·하차 기록은 건드리지 않는다(지난 운행의 하차 시각을 지어낼 수 없다).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "메인 관리자 콘솔이 목록(findStaleMoving)에서 고른 run.id 하나를 조건부로 닫는 단건 호출이다 — "
            + "그 목록이 이미 전 학원 대상이라 이 시점에 학원을 다시 물을 근거가 없다. 호출부는 @CanForceFinishRun 으로 보호되는 "
            + "RunForceFinishCommandService 뿐이라는 전제")
    @Query("UPDATE Run r SET r.status = src.backend.run.entity.RunStatus.FINISHED, r.finishedAt = :finishedAt, "
            + "r.finishPending = false WHERE r.id = :id AND " + STALE_MOVING)
    int finishIfStaleMoving(@Param("id") Long id, @Param("before") LocalDate before,
            @Param("finishedAt") OffsetDateTime finishedAt);

    /**
     * 회차를 idle → confirmed 로 전이한다 — 영향받은 행 수로 성공 여부를 판정한다(목표 2).
     *
     * <p><b>{@code WHERE status = 'idle'} 조건이 멱등성의 전부다.</b> 같은 회차를 동시에 두 스레드가
     * 부르면 먼저 행 잠금을 얻은 쪽만 {@code status='idle'} 을 보고 갱신하며, 나중 스레드는 커밋된
     * 값을 다시 읽어 조건이 거짓이 되어 0행을 갱신한다 — {@code SELECT} 로 먼저 상태를 본 뒤 갱신하면
     * 그 사이에 경쟁자가 끼어들 수 있어 이 보장이 성립하지 않는다.
     *
     * <p><b>{@code REQUIRES_NEW} 를 쓰지 않는다</b> — {@code NotificationLogRepository} 의 조건부
     * UPDATE 들과 다르게, 이 갱신은 호출자({@code RunConfirmationService.confirmOne})의 트랜잭션에
     * <b>그대로 참여</b>해야 한다. 확정을 먼저 표시해 두고 그 뒤 노선 계산이 실패하면, 참여한
     * 트랜잭션이 롤백되며 이 UPDATE 도 함께 취소되어 회차가 자동으로 idle 로 되돌아간다(목표 5) —
     * 별도 트랜잭션이었다면 그 롤백에 묻어가지 못하고 확정 표시만 남는다.
     *
     * <p>{@code canceled_at IS NULL} 을 조건에 더한 이유는 조회와 이 갱신 사이의 경합이다 — 관계자가
     * 대상 목록을 집은 <b>뒤</b>, 이 갱신이 돌기 <b>전</b>에 그 회차를 취소하면 {@code status} 는
     * 여전히 {@code idle} 이라 {@link #findDueForConfirmation}
     * 의 배제만으로는 그 창을 못 닫는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "findDueForConfirmation 가 "
            + "이미 전 학원 대상으로 골라낸 run.id 하나를 조건부로 갱신하는 단건 호출이다 — 그 조회가 이미 좁힌 대상이라 "
            + "이 시점에 학원을 다시 물을 근거가 없다")
    @Query("UPDATE Run r SET r.status = src.backend.run.entity.RunStatus.CONFIRMED, r.confirmedAt = :confirmedAt, "
            + "r.consecutiveFailures = 0, r.confirmRetryAt = NULL WHERE r.id = :id "
            + "AND r.status = src.backend.run.entity.RunStatus.IDLE "
            + "AND r.canceledAt IS NULL")
    int confirmIfIdle(@Param("id") Long id, @Param("confirmedAt") OffsetDateTime confirmedAt);

    /**
     * 스케줄이 만든 <b>오늘 이후 · 주어진 상태 · 미취소</b> 회차 — 스케줄 수정·삭제를 미리 만든 회차에 반영할 때
     * 고른다(Ruling 366 ②). 오늘({@code today})은 포함하지 않는다 — 오늘 회차는 확정 배치가 이미 걸려 있을 수 있다.
     */
    List<Run> findAllByAcademyIdAndScheduleIdAndServiceDateAfterAndStatusAndCanceledAtIsNull(Long academyId,
            Long scheduleId, LocalDate today, RunStatus status);

    /**
     * 스케줄이 <b>취소한</b> 오늘 이후 · 주어진 상태 회차 — 스케줄 재활성·요일/방향 복귀 때 되살릴 후보다(Ruling 367 ②).
     * 관계자가 직접 취소한 회차({@code cancel_source = staff})와 출처를 모르는 옛 취소({@code NULL})는 고르지 않는다.
     */
    List<Run> findAllByAcademyIdAndScheduleIdAndServiceDateAfterAndStatusAndCancelSource(Long academyId,
            Long scheduleId, LocalDate today, RunStatus status, RunCancelSource cancelSource);

    /**
     * 그 차량·날짜·방향·출발 시각을 <b>다른</b> 회차가 이미 잡고 있는가 — 스케줄 수정이 회차를 옮기기 전에 본다.
     * 취소된 회차도 센다({@code uk_run_bus_date_direction_depart} 가 취소 여부를 가리지 않는다).
     */
    boolean existsByAcademyIdAndBusIdAndServiceDateAndDirectionAndDepartTimeAndIdNot(Long academyId, Long busId,
            LocalDate serviceDate, Direction direction, OffsetDateTime departTime, Long id);

    /**
     * 그 스케줄이 그날 그 방향으로 <b>살아 있는</b>(미취소) 회차를 이미 가졌는가 — 스케줄 수정 뒤 내일 회차를 만들기
     * 전에 본다. 확정·시작된 회차는 수정 반영 대상이 아니라 옛 출발 시각으로 남으므로, 유일성 조합만 보면 새 시각으로
     * 같은 스케줄의 회차가 하나 더 생긴다.
     */
    boolean existsByAcademyIdAndScheduleIdAndServiceDateAndDirectionAndCanceledAtIsNull(Long academyId,
            Long scheduleId, LocalDate serviceDate, Direction direction);

    /** 차량의 오늘 이후 · 미취소 회차 중 주어진 상태의 것 — 정원 축소 경고(§5.12, BR-116)가 쓴다. */
    List<Run> findAllByAcademyIdAndBusIdAndServiceDateGreaterThanEqualAndCanceledAtIsNullAndStatusIn(Long academyId,
            Long busId, LocalDate today, Collection<RunStatus> statuses);

    /**
     * 확정 실패 1회를 기록한다(목표 4) — {@code consecutive_failures} 만 올린다.
     *
     * <p>상태를 여기서 다시 {@code idle} 로 되돌리지 않는다 — {@link #confirmIfIdle} 이 참여한
     * 트랜잭션이 이미 롤백되어 DB 의 상태는 갱신 전 {@code idle} 그대로다. 이 메서드가 하는 일은
     * "실패했다는 사실" 만 별도로 남기는 것이다.
     *
     * <p><b>{@code REQUIRES_NEW} 가 필요하다</b> — 이 메서드는 확정이 실패해 앞 트랜잭션이 이미
     * 롤백·종료된 <b>뒤</b>, 오케스트레이터(트랜잭션 밖)가 호출한다. 실패 기록 자체가 그 롤백에
     * 휩쓸리면 안 되므로 독립 트랜잭션을 새로 연다({@code NotificationLogRepository} 와 같은 근거).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "확정 배치가 이미 학원과 무관하게 골라낸 run.id 하나의 실패 카운터만 올리는 단건 "
            + "갱신이다 — confirmIfIdle 과 같은 근거")
    @Query("UPDATE Run r SET r.consecutiveFailures = r.consecutiveFailures + 1, r.confirmRetryAt = :retryAt "
            + "WHERE r.id = :id")
    int recordFailure(@Param("id") Long id, @Param("retryAt") OffsetDateTime retryAt);

    /**
     * 그 학원의 확정 실패 이력을 지운다 — 노선·학원 좌표를 저장해 영구 실패의 원인이 고쳐졌을 수 있을 때 호출한다(R46-KFIXBE K-2,
     * Ruling 703). 아직 {@code idle} 인 회차의 {@code consecutive_failures} 를 0 으로, {@code confirm_retry_at} 을 비워 다음 틱에 바로
     * 다시 시도하게 한다. 고쳐지지 않았으면 다시 실패해 간격이 처음부터 늘어난다.
     *
     * <p>호출자의 트랜잭션에 참여한다({@code recordFailure} 와 달리 {@code REQUIRES_NEW} 가 아니다) — 저장이 롤백되면 이 초기화도 함께
     * 취소돼야 한다. 영속성 컨텍스트는 비우지 않는다 — 호출 지점(노선·학원 저장)이 방금 읽은 엔티티를 이어 쓴다.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Run r SET r.consecutiveFailures = 0, r.confirmRetryAt = NULL WHERE r.academyId = :academyId "
            + "AND r.status = src.backend.run.entity.RunStatus.IDLE AND r.consecutiveFailures > 0")
    int resetConfirmationFailures(@Param("academyId") Long academyId);

    /**
     * 회차 id 목록을 학원 조건 없이 읽는다(AdminEmergencyQueryService, §6.11) — 호출부가 넘기는
     * id 는 이미 emergency_alert.run_id 에서 온 값이고, 그 조회(EmergencyAlertRepository
     * #findAllByState) 자체가 메인 관리자 콘솔의 명시적 전 학원 예외라 여기서 다시
     * 학원으로 좁힐 근거가 부재하다(AccountRepository#findAllByIdIn 과 같은 근거).
     */
    @AcademyScopeExempt(reason = "메인 관리자 콘솔의 전 학원 비상 알림 조회(AdminEmergencyQueryService) — 호출부가 "
            + "넘기는 runId 는 emergency_alert.run_id 에서 온 값이라 학원별로 미리 좁힐 수 없다. emergency_alert "
            + "조회 자체가 이미 §6.11 의 명시적 전 학원 예외다(AccountRepository#findAllByIdIn 과 같은 근거)")
    List<Run> findAllByIdIn(Collection<Long> ids);

    /**
     * {@code schoolbus.run.unconfirmed} 게이지(TECH_DECISIONS §13.1·§13.4)의 조회 대상 — 판정 시각을
     * 5분 넘긴(경고 여유) idle 회차 수. {@code threshold} 에 {@code now - 5분} 을 넘겨받는다 — 알럿
     * 조건과 확정 배치 조회의 문턱이 다르므로({@code +5분} 여유) 이 메서드를 따로 둔다.
     *
     * <p>{@link #findDueForConfirmation} 과 같은 두 조건을 건다. ① {@code canceled_at IS NULL} — 취소된 회차는 애초에
     * 확정 대상이 아니라 "노선을 못 받은" 위험이 없다. ② {@code service_date >= today}(BR-231) — 지난 날짜 idle 회차는
     * 확정 배치가 집지 않아 영구히 idle 이고, 세면 "0 이 아니면 곧 운행 사고" 경보가 다시 꺼지지 않는다.
     */
    @AcademyScopeExempt(reason = "미확정 회차 게이지(관측 목표 8)는 시각이 촉발하는 전 학원 대상 집계라 좁힐 학원이 "
            + "부재하다 — findDueForConfirmation 와 같은 근거. "
            + "호출부는 관측 스케줄러(RunUnconfirmedGaugeScheduler)뿐이라는 전제")
    @Query("SELECT COUNT(r) FROM Run r WHERE r.status = :status AND r.confirmAt <= :threshold "
            + "AND r.serviceDate >= :today AND r.canceledAt IS NULL")
    long countOverdueUnconfirmed(@Param("status") RunStatus status, @Param("threshold") OffsetDateTime threshold,
            @Param("today") LocalDate today);

    /**
     * 학원별로 <b>오늘 확정에 실패 중인</b> 회차 수 — 임시 취소를 뺀 오늘 회차 중 아직 {@code idle} 이면서
     * {@code consecutive_failures > 0} 인 것(§6.15, Ruling 543). 이미 확정된 회차의 옛 실패 횟수는 읽지 않는다. 상태를 enum 리터럴로
     * 쓰는 이유는 {@link #countDelayedByAcademy} 와 같다({@code ix_run_status_confirm_at} 의 {@code status = 'idle'} 부분 조건).
     */
    @AcademyScopeExempt(reason = "메인 관리자 전체 관제(§6.15)가 전 학원을 학원별로 묶어 세는 조회라 좁힐 학원이 부재하다 — "
            + "호출부는 @CanMonitorAll 로 보호되는 AdminRunAttentionQueryService 뿐이라는 전제 — 요청 경로에서 학원 권한으로 "
            + "부르면 이 예외가 우회로가 된다(findDueForConfirmation 과 같은 근거)")
    @Query("SELECT r.academyId AS academyId, COUNT(r) AS runCount FROM Run r "
            + "WHERE r.serviceDate = :today AND r.canceledAt IS NULL "
            + "AND r.status = src.backend.run.entity.RunStatus.IDLE "
            + "AND r.consecutiveFailures > 0 GROUP BY r.academyId")
    List<AcademyRunCount> countConfirmFailedByAcademy(@Param("today") LocalDate today);

    /**
     * 학원별로 <b>오늘 지연 알림이 나간 채 아직 끝나지 않은</b> 회차 수(§6.15, Ruling 543) — 임시 취소를 뺀 오늘
     * 회차 중 {@code finished} 가 아니고 {@code delay_notice} 가 1건 이상인 것. 알림이 여러 건이어도 회차는 한 번만
     * 센다({@code EXISTS}). 상태를 파라미터가 아니라 enum 리터럴로 쓰는 이유는 부분 인덱스
     * {@code ix_run_open_service_date}({@code status <> 'finished'} 조건)가 일반(generic) 계획에서도 쓰이게 하려는 것이다 —
     * 파라미터이면 플래너가 조건이 인덱스 조건을 함의하는지 알 수 없다(R46-LATERBE, Ruling 673).
     */
    @AcademyScopeExempt(reason = "메인 관리자 전체 관제(§6.15)가 전 학원을 학원별로 묶어 세는 조회라 좁힐 학원이 부재하다 — "
            + "countConfirmFailedByAcademy 와 같은 근거와 같은 호출부 전제")
    @Query("SELECT r.academyId AS academyId, COUNT(r) AS runCount FROM Run r "
            + "WHERE r.serviceDate = :today AND r.canceledAt IS NULL "
            + "AND r.status <> src.backend.run.entity.RunStatus.FINISHED "
            + "AND EXISTS (SELECT 1 FROM DelayNotice d WHERE d.runId = r.id) GROUP BY r.academyId")
    List<AcademyRunCount> countDelayedByAcademy(@Param("today") LocalDate today);
}
