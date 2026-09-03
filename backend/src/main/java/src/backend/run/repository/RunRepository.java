package src.backend.run.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.Direction;
import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;

/** {@link Run} 영속성 접근 — 조회는 전부 학원으로 좁혀져 호출부가 조건을 빼먹을 자리가 부재하다. */
public interface RunRepository extends JpaRepository<Run, Long> {

    /**
     * 한 학원의 <b>그 날짜</b> 회차 목록(SCH-02 결과 조회, §5.10 {@code GET /staff/runs}).
     *
     * <p>날짜 조건이 <b>쿼리에 고정</b>돼 있는 것이 요점이다 — 조건이 빠져도 목록은 그럴듯하게
     * 동작하고(ARCHITECTURE §6.1), 그 상태에서는 오늘 화면에 지난달 회차가 함께 뜬다.
     *
     * <p>취소된 회차({@code canceled_at} 이 채워진 것)도 싣는다 — 임시 취소는 <b>표시</b>이지 삭제가
     * 아니고(§5.10), 거르면 관계자가 무엇을 취소했는지 되읽을 경로가 사라진다.
     */
    List<Run> findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(Long academyId, LocalDate serviceDate);

    /**
     * 임시 취소·배치 대상 회차 1건(SCH-03 · MGR-05, §5.10·§5.14) — 학원이 어긋나면 빈 결과이고
     * 호출부가 그것을 {@code 404 RUN_NOT_FOUND} 로 답한다(Ruling 163: {@code {id}} 지목은 404).
     */
    Optional<Run> findByIdAndAcademyId(Long id, Long academyId);

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
     */
    @AcademyScopeExempt(reason = "확정 배치(RTE-08)는 시각이 촉발하는 전 학원 대상 조회라 좁힐 학원이 부재하다 — "
            + "학원 하나로 좁히면 나머지 학원의 회차가 확정되지 않는다. 호출부는 배치(RunConfirmationScheduler)뿐이라는 "
            + "전제 — 요청 경로에서 부르면 이 예외가 우회로가 된다(ScheduleRepository.findAllByWeekdayAndActiveIsTrue 와 같은 근거)")
    List<Run> findByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNullOrderByConfirmAtAsc(RunStatus status,
            OffsetDateTime now, Pageable pageable);

    /**
     * 근접 알림 스케줄러(NTF-04, 목표 13·15)의 조회 대상 — 시각 문턱이 없다는 점이
     * {@link #findByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNullOrderByConfirmAtAsc} 와 다르다.
     * 근접 판정은 "판정 시각이 지났는가" 가 아니라 "지금 운행 중인가" 만 묻는다({@code RunStatus.MOVING}) —
     * 실제 좌표가 300m 안에 들어왔는지는 이 조회가 아니라 정차 항목별 판정이 담당한다.
     *
     * <p>{@code canceled_at IS NULL} 을 더한 이유는 {@link #confirmIfIdle} 과 같다 — 조회와 판정
     * 사이에 회차가 취소돼도 {@code status} 는 그대로일 수 있어, 취소 배제를 별도로 걸어야 한다.
     *
     * <p>{@code pageable} 은 한 틱이 한 번에 집는 상한이다(확정 배치와 같은 근거) — 상한 없이 전건을
     * 집으면 동시 운행 중인 회차가 몰린 틱 하나가 오래 걸린다.
     */
    @AcademyScopeExempt(reason = "근접 알림 스케줄러는 시각이 촉발하는 전 학원 대상 조회라 좁힐 학원이 부재하다 — "
            + "findByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNullOrderByConfirmAtAsc 와 같은 근거. 호출부는 "
            + "배치(ProximityNotificationScheduler)뿐이라는 전제 — 요청 경로에서 부르면 이 예외가 우회로가 된다")
    List<Run> findByStatusAndCanceledAtIsNullOrderByIdAsc(RunStatus status, Pageable pageable);

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
     * 여전히 {@code idle} 이라 {@link #findByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNullOrderByConfirmAtAsc}
     * 의 배제만으로는 그 창을 못 닫는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "findByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNullOrderByConfirmAtAsc 가 "
            + "이미 전 학원 대상으로 골라낸 run.id 하나를 조건부로 갱신하는 단건 호출이다 — 그 조회가 이미 좁힌 대상이라 "
            + "이 시점에 학원을 다시 물을 근거가 없다")
    @Query("UPDATE Run r SET r.status = src.backend.run.entity.RunStatus.CONFIRMED, r.confirmedAt = :confirmedAt, "
            + "r.consecutiveFailures = 0 WHERE r.id = :id AND r.status = src.backend.run.entity.RunStatus.IDLE "
            + "AND r.canceledAt IS NULL")
    int confirmIfIdle(@Param("id") Long id, @Param("confirmedAt") OffsetDateTime confirmedAt);

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
    @Query("UPDATE Run r SET r.consecutiveFailures = r.consecutiveFailures + 1 WHERE r.id = :id")
    int recordFailure(@Param("id") Long id);

    /**
     * 회차 id 목록을 학원 조건 없이 읽는다(AdminEmergencyQueryService, §6.11) — 호출부가 넘기는
     * id 는 이미 emergency_alert.run_id 에서 온 값이고, 그 조회(EmergencyAlertRepository
     * #findAllByOrderByReceivedAtDesc) 자체가 메인 관리자 콘솔의 명시적 전 학원 예외라 여기서 다시
     * 학원으로 좁힐 근거가 부재하다(AccountRepository#findAllByIdIn 과 같은 근거).
     */
    @AcademyScopeExempt(reason = "메인 관리자 콘솔의 전 학원 비상 알림 조회(AdminEmergencyQueryService) — 호출부가 "
            + "넘기는 runId 는 emergency_alert.run_id 에서 온 값이라 학원별로 미리 좁힐 수 없다. emergency_alert "
            + "조회 자체가 이미 §6.11 의 명시적 전 학원 예외다(AccountRepository#findAllByIdIn 과 같은 근거)")
    List<Run> findAllByIdIn(Collection<Long> ids);
}
