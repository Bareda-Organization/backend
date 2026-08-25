package src.backend.audit.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.audit.entity.AuditLog;

/**
 * {@link AuditLog} 영속성 접근 — 지금은 <b>적재 전용</b>이다.
 *
 * <p>조회 메서드를 선언하지 않은 것이 의도다. 감사·접속 이력 <b>조회</b>(API_SPEC §6.13
 * {@code GET /admin/audit-logs}·{@code /admin/login-history})는 Phase 14 소유라, 여기에 미리
 * 조회를 만들어 두면 아무도 부르지 않는 조회가 학원 격리 검사만 통과한 채 남는다.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
