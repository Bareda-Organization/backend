package src.backend.exception.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.exception.entity.EmergencyAlert;
import src.backend.global.security.access.AcademyScopeExempt;

/** {@link EmergencyAlert} 영속성 접근(EXC-04, Phase 11 T2). */
public interface EmergencyAlertRepository extends JpaRepository<EmergencyAlert, Long> {

    /**
     * 신고의 처리 상태를 {@code EmergencyStatusFilter} 와 같은 규칙으로 계산해 거른다 — 취소가 확인보다 앞선다
     * (취소 뒤 확인된 신고는 {@code CANCELED}).
     */
    String SELECT_BY_STATE = "SELECT a FROM EmergencyAlert a WHERE (CASE WHEN a.canceledAt IS NOT NULL THEN 'CANCELED' "
            + "WHEN a.ackedAt IS NOT NULL THEN 'ACKED' ELSE 'OPEN' END) = :state";

    /**
     * {@code client_key} 로 재전송을 가려낸다({@code uk_emergency_alert_client_key}) —
     * {@link src.backend.boarding.command.BoardingCommandService} 와 같은 멱등성 재생 형태다.
     */
    @AcademyScopeExempt(reason = "client_key 는 클라이언트가 생성한 UUID 라 그 자체로 전 학원에서 유일하고 "
            + "추측 불가능하다(RiderStatusHistoryRepository#findByClientKey 와 같은 근거). "
            + "EmergencyCommandService#raise 가 배치·학원 범위 회차 확인 뒤에 이 조회를 하고, 찾은 행의 "
            + "run_id·type 을 요청과 대조한다(BR-078) — 배치 확인은 회차 상태를 보지 않아 종료 뒤 재전송도 통과한다")
    Optional<EmergencyAlert> findByClientKey(UUID clientKey);

    /**
     * 취소 대상 1건(목표 9) — 발신자 자신의 회차·학원으로 다시 좁힌다. 남의 신고를 {@code id} 로
     * 지목해도 이 조회가 빈 결과를 내 {@code 404 EMERGENCY_NOT_FOUND} 로 응답한다(존재 여부를
     * 드러내지 않는다, {@code StopNotFound} 와 같은 형태).
     */
    Optional<EmergencyAlert> findByIdAndRunIdAndAcademyId(Long id, Long runId, Long academyId);

    /** 확인 처리 대상 1건(목표 10) — 학원 관계자 화면은 회차를 모르고 신고 id 만 안다. */
    Optional<EmergencyAlert> findByIdAndAcademyId(Long id, Long academyId);

    /**
     * 확인 처리를 조건부 UPDATE 로 반영한다(BR-079, §5.16) — {@code acked_at IS NULL} 재확인이 "최초
     * 확인자만 기록" 의 전부다. 관계자·메인 관리자가 동시에 눌러도 먼저 행 잠금을 얻은 쪽만 갱신하고
     * 나중 쪽은 0행을 받는다({@code NoShowCaseRepository#escalateIfDue} 와 같은 형태).
     *
     * @return 영향받은 행 수 — 0 이면 이미 확인된 신고다
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "호출부(EmergencyCommandService#ack)가 역할별 범위(관계자 — findByIdAndAcademyId, "
            + "메인 관리자 — 전 학원)로 이미 찾은 신고 id 하나에 대한 조건부 갱신이다 — 그 조회가 이미 좁힌 대상이라 "
            + "이 시점에 학원을 다시 물을 근거가 없다(NoShowCaseRepository#escalateIfDue 와 같은 근거)")
    @Query("UPDATE EmergencyAlert a SET a.ackedBy = :ackedBy, a.ackedAt = :ackedAt "
            + "WHERE a.id = :id AND a.ackedAt IS NULL")
    int ackIfUnacked(@Param("id") Long id, @Param("ackedBy") Long ackedBy, @Param("ackedAt") OffsetDateTime ackedAt);

    /**
     * 학원의 비상 알림 목록 — 상태 필터(§5.16 · §6.11)를 쿼리로 건다(BR-087). 무기한 보존되는 테이블이라 자바에서
     * 거르면 호출마다 학원 전 기간 행을 읽는다. 최근 신고가 먼저 보이게 접수 역순이다.
     *
     * @param state {@link src.backend.exception.query.EmergencyStatusFilter} 의 이름({@code OPEN}·{@code ACKED}·{@code CANCELED})
     */
    @Query(SELECT_BY_STATE + " AND a.academyId = :academyId ORDER BY a.receivedAt DESC")
    List<EmergencyAlert> findAllByAcademyIdAndState(@Param("academyId") Long academyId, @Param("state") String state);

    /** {@link #findAllByAcademyIdAndState} 에 접수 시각 구간({@code [from, to)}, §5.16 {@code date})을 더한다. */
    @Query(SELECT_BY_STATE + " AND a.academyId = :academyId AND a.receivedAt >= :from AND a.receivedAt < :to "
            + "ORDER BY a.receivedAt DESC")
    List<EmergencyAlert> findAllByAcademyIdAndStateReceivedBetween(@Param("academyId") Long academyId,
            @Param("state") String state, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** 발신자가 자기 회차의 신고 상태를 조회하는 목록(§4.15) — 최근 신고가 먼저 보이게 접수 역순이다. */
    List<EmergencyAlert> findAllByRunIdAndAcademyIdOrderByReceivedAtDesc(Long runId, Long academyId);

    /**
     * 메인 관리자 콘솔의 전 학원 비상 알림 목록(목표 11, {@code GET /admin/emergencies}) —
     * {@code /admin} 은 학원 격리의 명시적 예외다(§1.5, {@code AccountRepository
     * #findStaffAccountsForConsole} 과 같은 근거). 좁힐 학원이 없는 이유도 같다: 이 화면 자체가
     * 여러 학원을 한 목록에서 보기 위한 것이다.
     */
    @AcademyScopeExempt(reason = "§6.x 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "예외를 여는 판정은 컨트롤러의 @CanMonitorAll 하나다(AccountRepository#findStaffAccountsForConsole 과 같은 형태)")
    @Query(SELECT_BY_STATE + " ORDER BY a.receivedAt DESC")
    List<EmergencyAlert> findAllByState(@Param("state") String state);

    /** 학원 관계자의 미확인 배지(§5.16 {@code unacked_count}) — 목록 필터와 무관하게 학원 전체의 미확인·미취소 건수. */
    long countByAcademyIdAndAckedAtIsNullAndCanceledAtIsNull(Long academyId);

    /** 메인 관리자 콘솔의 미확인 배지(§6.11) — 목록 필터와 무관하게 전 학원의 미확인·미취소 건수. */
    @AcademyScopeExempt(reason = "§6.11 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "배지는 전 학원의 미확인 신고를 세는 것이 목적이라 좁힐 학원이 부재")
    long countByAckedAtIsNullAndCanceledAtIsNull();
}
