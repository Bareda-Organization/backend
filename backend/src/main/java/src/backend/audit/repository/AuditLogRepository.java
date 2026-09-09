package src.backend.audit.repository;

import java.time.OffsetDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.audit.entity.AuditCategory;
import src.backend.audit.entity.AuditLog;

/**
 * {@link AuditLog} 영속성 접근 — 적재와 조회를 함께 갖는다(API_SPEC §6.13
 * {@code GET /admin/audit-logs}·{@code /admin/login-history}, Phase 14 T1 목표 3·4).
 *
 * <p>{@code from}·{@code to} 를 항상 구체값으로 요구한다 — {@code (:from IS NULL OR ...)} 형태로
 * {@code OffsetDateTime} 파라미터를 열어 두면 PostgreSQL 이 그 파라미터의 타입을 못 정해 500
 * (예: {@code ExceptionReportRepository} 가 문서화한 실측)이 난다. {@code academyId}·
 * {@code accountId} 는 {@code Long} 이라 같은 문제가 없어 {@code IS NULL} 분기를 그대로 둔다.
 * 미지정 필터의 기본값(연도 1~9999)은 조회 서비스가 채운다.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.category = :category
              AND (:academyId IS NULL OR a.academyId = :academyId)
              AND (:accountId IS NULL OR a.actorAccountId = :accountId)
              AND a.occurredAt >= :from
              AND a.occurredAt <= :to
            """)
    Page<AuditLog> search(@Param("category") AuditCategory category, @Param("academyId") Long academyId,
            @Param("accountId") Long accountId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            Pageable pageable);
}
