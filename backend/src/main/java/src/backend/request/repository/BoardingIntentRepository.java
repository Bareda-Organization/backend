package src.backend.request.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.request.entity.BoardingIntent;

/**
 * {@link BoardingIntent} 영속성 접근 — {@code boarding_intent} 는 {@code academy_id} 컬럼이 부재한
 * <b>부모 경유</b> 자원이다(ERD §6.1).
 */
public interface BoardingIntentRepository extends JpaRepository<BoardingIntent, Long> {

    /**
     * (회차, 학생) 단건 조회 — 한도 판정·소비·복구·{@code riding} 적용이 전부 이 행 하나를 기준으로
     * 이뤄진다({@code uk_boarding_intent_run_student} UNIQUE, 목표 5의 "한도 단위는 회차" 근거).
     *
     * <p>{@code runId} 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제 위에 있다 — 변경
     * 요청 처리든 탑승 의사 토글이든, 이 조회에 닿기 전에 {@code AcademyScope.assertAccessible} 이
     * 학원을 먼저 확인한다({@code DeviceTokenRepository} 와 같은 근거). {@code boarding_intent} 는
     * {@code academy_id} 컬럼이 부재해 부모({@code run}) 조인 없이는 여기서 재확인할 수도 없다 —
     * 조인해 재확인하려면 이 조회 하나를 위해 {@code run} 을 매번 함께 읽어야 해, 호출부가 이미 한
     * 검증을 한 번 더 반복하는 셈이다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 — "
            + "변경 요청 처리·탑승 의사 토글 어느 진입 경로든 이 조회 전에 AcademyScope.assertAccessible 이 "
            + "학원을 확인한다(DeviceTokenRepository.findByAccountIdAndDeviceId 와 같은 근거). boarding_intent 는 "
            + "academy_id 컬럼이 부재해 부모(run) 조인 없이는 이 조회 하나를 위해 재확인할 수도 없다")
    Optional<BoardingIntent> findByRunIdAndStudentId(Long runId, Long studentId);

    /**
     * ①구간 토글이 남긴 {@code riding=false} 학생 목록 — 확정 배치(목표 1)가 명단을 만들기 전에 이
     * 목록을 걸러내야 {@code run_rider} 에 그 학생이 아예 생기지 않는다({@code RunConfirmationService}
     * 가 {@code DailyRoster} 를 만들기 직전에 호출).
     *
     * <p>{@code runId} 는 확정 배치 스케줄러 자신이 {@code RunRepository.findByStatusAndConfirmAtLessThanEqual
     * AndCanceledAtIsNullOrderByConfirmAtAsc} 로 이미 학원 범위를 거치지 않고 얻은 내부 식별자다 — 외부
     * 요청이 아니라 배치 프로세스 스스로가 만든 값이라 호출부에 별도 학원 확인 지점이 없고, 이 조회
     * 자체도 그 값을 그대로 받아 쓸 뿐이다.
     */
    @AcademyScopeExempt(reason = "runId 는 확정 배치 스케줄러가 내부적으로 순회하는 식별자다 — 외부 요청이 닿는 "
            + "경로가 아니라 사용자 학원 범위를 확인할 지점 자체가 없다(RunRepository 의 배치 전용 조회와 같은 근거). "
            + "boarding_intent 는 academy_id 컬럼이 부재해 조인 없이는 재확인할 수도 없다")
    @Query("SELECT b.studentId FROM BoardingIntent b WHERE b.runId = :runId AND b.riding = false")
    List<Long> findStudentIdsByRunIdAndRidingFalse(@Param("runId") Long runId);
}
