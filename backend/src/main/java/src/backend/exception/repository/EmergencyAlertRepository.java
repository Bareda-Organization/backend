package src.backend.exception.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.exception.entity.EmergencyAlert;
import src.backend.global.security.access.AcademyScopeExempt;

/** {@link EmergencyAlert} 영속성 접근(EXC-04, Phase 11 T2). */
public interface EmergencyAlertRepository extends JpaRepository<EmergencyAlert, Long> {

    /**
     * {@code client_key} 로 재전송을 가려낸다({@code uk_emergency_alert_client_key}) —
     * {@link src.backend.boarding.command.BoardingCommandService} 와 같은 멱등성 재생 형태다.
     */
    @AcademyScopeExempt(reason = "client_key 는 클라이언트가 생성한 UUID 라 그 자체로 전 학원에서 유일하고 "
            + "추측 불가능하다(RiderStatusHistoryRepository#findByClientKey 와 같은 근거). "
            + "EmergencyCommandService#raise 가 이 조회를 배치(assignment) 검증보다 먼저 두는 것이 "
            + "의도된 순서다 — 재확인하면 그 사이 회차가 끝난 정상 재전송까지 막는다")
    Optional<EmergencyAlert> findByClientKey(UUID clientKey);

    /**
     * 취소 대상 1건(목표 9) — 발신자 자신의 회차·학원으로 다시 좁힌다. 남의 신고를 {@code id} 로
     * 지목해도 이 조회가 빈 결과를 내 {@code 404 EMERGENCY_NOT_FOUND} 로 응답한다(존재 여부를
     * 드러내지 않는다, {@code StopNotFound} 와 같은 형태).
     */
    Optional<EmergencyAlert> findByIdAndRunIdAndAcademyId(Long id, Long runId, Long academyId);

    /** 확인 처리 대상 1건(목표 10) — 학원 관계자 화면은 회차를 모르고 신고 id 만 안다. */
    Optional<EmergencyAlert> findByIdAndAcademyId(Long id, Long academyId);

    /** 학원 관계자 화면의 비상 알림 목록(목표 10) — 최근 신고가 먼저 보이게 접수 역순이다. */
    List<EmergencyAlert> findAllByAcademyIdOrderByReceivedAtDesc(Long academyId);

    /**
     * 메인 관리자 콘솔의 전 학원 비상 알림 목록(목표 11, {@code GET /admin/emergencies}) —
     * {@code /admin} 은 학원 격리의 명시적 예외다(§1.5, {@code AccountRepository
     * #findStaffAccountsForConsole} 과 같은 근거). 좁힐 학원이 없는 이유도 같다: 이 화면 자체가
     * 여러 학원을 한 목록에서 보기 위한 것이다.
     */
    @AcademyScopeExempt(reason = "§6.x 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "예외를 여는 판정은 컨트롤러의 @CanMonitorAll 하나다(AccountRepository#findStaffAccountsForConsole 과 같은 형태)")
    List<EmergencyAlert> findAllByOrderByReceivedAtDesc();
}
