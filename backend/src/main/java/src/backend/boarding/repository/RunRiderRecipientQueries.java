package src.backend.boarding.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link RunRider} 의 알림·방송 대상 조회 묶음 — 학생 id 목록과 확정 결과·잔류 명단. 호출부는 이 묶음을 직접 주입하지 않고
 * {@link RunRiderRepository} 가 상속해 노출하는 메서드로 쓴다.
 */
@NoRepositoryBean
public interface RunRiderRecipientQueries extends Repository<RunRider, Long> {

    /**
     * 위치 방송 수신자 — 그 회차 탑승자 중 {@code excludedStatus}(absent) 가 아닌 학생의 id 만(중복 없이). 엔티티 20여 개를 읽어 학생 id 만
     * 쓰는 {@link RunRiderRepository#findAllByRunId} 대신 투영으로 읽는다. 근거는 {@link RunRiderRepository#findAllByRunId} 와 같다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 RunRepository.findByIdAndAcademyId 로 이미 학원 범위에 좁혀 얻은 "
            + "회차의 식별자라는 전제다(findAllByRunId 와 같은 근거)")
    @Query("SELECT DISTINCT rr.studentId FROM RunRider rr WHERE rr.runId = :runId AND rr.status <> :excludedStatus")
    List<Long> findStudentIdsByRunIdAndStatusNot(@Param("runId") Long runId,
            @Param("excludedStatus") RiderStatus excludedStatus);

    /**
     * 하원 최종 지점 도착 처리에서 종료가 보류될 때 기사 화면에 실을 미하차 잔류
     * 명단 — 이름·현재 승하차지를 이 조회에서 함께 채운다({@code RunArriveResponse.remaining[]}
     * 이 별도 조회 없이 바로 응답에 실릴 수 있게).
     *
     * <p>{@code runId} 근거는 {@link RunRiderRepository#findByRunIdAndStudentId} 와 같다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 — "
            + "findByRunIdAndStudentId 와 같은 근거(AcademyScope.assertAccessible 선확인)")
    @Query("""
            SELECT rr.id AS riderId, s.name AS name, st.name AS stopName
            FROM RunRider rr
            JOIN Student s ON s.id = rr.studentId
            JOIN Stop st ON st.id = rr.stopId
            WHERE rr.runId = :runId
              AND rr.status = :status
            ORDER BY rr.id ASC
            """)
    List<RemainingRiderView> findRemainingByRunIdAndStatus(@Param("runId") Long runId,
            @Param("status") RiderStatus status);

    /**
     * 그 승하차지에서 근접 알림(NTF-04)을 받을 학생 id 목록 — {@link RiderStatus#ABSENT} 인 학생은
     * 뺀다(C-02: {@code absent} 학생은 {@code arrive}·{@code delay} 발송 대상 밖). {@code no_show} 는
     * 이 알림이 아직 도착 전 상태에서만 발송되므로 굳이 배제하지 않는다 — 미승차 확정은 승차 시점
     * 이후에 나는 상태다.
     *
     * <p>{@code runId} 근거는 {@link RunRiderRepository#findByRunIdAndStudentId} 와 같다 — 호출부(근접 알림 스케줄러)가
     * {@code RunRepository} 로 이미 학원과 무관하게 골라낸 회차의 식별자만 넘긴다는 전제다.
     */
    @AcademyScopeExempt(reason = "runId 는 근접 알림 스케줄러가 RunRepository 로 이미 학원과 무관하게 골라낸 회차의 "
            + "식별자라는 전제다 — findByRunIdAndStudentId 와 같은 근거")
    @Query("""
            SELECT rr.studentId FROM RunRider rr
            WHERE rr.runId = :runId
              AND rr.stopId = :stopId
              AND rr.status <> src.backend.boarding.entity.RiderStatus.ABSENT
            """)
    List<Long> findStudentIdsByRunIdAndStopIdExcludingAbsent(@Param("runId") Long runId, @Param("stopId") Long stopId);

    /**
     * 지연 알림(NTF-06, API_SPEC §4.9)의 수신 대상 학생 id — 현재 승하차지 <b>이후</b>({@code stopIds},
     * 호출부가 seq 로 미리 걸러 넘긴다) 승하차지의 탑승자 중, 이미 탑승한 학생과 결석 처리된 학생을
     * 뺀다(수신 범위 표 "이미 탑승한 학생은 제외" · C-02 "absent 는 대상 밖").
     *
     * <p>두 배제를 <b>별개의 AND 절</b>로 쓴다 — {@code status NOT IN (BOARDED, ABSENT)} 로 합치면
     * 한쪽 배제만 지워도 나머지가 가려 결함이 드러나지 않는다.
     *
     * <p>{@code runId} 근거는 {@link RunRiderRepository#findByRunIdAndStudentId} 와 같다 — 호출부(지연 알림 커맨드
     * 서비스)가 이 메서드에 앞서 {@code runRepository.findByIdAndAcademyId} 로 이미 학원 범위에 좁혀
     * 확인한 회차의 식별자만 넘긴다는 전제다({@code RunAssignmentAccess
     * #assertAssignedEscort} 는 그 자신의 자바독대로 학원 범위를 확인하지 않는다. 확인은 뒤이은 그
     * 조회가 성공했을 때 비로소 성립한다).
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 runRepository.findByIdAndAcademyId 로 이미 학원 범위에 "
            + "좁혀 확인한 회차의 식별자라는 전제다 — findByRunIdAndStudentId 와 같은 근거")
    @Query("""
            SELECT DISTINCT rr.studentId FROM RunRider rr
            WHERE rr.runId = :runId
              AND rr.stopId IN :stopIds
              AND rr.status <> src.backend.boarding.entity.RiderStatus.BOARDED
              AND rr.status <> src.backend.boarding.entity.RiderStatus.ABSENT
            """)
    List<Long> findStudentIdsForDelayNotification(@Param("runId") Long runId,
            @Param("stopIds") Collection<Long> stopIds);

    /**
     * 하원 지연 알림 수신 학생(API_SPEC §4.9) — 아직 지나지 않은 승하차지의 탑승자 중 <b>탑승 중({@code boarded})</b>
     * 인 학생. 하원은 시작 때 전원이 탑승 상태라 {@link #findStudentIdsForDelayNotification} 의 "아직 탑승하지 않은 학생"
     * 조건으로는 수신자가 0명이었다. 결석·하차·미승차·대기는 탑승 중이 아니므로 자연히 빠진다.
     *
     * <p>{@code runId} 근거는 {@link #findStudentIdsForDelayNotification} 와 같다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 runRepository.findByIdAndAcademyId 로 이미 학원 범위에 "
            + "좁혀 확인한 회차의 식별자라는 전제다 — findStudentIdsForDelayNotification 와 같은 근거")
    @Query("""
            SELECT DISTINCT rr.studentId FROM RunRider rr
            WHERE rr.runId = :runId
              AND rr.stopId IN :stopIds
              AND rr.status = src.backend.boarding.entity.RiderStatus.BOARDED
            """)
    List<Long> findBoardedStudentIdsForDelayNotification(@Param("runId") Long runId,
            @Param("stopIds") Collection<Long> stopIds);

    /**
     * 그 승하차지를 출발할 때(Ruling 308) 확정 결과를 통지할 학생 명단 — 승차·
     * 하차·미승차 3종만 대상이다. {@code waiting} 인 채 출발한 학생은 발송 대상이 아니다.
     * 이 조회가 이미 걸러 낸다. {@code absent} 도 뺀다 — 결석은 운행 중 승하차 처리 결과가
     * 아니라 사전에 확정된 상태라 이 통지의 대상이 아니다(근접 알림의 {@code excludingAbsent} 와
     * 같은 근거).
     *
     * <p><b>하원은 {@code boarded} 를 뺀다</b> — 하원의 승차 알림은 시작 때 자동 승차분으로 이미 나갔고
     * (NTF-01 · {@code RunAutoBoardedEvent}), 하차지를 출발할 때까지 하차 처리를 못 받은 학생은 아직 탑승 중이라
     * 확정된 결과가 없다. 빼지 않으면 하차지에서 "탑승했습니다" 가 나간다.
     *
     * <p>{@code runId} 근거는 {@link RunRiderRepository#findByRunIdAndStudentId} 와 같다 — 호출부(출발 판정, Ruling
     * 307)가 근접 알림과 같은 스케줄러로 이미 학원과 무관하게 골라낸 회차의 식별자라는 전제다.
     */
    @AcademyScopeExempt(reason = "runId 는 출발 판정 스케줄러가 근접 알림과 같은 근거로 RunRepository 로 이미 학원과 "
            + "무관하게 골라낸 회차의 식별자라는 전제다 — findStudentIdsByRunIdAndStopIdExcludingAbsent 와 같은 근거")
    @Query("""
            SELECT rr FROM RunRider rr
            JOIN Run r ON r.id = rr.runId
            WHERE rr.runId = :runId
              AND rr.stopId = :stopId
              AND rr.status IN (src.backend.boarding.entity.RiderStatus.BOARDED,
                                 src.backend.boarding.entity.RiderStatus.ALIGHTED,
                                 src.backend.boarding.entity.RiderStatus.NO_SHOW)
              AND NOT (r.direction = src.backend.global.common.enums.Direction.FROM_ACADEMY
                       AND rr.status = src.backend.boarding.entity.RiderStatus.BOARDED)
            """)
    List<RunRider> findFinalizedByRunIdAndStopId(@Param("runId") Long runId, @Param("stopId") Long stopId);
}
