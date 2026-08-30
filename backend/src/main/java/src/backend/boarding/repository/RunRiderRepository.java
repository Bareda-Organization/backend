package src.backend.boarding.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link RunRider} 영속성 접근 — {@code run_rider} 는 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b>
 * 자원이다(ERD §6.1).
 */
public interface RunRiderRepository extends JpaRepository<RunRider, Long> {

    /**
     * ③구간 미등원 토글(API_SPEC §3.6)이 상태를 옮길 그 학생의 명단 행 1건 —
     * {@code uk_run_rider_run_student} UNIQUE(암시)가 결과를 한 건으로 좁힌다.
     *
     * <p>{@code runId} 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 —
     * {@code request.repository.BoardingIntentRepository#findByRunIdAndStudentId} 와 같은 근거
     * ({@code AcademyScope.assertAccessible} 가 이 조회 전에 학원을 먼저 확인한다).
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 — 탑승 의사 토글은 "
            + "이 조회 전에 AcademyScope.assertAccessible 이 학원을 확인한다(BoardingIntentRepository.findByRunIdAndStudentId "
            + "와 같은 근거). run_rider 는 academy_id 컬럼이 부재해 부모(run) 조인 없이는 재확인할 수도 없다")
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
}
