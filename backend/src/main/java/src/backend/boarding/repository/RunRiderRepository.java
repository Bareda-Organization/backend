package src.backend.boarding.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link RunRider} 영속성 접근 — {@code run_rider} 는 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b>
 * 자원이다(ERD §6.1). 확정 배치(Phase 7)는 {@code saveAll()} 로 회차별 명단을 한 번에 쌓기만 했으나,
 * Phase 8(②구간 승인 미리보기)이 처음으로 그 명단을 다시 읽어야 해 조회 메서드를 더한다.
 * 자원이다(ERD §6.1).
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

    /**
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
}
