package src.backend.exception.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.exception.entity.NoShowCase;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link NoShowCase} 영속성 접근 — {@code no_show_case} 는 {@code run_rider} 를 부모로 두는 부모 경유
 * 자원이다(ERD §6.1). Phase 9 목표 7 이 처음으로 이 저장소를 만든다: 승하차 처리가 {@code no_show} 로
 * 전이할 때 케이스를 만들고, 그 응답 조립이 이미 있는 케이스를 되읽는다. Phase 11 목표 1(에스컬레이션)이
 * 폴링 조회 2건을 더한다.
 */
public interface NoShowCaseRepository extends JpaRepository<NoShowCase, Long> {

    /**
     * 그 탑승자의 미승차 케이스 — {@code run_rider_id} 가 UNIQUE 라 한 건 이하다(목표 7·12, 응답의
     * {@code no_show_case} 조립 근거).
     *
     * <p>{@code runRiderId} 는 호출부가 {@code RunRiderRepository.findByIdAndRunIdAndStatusNot} 으로
     * 이미 학원 범위로 확인한 탑승자의 식별자라는 전제다.
     */
    @AcademyScopeExempt(reason = "runRiderId 는 호출부가 RunRiderRepository.findByIdAndRunIdAndStatusNot 으로 "
            + "이미 학원 범위로 확인한 탑승자의 식별자라는 전제다")
    Optional<NoShowCase> findByRunRiderId(Long runRiderId);

    /**
     * 에스컬레이션 폴링 대상(Phase 11 목표 1, API_SPEC §4.8) — 대기 만료({@code expires_at <= now})
     * + 무응답({@code escalated_at IS NULL}) + 미해소({@code resolved_at IS NULL}).
     *
     * <p>시각이 촉발하는 <b>전 학원 대상</b> 조회라 좁힐 학원이 없다 — 학원 하나로 좁히면 나머지 학원의
     * 도래분이 에스컬레이션되지 않는다({@code ChangeRequestRepository.findByStatusAndDeadlineAtLessThanEqual}
     * 과 같은 근거). 호출부가 {@code NoShowEscalationScheduler} 뿐이라는 전제 — 요청 경로에서 부르면
     * 이 예외가 우회로가 된다. {@code ix_no_show_case_expires ON no_show_case (expires_at) WHERE
     * escalated_at IS NULL} 부분 인덱스가 이 조회 모양을 이미 전제하고 있다(V1__init_schema.sql).
     *
     * <p>{@code pageable} 은 한 틱이 한 번에 집는 상한이다 — 상한 없이 전건을 집으면 만료가 몰린
     * 틱 하나가 커넥션을 오래 붙든다({@code ChangeRequestRepository} 와 같은 근거).
     */
    @AcademyScopeExempt(reason = "에스컬레이션 폴링은 시각이 촉발하는 전 학원 대상 조회라 좁힐 학원이 부재하다 — "
            + "학원 하나로 좁히면 나머지 학원의 도래분이 에스컬레이션되지 않는다. 호출부는 "
            + "NoShowEscalationScheduler 뿐이라는 전제(ChangeRequestRepository 의 자동 거절 폴링과 같은 근거) — "
            + "요청 경로에서 부르면 이 예외가 우회로가 된다")
    @Query("SELECT c FROM NoShowCase c WHERE c.escalatedAt IS NULL AND c.resolvedAt IS NULL "
            + "AND c.expiresAt <= :now ORDER BY c.expiresAt ASC")
    List<NoShowCase> findDueForEscalation(@Param("now") OffsetDateTime now, Pageable pageable);

    /**
     * 에스컬레이션을 조건부 UPDATE 로 반영한다(목표 1, 동시성) — 영향받은 행 수로 성공 여부를
     * 판정한다. {@code WHERE escalated_at IS NULL AND resolved_at IS NULL} 재확인이 멱등성의 전부다 —
     * 스케줄러 틱 두 번이 같은 케이스를 동시에 집어도, 먼저 행 잠금을 얻은 쪽만 갱신하고 나중 쪽은
     * 0행을 받는다({@code ChangeRequestRepository#autoRejectIfPending} 과 같은 근거). {@code SELECT}
     * 로 먼저 상태를 본 뒤 갱신하면 그 사이에 경쟁자가 끼어들 수 있어 이 보장이 성립하지 않는다.
     *
     * <p>{@code resolved_at} 도 재확인하는 이유 — 폴링({@link #findDueForEscalation})과 이 UPDATE
     * 사이에 사용자가 연락에 응답해({@code NoShowCase#resolveByAnswer}) 해소될 수 있다. {@code SELECT}
     * 시점엔 미해소였어도 UPDATE 시점엔 해소됐을 수 있어, 조건에서 빠뜨리면 이미 끝난 케이스를
     * 에스컬레이션하는 경쟁이 생긴다.
     *
     * @return 영향받은 행 수. 0이면 이미 처리됐거나(경쟁 패배·응답 도착) 대상이 없는 것이다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "위 폴링 조회가 이미 전 학원 시각 촉발로 골라낸 no_show_case.id 하나에 대한 "
            + "단건 조건부 갱신이다 — 그 조회가 이미 좁힌 대상이라 이 시점에 학원을 다시 물을 근거가 없다"
            + "(ChangeRequestRepository#autoRejectIfPending 과 같은 근거)")
    @Query("UPDATE NoShowCase c SET c.escalatedAt = :now WHERE c.id = :id "
            + "AND c.escalatedAt IS NULL AND c.resolvedAt IS NULL")
    int escalateIfDue(@Param("id") Long id, @Param("now") OffsetDateTime now);
}
