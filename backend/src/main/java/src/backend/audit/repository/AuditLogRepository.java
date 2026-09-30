package src.backend.audit.repository;

import java.time.OffsetDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditCategory;
import src.backend.audit.entity.AuditLog;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link AuditLog} 영속성 접근 — 적재와 조회를 함께 갖는다(API_SPEC §6.13
 * {@code GET /admin/audit-logs}·{@code /admin/login-history}, Phase 14 T1 목표 3·4).
 *
 * <p>{@code from}·{@code to} 를 항상 구체값으로 요구한다 — {@code (:from IS NULL OR ...)} 형태로
 * {@code OffsetDateTime} 파라미터를 열어 두면 PostgreSQL 이 그 파라미터의 타입을 못 정해 500
 * (예: {@code ExceptionReportRepository} 가 문서화한 실측)이 난다. {@code academyId}·{@code accountId} 는
 * {@link #search} 가 필터 조합별 조회로 갈라 {@code IS NULL} 분기를 두지 않는다(BR-089).
 * 미지정 필터의 기본값(연도 1~9999)은 조회 서비스가 채운다.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 감사·접속 이력 검색(§6.13) — 주어진 필터에 맞는 조회 하나로 보낸다(BR-089).
     *
     * <p>한 쿼리에 {@code (:academyId IS NULL OR ...)} 를 두면, 준비문이 일반 계획으로 바뀐 뒤 어느 인덱스도 쓸 수
     * 없다. 필터 조합마다 조건을 고정해 {@code (academy_id|actor_account_id|category, occurred_at)} 인덱스를 탄다.
     * {@code from}·{@code to} 는 양끝을 포함한다.
     */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "academy_id 는 선택 필터이고, 받은 값은 아래 학원 조건 조회로 그대로 넘긴다")
    default Page<AuditLog> search(AuditCategory category, Long academyId, Long accountId, OffsetDateTime from,
            OffsetDateTime to, Pageable pageable) {
        if (academyId != null && accountId != null) {
            return findAllByCategoryAndAcademyIdAndActorAccountIdAndOccurredAtBetween(category, academyId, accountId,
                    from, to, pageable);
        }
        if (academyId != null) {
            return findAllByCategoryAndAcademyIdAndOccurredAtBetween(category, academyId, from, to, pageable);
        }
        if (accountId != null) {
            return findAllByCategoryAndActorAccountIdAndOccurredAtBetween(category, accountId, from, to, pageable);
        }
        return findAllByCategoryAndOccurredAtBetween(category, from, to, pageable);
    }

    /**
     * 접속 이력 검색(§6.13 {@code login-history}) — {@link #search} 와 같되 <b>계정 필터의 뜻이 다르다</b>(BR-219).
     * 해제({@code unblock}) 행은 "해제된 계정" 의 이력이라 {@code target_id} 로 맞추고, 나머지는 행위자로 맞춘다 —
     * 해제 행의 행위자는 해제한 관리자이고 그 기록은 감사 로그(§6.12)의 몫이다. 계정 필터가 없으면 {@link #search} 다.
     */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "academy_id 는 선택 필터이고, 받은 값은 아래 학원 조건 조회로 그대로 넘긴다")
    default Page<AuditLog> searchLoginHistory(Long academyId, Long accountId, OffsetDateTime from, OffsetDateTime to,
            Pageable pageable) {
        if (accountId == null) {
            return search(AuditCategory.LOGIN, academyId, null, from, to, pageable);
        }
        if (academyId != null) {
            return findLoginHistoryByAcademyAndAccount(AuditCategory.LOGIN, AuditAction.UNBLOCK, academyId, accountId,
                    from, to, pageable);
        }
        return findLoginHistoryByAccount(AuditCategory.LOGIN, AuditAction.UNBLOCK, accountId, from, to, pageable);
    }

    /** 학원·계정 둘 다 지정한 접속 이력 검색 — 해제 행은 해제된 계정으로 맞춘다. */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.category = :category AND a.academyId = :academyId
              AND a.occurredAt BETWEEN :from AND :to
              AND ((a.action <> :unblock AND a.actorAccountId = :accountId)
                   OR (a.action = :unblock AND a.targetId = :accountId))
            """)
    Page<AuditLog> findLoginHistoryByAcademyAndAccount(@Param("category") AuditCategory category,
            @Param("unblock") AuditAction unblock, @Param("academyId") Long academyId,
            @Param("accountId") Long accountId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            Pageable pageable);

    /** 계정만 지정한 접속 이력 검색 — 해제 행은 해제된 계정으로 맞춘다. */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "예외를 여는 판정은 컨트롤러의 @CanReadAudit 하나다")
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.category = :category
              AND a.occurredAt BETWEEN :from AND :to
              AND ((a.action <> :unblock AND a.actorAccountId = :accountId)
                   OR (a.action = :unblock AND a.targetId = :accountId))
            """)
    Page<AuditLog> findLoginHistoryByAccount(@Param("category") AuditCategory category,
            @Param("unblock") AuditAction unblock, @Param("accountId") Long accountId,
            @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to, Pageable pageable);

    /** 학원·계정 둘 다 지정한 검색. */
    Page<AuditLog> findAllByCategoryAndAcademyIdAndActorAccountIdAndOccurredAtBetween(AuditCategory category,
            Long academyId, Long actorAccountId, OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    /** 학원만 지정한 검색. */
    Page<AuditLog> findAllByCategoryAndAcademyIdAndOccurredAtBetween(AuditCategory category, Long academyId,
            OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    /** 계정만 지정한 검색 — 메인 관리자 콘솔(§6.13)이라 학원으로 좁히지 않는다. */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "예외를 여는 판정은 컨트롤러의 @CanReadAudit 하나다")
    Page<AuditLog> findAllByCategoryAndActorAccountIdAndOccurredAtBetween(AuditCategory category,
            Long actorAccountId, OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    /** 필터 없는 첫 화면 — {@code ix_audit_log_category_occurred} 를 탄다. */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "예외를 여는 판정은 컨트롤러의 @CanReadAudit 하나다")
    Page<AuditLog> findAllByCategoryAndOccurredAtBetween(AuditCategory category, OffsetDateTime from,
            OffsetDateTime to, Pageable pageable);
}
