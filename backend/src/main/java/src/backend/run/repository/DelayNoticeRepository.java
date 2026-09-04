package src.backend.run.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.run.entity.DelayNotice;

/** {@link DelayNotice} 영속성 접근. */
public interface DelayNoticeRepository extends JpaRepository<DelayNotice, Long> {

    /**
     * 그 회차의 직전 발신 1건(Ruling 253 중복·갱신 판정의 대조 대상) — {@code idx_delay_notice_run_sent_at}
     * ({@code run_id}, {@code sent_at DESC})이 이 조회를 받친다.
     *
     * <p>{@code runId} 는 호출부(지연 알림 커맨드 서비스)가 {@code RunAssignmentAccess
     * #assertAssignedEscort} 로 이미 학원 범위에 좁혀 확인한 회차의 식별자라는 전제다 —
     * {@code RunRiderRepository#findByRunIdAndStudentId} 와 같은 근거.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 RunAssignmentAccess#assertAssignedEscort 로 이미 학원 범위에 "
            + "좁혀 확인한 회차의 식별자라는 전제다 — RunRiderRepository#findByRunIdAndStudentId 와 같은 근거")
    Optional<DelayNotice> findFirstByRunIdOrderBySentAtDesc(Long runId);
}
