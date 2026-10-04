package src.backend.audit.repository;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
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
 * {@link #search} 가 필터 조합별 조회로 갈라 {@code IS NULL} 분기를 두지 않는다(BR-089) — {@code action} 도 같다: 안 고르면
 * 전 동작 목록({@link #ALL_ACTIONS})을 넘긴다. 미지정 필터의 기본값(연도 1~9999)은 조회 서비스가 채운다.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** 동작을 거르지 않는다는 뜻 — 접속 이력은 네 동작을 전부, 감사 로그는 {@code action} 을 안 준 요청이 쓴다. */
    List<AuditAction> ALL_ACTIONS = Arrays.asList(AuditAction.values());

    /**
     * 감사·접속 이력 검색(§6.13) — 주어진 필터에 맞는 조회 하나로 보낸다(BR-089).
     *
     * <p>한 쿼리에 {@code (:academyId IS NULL OR ...)} 를 두면, 준비문이 일반 계획으로 바뀐 뒤 어느 인덱스도 쓸 수
     * 없다. 필터 조합마다 조건을 고정해 {@code (academy_id|actor_account_id|category, occurred_at)} 인덱스를 탄다.
     * {@code from}·{@code to} 는 양끝을 포함한다.
     */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "academy_id 는 선택 필터이고, 받은 값은 아래 학원 조건 조회로 그대로 넘긴다")
    default Page<AuditLog> search(AuditCategory category, Collection<AuditAction> actions, Long academyId,
            Long accountId, OffsetDateTime from, OffsetDateTime to, Pageable pageable) {
        if (academyId != null && accountId != null) {
            return findAllByCategoryAndActionInAndAcademyIdAndActorAccountIdAndOccurredAtBetween(category, actions,
                    academyId, accountId, from, to, pageable);
        }
        if (academyId != null) {
            return findAllByCategoryAndActionInAndAcademyIdAndOccurredAtBetween(category, actions, academyId, from, to,
                    pageable);
        }
        if (accountId != null) {
            return findAllByCategoryAndActionInAndActorAccountIdAndOccurredAtBetween(category, actions, accountId, from,
                    to, pageable);
        }
        return findAllByCategoryAndActionInAndOccurredAtBetween(category, actions, from, to, pageable);
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
            return search(AuditCategory.LOGIN, ALL_ACTIONS, academyId, null, from, to, pageable);
        }
        if (academyId != null) {
            return findLoginHistoryByAcademyAndAccount(AuditCategory.LOGIN, academyId, accountId, from, to, pageable);
        }
        return findLoginHistoryByAccount(AuditCategory.LOGIN, accountId, from, to, pageable);
    }

    /**
     * 학원·계정 둘 다 지정한 접속 이력 검색 — 해제 행은 해제된 계정으로 맞춘다.
     *
     * <p>{@code unblock} 을 바인딩 파라미터가 아니라 <b>리터럴</b>로 쓴다(Ruling 632) — 해제 행만 색인하는 부분 인덱스
     * {@code ix_audit_log_unblock_target} 의 조건({@code action = 'unblock'})을 일반(generic) 계획도 함의로 받아들이게 하려는 것이다.
     */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.category = :category AND a.academyId = :academyId
              AND a.occurredAt BETWEEN :from AND :to
              AND ((a.action <> src.backend.audit.entity.AuditAction.UNBLOCK AND a.actorAccountId = :accountId)
                   OR (a.action = src.backend.audit.entity.AuditAction.UNBLOCK AND a.targetId = :accountId))
            """)
    Page<AuditLog> findLoginHistoryByAcademyAndAccount(@Param("category") AuditCategory category,
            @Param("academyId") Long academyId, @Param("accountId") Long accountId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            Pageable pageable);

    /**
     * 계정만 지정한 접속 이력 검색 — 해제 행은 해제된 계정으로 맞춘다. 두 갈래({@code actor_account_id} · 해제 행의
     * {@code target_id})가 각자 인덱스({@code ix_audit_log_actor_occurred} · {@code ix_audit_log_unblock_target})를 타 {@code BitmapOr}
     * 로 합쳐진다 — 해제 행의 접근 경로가 없을 때는 일치 행이 적은 계정이 기간 전체를 훑었다(실측 15,674 버퍼 → 18).
     */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "예외를 여는 판정은 컨트롤러의 @CanReadAudit 하나다")
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.category = :category
              AND a.occurredAt BETWEEN :from AND :to
              AND ((a.action <> src.backend.audit.entity.AuditAction.UNBLOCK AND a.actorAccountId = :accountId)
                   OR (a.action = src.backend.audit.entity.AuditAction.UNBLOCK AND a.targetId = :accountId))
            """)
    Page<AuditLog> findLoginHistoryByAccount(@Param("category") AuditCategory category,
            @Param("accountId") Long accountId,
            @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to, Pageable pageable);

    /** 학원·계정 둘 다 지정한 검색. */
    Page<AuditLog> findAllByCategoryAndActionInAndAcademyIdAndActorAccountIdAndOccurredAtBetween(
            AuditCategory category, Collection<AuditAction> actions, Long academyId, Long actorAccountId,
            OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    /** 학원만 지정한 검색. */
    Page<AuditLog> findAllByCategoryAndActionInAndAcademyIdAndOccurredAtBetween(AuditCategory category,
            Collection<AuditAction> actions, Long academyId, OffsetDateTime from, OffsetDateTime to,
            Pageable pageable);

    /** 계정만 지정한 검색 — 메인 관리자 콘솔(§6.13)이라 학원으로 좁히지 않는다. */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "예외를 여는 판정은 컨트롤러의 @CanReadAudit 하나다")
    Page<AuditLog> findAllByCategoryAndActionInAndActorAccountIdAndOccurredAtBetween(AuditCategory category,
            Collection<AuditAction> actions, Long actorAccountId, OffsetDateTime from, OffsetDateTime to,
            Pageable pageable);

    /** 필터 없는 첫 화면 — {@code ix_audit_log_category_occurred} 를 탄다. */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "예외를 여는 판정은 컨트롤러의 @CanReadAudit 하나다")
    Page<AuditLog> findAllByCategoryAndActionInAndOccurredAtBetween(AuditCategory category,
            Collection<AuditAction> actions, OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    /**
     * 보존 정리 배치(R46 감사 B)가 지울 id 를 오래된 순으로 {@code limit} 만큼 — 카테고리별로 불러 기존
     * {@code ix_audit_log_category_occurred} 를 탄다(시간만으로 읽는 인덱스를 따로 두면 첫 화면 조회의 실행 계획이
     * 그쪽으로 옮겨 간다). 전 학원의 만료 행이 대상이라 학원 조건을 걸지 않는다.
     */
    @AcademyScopeExempt(reason = "보존 정리 배치 — 전 학원의 만료 감사 행이 대상이고, 부르는 주체가 사용자 요청이 아니라 "
            + "스케줄러라 요청 주체의 소속 자체가 부재")
    @Query("select a.id from AuditLog a where a.category = :category and a.occurredAt < :cutoff order by a.occurredAt")
    List<Long> findIdsForRetentionCleanup(@Param("category") AuditCategory category,
            @Param("cutoff") OffsetDateTime cutoff, Limit limit);

    /**
     * 로그인 이력 행의 날짜별 행위 수(§6.18 {@code logins} · {@code daily}) — 서울 날짜로 가른다. {@code ix_audit_log_category_occurred} 가 받친다.
     * 학원 필터를 걸지 않는다(계정·서버 단위 값이다, §6.18).
     */
    @AcademyScopeExempt(reason = "메인 관리자 대시보드(§6.18)의 로그인 집계는 학원 필터를 걸지 않는 계정·서버 단위 값이다 — 접속 이력 §6.13 과 같은 원천이고 "
            + "호출부는 @CanMonitorAll 로 보호되는 AdminDashboardQueryService 뿐이라는 전제")
    @Query(value = """
            SELECT (a.occurred_at AT TIME ZONE :zone)::date AS "day", a.action AS "action", COUNT(*) AS "total"
              FROM audit_log a
             WHERE a.category = 'login' AND a.occurred_at >= :from AND a.occurred_at < :to
             GROUP BY 1, 2
            """, nativeQuery = true)
    List<LoginActionCount> countLoginActionsByDay(@Param("zone") String zone, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /** 기간의 차단 중 <b>지금은 풀린</b> 것의 수(§6.18 {@code logins.blocks_released}) — 차단된 계정이 지금 {@code blocked} 가 아니다. */
    @AcademyScopeExempt(reason = "countLoginActionsByDay 와 같은 근거와 같은 호출부 전제 — 계정 단위 집계라 학원 필터를 걸지 않는다")
    @Query(value = """
            SELECT COUNT(*) FROM audit_log a JOIN account c ON c.id = a.actor_account_id
             WHERE a.category = 'login' AND a.action = 'block' AND a.occurred_at >= :from AND a.occurred_at < :to
               AND c.status <> 'blocked'
            """, nativeQuery = true)
    long countReleasedBlocks(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
