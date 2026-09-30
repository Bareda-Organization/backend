package src.backend.boarding.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.common.enums.ChangeType;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.boarding.dto.StaffRunRiderAggregateView;

/**
 * {@link RunRider} 영속성 접근 — {@code run_rider} 는 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b>
 * 자원이다(ERD §6.1). 확정 배치(Phase 7)는 {@code saveAll()} 로 회차별 명단을 한 번에 쌓기만 했으나,
 * Phase 8(②구간 승인 미리보기)이 처음으로 그 명단을 다시 읽어야 해 조회 메서드를 더한다.
 */
public interface RunRiderRepository extends JpaRepository<RunRider, Long> {

    /**
     * 회차 1건의 현재 라이더 명단 전체(Phase 8, ②구간 승인 미리보기의 기준선) — 확정 배치가 쌓은
     * 뒤 그동안 승인된 변경까지 반영된 <b>지금</b> 상태를 읽는다({@code weekly_address} 가 아니라
     * 이쪽을 기준선으로 삼는 이유는 승인 미리보기 판단 근거 참조).
     *
     * <p>{@code academy_id} 컬럼이 부재해 학원 조건을 붙일 자리가 <b>부모(Run) 조인뿐</b>이다
     * (ERD §6.1 부모 경유 — {@code WeeklyAddressRepository} 와 같은 형태).
     */
    @Query("""
            SELECT rr FROM RunRider rr
            JOIN Run r ON r.id = rr.runId
            WHERE rr.runId = :runId
              AND r.academyId = :academyId
            """)
    List<RunRider> findAllByRunIdAndAcademyId(@Param("runId") Long runId, @Param("academyId") Long academyId);

    /**
     * 회차 여러 개의 명단을 한 번에(BR-252) — {@link #findAllByRunIdAndAcademyId} 를 회차마다 부르던 목록 조회가
     * 페이지 크기만큼 질의를 내지 않게 한다. 학원 조건은 같은 부모(Run) 조인이다.
     */
    @Query("""
            SELECT rr FROM RunRider rr
            JOIN Run r ON r.id = rr.runId
            WHERE rr.runId IN :runIds
              AND r.academyId = :academyId
            """)
    List<RunRider> findAllByRunIdInAndAcademyId(@Param("runIds") java.util.Collection<Long> runIds,
            @Param("academyId") Long academyId);

    /**
     * ③구간 미등원 토글(API_SPEC §3.6)이 상태를 옮길 그 학생의 명단 행 1건 —
     * {@code uk_run_rider_run_student} UNIQUE(암시)가 결과를 한 건으로 좁힌다.
     *
     * <p>{@code runId} 는 호출부가 {@code RunRepository.findByIdAndAcademyId} 로 이미 학원 범위에
     * 좁혀 얻은 회차의 식별자라는 전제다 — {@code request.repository.BoardingIntentRepository
     * #findByRunIdAndStudentId} 와 같은 근거(Ruling 153·180 {id} 지목 관례).
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 RunRepository.findByIdAndAcademyId 로 이미 학원 범위에 좁혀 얻은 "
            + "회차의 식별자라는 전제다(BoardingIntentRepository.findByRunIdAndStudentId 와 같은 근거). run_rider 는 "
            + "academy_id 컬럼이 부재해 부모(run) 조인 없이는 재확인할 수도 없다")
    Optional<RunRider> findByRunIdAndStudentId(Long runId, Long studentId);

    /**
     * 그 승하차지에 아직 남은(부재 처리되지 않은) 탑승자 수(API_SPEC §3.6 ③ "잔여 0명이면 {@code run_stop}
     * 을 {@code skipped}") — 방금 부재로 바꾼 학생을 포함해 센 뒤 0이면 그 정차지를 건너뛴다.
     *
     * <p>{@code runId} 근거는 {@link #findByRunIdAndStudentId} 와 같다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 — "
            + "findByRunIdAndStudentId 와 같은 근거(AcademyScope.assertAccessible 선확인)")
    long countByRunIdAndStopIdAndStatusNot(Long runId, Long stopId, RiderStatus status);

    /**
     * 그 회차에서 아직 그 상태인 라이더 수(Phase 9, RUN-06·C-15) — 하원 마지막 하차·②구간 종결 뒤의
     * 종료 판정({@code RunCompletionService})이 "아직 탑승 중({@code BOARDED})이 0명인가"를 이 메서드로
     * 묻는다.
     *
     * <p>{@code runId} 근거는 {@link #findByRunIdAndStudentId} 와 같다 — 호출부가 이미 학원 범위로
     * 좁혀 얻은 회차의 식별자만 넘긴다는 전제다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 — "
            + "findByRunIdAndStudentId 와 같은 근거(AcademyScope.assertAccessible 선확인)")
    long countByRunIdAndStatus(Long runId, RiderStatus status);

    /**
     * 승하차 처리(API_SPEC §4.6·§4.7)가 다룰 탑승자 1건 — {@code status} 가 {@link RiderStatus#ABSENT}
     * 인 행은 제외한다. §4.6 이 "{@code absent} 로 명단에서 제외된 탑승자"를 {@code 404 RIDER_NOT_FOUND}
     * 로 명시하기 때문에, 존재하지 않는 {@code riderId} 와 이미 제외된 탑승자를 이 메서드 하나로
     * 같은 404 에 합류시킨다 — 호출부가 두 사유를 따로 가를 필요가 없다.
     *
     * <p>{@code runId} 근거는 {@link #findByRunIdAndStudentId} 와 같다 — 호출부가 이미 학원 범위로
     * 좁혀 얻은 회차의 식별자라는 전제다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 RunRepository.findByIdAndAcademyId 로 이미 학원 범위에 좁혀 얻은 "
            + "회차의 식별자라는 전제다(findByRunIdAndStudentId 와 같은 근거)")
    Optional<RunRider> findByIdAndRunIdAndStatusNot(Long id, Long runId, RiderStatus excludedStatus);

    /**
     * 회차 1건의 명단 전체(목표 11, 하원 시작 시 일괄 승차) — {@link #findAllByRunIdAndAcademyId} 와
     * 달리 학원 조인이 없다. 호출부(T2 의 회차 시작 커맨드)가 이미 {@code RunRepository
     * .findByIdAndAcademyId} 로 학원 범위를 확인한 뒤 그 {@code runId} 만 넘긴다는 전제다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 RunRepository.findByIdAndAcademyId 로 이미 학원 범위에 좁혀 얻은 "
            + "회차의 식별자라는 전제다(findByRunIdAndStudentId 와 같은 근거)")
    List<RunRider> findAllByRunId(Long runId);

    /**
     * 하원 최종 지점 도착 처리에서 종료가 보류될 때(Phase 9 goal 10) 기사 화면에 실을 미하차 잔류
     * 명단 — 이름·현재 승하차지를 이 조회에서 함께 채운다({@code RunArriveResponse.remaining[]}
     * 이 별도 조회 없이 바로 응답에 실릴 수 있게).
     *
     * <p>{@code runId} 근거는 {@link #findByRunIdAndStudentId} 와 같다.
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
     * 그 승하차지에 아직 남은(부재·미승차 둘 다 빠진) 탑승자 수 — {@code no_show} 도 잔여 0명 판정에서
     * 빠져야 한다(목표 7). 다중값 NOT 제외를 메서드명만으로 표현할 수 없어 {@code @Query} 로 직접 쓴다.
     *
     * <p>{@code runId} 근거는 {@link #findByRunIdAndStudentId} 와 같다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 — "
            + "findByRunIdAndStudentId 와 같은 근거(AcademyScope.assertAccessible 선확인)")
    @Query("""
            SELECT COUNT(rr) FROM RunRider rr
            WHERE rr.runId = :runId
              AND rr.stopId = :stopId
              AND rr.status NOT IN :excludedStatuses
            """)
    long countByRunIdAndStopIdAndStatusNotIn(@Param("runId") Long runId, @Param("stopId") Long stopId,
            @Param("excludedStatuses") Collection<RiderStatus> excludedStatuses);

    /**
     * 그 승하차지에서 근접 알림(NTF-04)을 받을 학생 id 목록 — {@link RiderStatus#ABSENT} 인 학생은
     * 뺀다(C-02: {@code absent} 학생은 {@code arrive}·{@code delay} 발송 대상 밖). {@code no_show} 는
     * 이 알림이 아직 도착 전 상태에서만 발송되므로 굳이 배제하지 않는다 — 미승차 확정은 승차 시점
     * 이후에 나는 상태다.
     *
     * <p>{@code runId} 근거는 {@link #findByRunIdAndStudentId} 와 같다 — 호출부(근접 알림 스케줄러)가
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
     * 회차 목록의 탑승자를 {@code (runId, status, change)} 조합으로 묶어 센다(§5.3 {@code metrics.
     * boarded}·{@code no_show}·{@code absent} 및 {@code runs[].boarded_count}·{@code total_count}·
     * {@code added_count}·{@code removed_count}, Phase 13 T1) — 관계자 웹 대시보드 전용이라는 뜻으로
     * 메서드 이름에 용도를 남긴다(Ruling 238).
     *
     * <p>한 쿼리로 두 축(상태·변경 구분)을 함께 묶는 이유는 {@link StaffRunRiderAggregateView}
     * 자바독을 본다 — 소비 측(서비스 계층)이 필요한 축으로 접어 합산한다. {@code run_rider} 는
     * {@code academy_id} 컬럼이 부재한 부모 경유 자원이라(ERD §6.1, {@code Run} 경유) 학원 조건을
     * {@code run} 쪽 조인에 건다.
     */
    @Query("SELECT new src.backend.boarding.dto.StaffRunRiderAggregateView(rr.runId, rr.status, rr.change, "
            + "COUNT(rr)) FROM RunRider rr JOIN Run r ON r.id = rr.runId "
            + "WHERE r.academyId = :academyId AND rr.runId IN :runIds "
            + "GROUP BY rr.runId, rr.status, rr.change")
    List<StaffRunRiderAggregateView> aggregateForStaffDashboard(@Param("academyId") Long academyId,
            @Param("runIds") Collection<Long> runIds);

    /**
     * 지연 알림(NTF-06, API_SPEC §4.9)의 수신 대상 학생 id — 현재 승하차지 <b>이후</b>({@code stopIds},
     * 호출부가 seq 로 미리 걸러 넘긴다) 승하차지의 탑승자 중, 이미 탑승한 학생과 결석 처리된 학생을
     * 뺀다(수신 범위 표 "이미 탑승한 학생은 제외" · C-02 "absent 는 대상 밖").
     *
     * <p>두 배제를 <b>별개의 AND 절</b>로 쓴다 — {@code status NOT IN (BOARDED, ABSENT)} 로 합치면
     * 한쪽 배제만 지워도 나머지가 가려 결함이 드러나지 않는다.
     *
     * <p>{@code runId} 근거는 {@link #findByRunIdAndStudentId} 와 같다 — 호출부(지연 알림 커맨드
     * 서비스)가 이 메서드에 앞서 {@code runRepository.findByIdAndAcademyId} 로 이미 학원 범위에 좁혀
     * 확인한 회차의 식별자만 넘긴다는 전제다(F3 S1 라운드 1 정정 — {@code RunAssignmentAccess
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
     * 그 승하차지를 출발할 때(Ruling 308, IMPLEMENTATION_PLAN §8.23 T3 목표 3·9) 확정 결과를 통지할 학생 명단 — 승차·
     * 하차·미승차 3종만 대상이다. {@code waiting} 인 채 출발한 학생은 발송 대상이 아니다(목표 9)
     * 이라 이 조회가 이미 걸러 낸다. {@code absent} 도 뺀다 — 결석은 운행 중 승하차 처리 결과가
     * 아니라 사전에 확정된 상태라 이 통지의 대상이 아니다(근접 알림의 {@code excludingAbsent} 와
     * 같은 근거).
     *
     * <p>{@code runId} 근거는 {@link #findByRunIdAndStudentId} 와 같다 — 호출부(출발 판정, Ruling
     * 307)가 근접 알림과 같은 스케줄러로 이미 학원과 무관하게 골라낸 회차의 식별자라는 전제다.
     */
    @AcademyScopeExempt(reason = "runId 는 출발 판정 스케줄러가 근접 알림과 같은 근거로 RunRepository 로 이미 학원과 "
            + "무관하게 골라낸 회차의 식별자라는 전제다 — findStudentIdsByRunIdAndStopIdExcludingAbsent 와 같은 근거")
    @Query("""
            SELECT rr FROM RunRider rr
            WHERE rr.runId = :runId
              AND rr.stopId = :stopId
              AND rr.status IN (src.backend.boarding.entity.RiderStatus.BOARDED,
                                 src.backend.boarding.entity.RiderStatus.ALIGHTED,
                                 src.backend.boarding.entity.RiderStatus.NO_SHOW)
            """)
    List<RunRider> findFinalizedByRunIdAndStopId(@Param("runId") Long runId, @Param("stopId") Long stopId);

    /**
     * 그 회차들 중 이 학생이 명단에 있는 회차 — 학생 회차 판정이 회차마다 명단을 따로 묻지 않게 한 번에
     * 모은다(BR-058). 학원 조건은 {@code run} 부모 조인으로 건다({@link #findAllByRunIdAndAcademyId} 와 같은 형태).
     *
     * <p>버스 간 이동으로 빠진 행({@code change = removed})은 세지 않는다(BR-016) — 명단에 남는 것은 관계자·매니저
     * 표시용이고 그 학생은 그 버스에 없다. {@code change} 가 비면 변경 없음이라 포함한다.
     */
    @Query("""
            SELECT rr.runId FROM RunRider rr
            JOIN Run r ON r.id = rr.runId
            WHERE rr.studentId = :studentId
              AND rr.runId IN :runIds
              AND r.academyId = :academyId
              AND (rr.change IS NULL OR rr.change <> :removed)
            """)
    List<Long> findRunIdsByAcademyIdAndStudentIdAndRunIdIn(@Param("academyId") Long academyId,
            @Param("studentId") Long studentId, @Param("runIds") Collection<Long> runIds,
            @Param("removed") ChangeType removed);
}
