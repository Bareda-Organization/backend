package src.backend.account.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import src.backend.account.entity.Account;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.persistence.AcademyCount;
import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.global.security.authz.Permissions;

/**
 * {@link AccountRepository} 의 메인 관리자 콘솔 · 대시보드 · 감사 화면용 조회 묶음 — 전 학원 범위로 계정을 읽는 조회와 집계라 로그인 · 복구 · 본인 조회와
 * 가른다. 원래 저장소가 이 인터페이스를 상속하므로 메서드 이름 · 시그니처 · 애너테이션은 그대로이고 호출부는 {@link AccountRepository} 를 쓴다.
 */
@NoRepositoryBean
public interface AccountConsoleQueries extends Repository<Account, Long> {

    /**
     * 학원별 소속 사용자 수(API_SPEC §6.1 {@code user_count}) — 역할과 상태를 인자로 받아 무엇을 세는지
     * 호출부가 정한다.
     *
     * <p><b>상태를 거는 것이 이 조회의 핵심이다</b>(Ruling 142). 걸지 않으면 승인 대기·거절된 계정까지
     * 합산돼, 짝 필드 {@code staff_count}(재직자만 셈)와 같은 응답 안에서 집계 기준이 갈린다 — 관리자가
     * 읽는 "소속 사용자 수" 가 실제보다 부풀려진다.
     *
     * <p>한 페이지의 학원 전부를 한 번에 센다 — 학원마다 세면 한 페이지(최대 100건)가 질의 100건이 된다.
     */
    @Query("SELECT a.academyId AS academyId, COUNT(a) AS total FROM Account a "
            + "WHERE a.academyId IN :academyIds AND a.role IN :roles AND a.status IN :statuses "
            + "GROUP BY a.academyId")
    List<AcademyCount> countByAcademyIdInGroupedByAcademyId(@Param("academyIds") Collection<Long> academyIds,
            @Param("roles") Collection<Role> roles, @Param("statuses") Collection<AccountStatus> statuses);

    /**
     * 여러 계정의 이름·연락처를 한 번에 가져온다 — 메인 관리자 콘솔의 관계자 가입 요청 목록
     * (API_SPEC §6.4)이 요청 행에 신청자 정보를 채울 때 쓰고, 비상 알림 목록
     * ({@code EmergencyStaffQueryService})이 확인자(acked_by)를 이름으로 바꿀 때도 쓴다.
     *
     * <p>학원 조건을 함께 걸 수 없는 것이 §6.3 의 {@link AccountRepository#findAllByAcademyIdAndIdIn} 과 갈리는
     * 지점이다 — 그쪽은 목록 전체가 한 학원이고 이쪽은 한 페이지(또는 목록)에 여러 학원이 섞일 수 있다.
     */
    @AcademyScopeExempt(reason = "§6.4 메인 관리자 콘솔의 전 학원 조회(ARCHITECTURE §6.2 격리 예외) — 한 페이지에 여러 학원이 "
            + "섞여 좁힐 학원이 부재. 호출부가 이미 학원 범위로 확인된 조회가 돌려준 account_id 만 넘긴다는 전제 "
            + "(승인 큐 조회의 account_id, 또는 emergency_alert.acked_by — 후자는 토큰의 accountId 로만 채워져 "
            + "요청 파라미터가 아니다, 또는 §6.13 접속 이력 해제 행의 target_id·actor_account_id — 감사 행에서 읽은 값, 또는 보존 정리 배치(Ruling 480 ②)가 파기 대상 학생에서 읽은 계정 id — 호출 주체가 스케줄러) — 요청 파라미터의 식별자를 직접 넘기면 임의 계정의 연락처를 읽는 통로가 된다")
    List<Account> findAllByIdIn(Collection<Long> ids);

    /**
     * 차단 계정 목록(API_SPEC §6.10 {@code GET /admin/blocked-accounts}) — {@code blocked} 계정 중 차단 사유가 {@code retentionReason}
     * 인 것(퇴원 보존 익명화)을 뺀다. 사유가 없는 차단 행도 목록에 든다.
     *
     * <p>정렬은 {@code Pageable} 이 실어 온다 — 파생 쿼리 이름에 {@code OrderBy} 를 박으면
     * {@code Pageable} 의 정렬과 어느 쪽이 이기는지가 호출부에서 보이지 않는다.
     */
    @AcademyScopeExempt(reason = "§6.10 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "차단 해제는 계정 단위 조치라 대상을 학원으로 좁히면 운영사가 어느 학원에서 사고가 났는지 "
            + "알아야만 목록을 볼 수 있게 된다. 예외를 여는 판정은 컨트롤러의 @Can(Permissions.ACCOUNT_UNBLOCK) 하나다")
    @Query("SELECT a FROM Account a WHERE a.status = src.backend.global.common.enums.AccountStatus.BLOCKED "
            + "AND (a.blockReason IS NULL OR a.blockReason <> :retentionReason)")
    Page<Account> findAllBlockedExcludingReason(@Param("retentionReason") String retentionReason, Pageable pageable);

    /** 로그인 실패 등으로 막힌 계정 — 퇴원 보존 익명화로 막힌 계정({@link Account#RETENTION_BLOCK_REASON})은 뺀다(API_SPEC §6.10). */
    @AcademyScopeExempt(reason = "§6.10 메인 관리자 콘솔 — findAllBlockedExcludingReason 을 사유 상수로 부를 뿐이며 전 학원 범위 근거가 같다")
    default Page<Account> findAllLoginBlocked(Pageable pageable) {
        return findAllBlockedExcludingReason(Account.RETENTION_BLOCK_REASON, pageable);
    }

    /**
     * 전 학원 소속 사용자 수(API_SPEC §6.1 {@code summary.user_count}) — {@link #countByAcademyIdInGroupedByAcademyId} 와 같은 역할·상태
     * 기준을 학원 구분 없이 한 번에 센다. 역할 4종은 학원 소속이 필수라(ck_account_academy_scope) 메인 관리자는 섞이지 않는다.
     */
    @AcademyScopeExempt(reason = "§6.1 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "summary 는 목록 필터·쪽과 무관한 전 학원 합계라 학원으로 좁히면 값의 뜻이 사라진다. 예외 판정은 컨트롤러의 "
            + "@Can(Permissions.ACADEMY_MANAGE) 하나다")
    long countByRoleInAndStatusIn(Collection<Role> roles, Collection<AccountStatus> statuses);

    /**
     * 로그인 아이디 여럿의 계정을 한 번에 가져온다(API_SPEC §6.13 {@code actor_name} — 감사 행이 계정 id 를 못 남긴 경우의 대체 경로).
     */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — 감사 이력은 전 학원 범위이고 행위자는 어느 학원의 계정이든 될 수 있어 "
            + "학원으로 좁히면 화면이 성립하지 않는다. 로그인 아이디는 전 학원 통틀어 유일하다(existsByLoginId). 호출부가 감사 행에서 읽은 "
            + "값만 넘긴다는 전제 — 요청 파라미터를 직접 넘기면 임의 계정의 이름을 읽는 통로가 된다. 예외 판정은 컨트롤러의 @Can(Permissions.AUDIT_READ) 하나다")
    List<Account> findAllByLoginIdIn(Collection<String> loginIds);

    /**
     * 특정 역할·상태 집합의 계정 전부(EXC-04) — 비상 알림이 메인 관리자 전원에게 설정과
     * 무관하게 동시 도달해야 하는데, 메인관리자는 {@code academy_staff} 소속이 없어
     * {@link src.backend.academy.repository.AcademyStaffRepository#findActiveAccountsByAcademyId} 로
     * 찾을 수 없다 — 이 조회가 그 갈래를 담당한다. 상태 집합은 호출부가 {@link AccountStatus#NOTIFIABLE} 로 넘긴다(Ruling 865).
     */
    @AcademyScopeExempt(reason = "메인관리자(SYSTEM_ADMIN)는 academy_id 가 null 이라(ck_account_academy_scope) "
            + "학원으로 좁힐 수 없다 — 이 역할 자체가 전 학원 범위라는 것이 §1.5 의 정의(Role#hasPlatformScope)다. "
            + "role=SYSTEM_ADMIN 조건이 이미 좁힌 대상이라 학원 조건을 더할 근거가 없다")
    List<Account> findAllByRoleAndStatusIn(Role role, Collection<AccountStatus> statuses);

    /**
     * {@link #findAllByRoleAndStatusIn} 와 같은 대상을 개수만 센다(API_SPEC §4.14 {@code notified}) — 비상 신고 응답이 실제 발송 대상 이름까지는 필요 없고 도달 수신자 수만 필요해,
     * 계정 전체를 불러 크기를 재는 대신 count 전용 질의로 좁힌다.
     */
    @AcademyScopeExempt(reason = "메인관리자(SYSTEM_ADMIN)는 academy_id 가 null 이라(ck_account_academy_scope) "
            + "학원으로 좁힐 수 없다 — §1.5 정의(Role#hasPlatformScope)와 {@link #findAllByRoleAndStatusIn} 가 "
            + "이미 같은 판단을 내렸다")
    long countByRoleAndStatusIn(Role role, Collection<AccountStatus> statuses);

    /**
     * 감사 화면의 행위자 찾기(API_SPEC §6.13 {@code GET /admin/audit-actors}) — 이름 또는 로그인 아이디에
     * {@code q} 가 들어 있는 계정을 이름순으로 {@code limit} 건까지. 빈 검색어는 전건과 같아지므로 호출부가 막는다.
     * {@code q} 는 호출부가 {@link src.backend.global.persistence.LikeEscape} 로 이스케이프해 넘기고 이 쿼리는
     * {@code ESCAPE '\'} 로 해석만 한다(BR-376).
     */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — 감사 이력은 전 학원 범위이고 행위자는 어느 학원의 계정이든 될 수 있어 "
            + "학원으로 좁히면 화면이 성립하지 않는다. 예외 판정은 컨트롤러의 @Can(Permissions.AUDIT_READ) 하나다")
    @Query("""
            SELECT a FROM Account a
            WHERE LOWER(a.name) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\'
               OR LOWER(a.loginId) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\'
            ORDER BY a.name, a.id
            """)
    List<Account> searchByNameOrLoginId(@Param("q") String q, Limit limit);

    /** 지금 차단된 계정 수(§6.18 {@code attention.blocked_accounts}, §6.10) 중 {@code retentionReason} 으로 막힌 것을 뺀 수 — 계정 단위 조치라 전 학원 범위다. */
    @AcademyScopeExempt(reason = "차단 계정 수(§6.18 · §6.10)는 계정 단위 조치라 전 학원 범위로 센다 — findAllBlockedExcludingReason 과 같은 근거. 호출부는 "
            + "@Can(Permissions.MONITOR_ALL) 로 보호되는 AdminDashboardQueryService 뿐이라는 전제")
    @Query("SELECT count(a) FROM Account a WHERE a.status = src.backend.global.common.enums.AccountStatus.BLOCKED "
            + "AND (a.blockReason IS NULL OR a.blockReason <> :retentionReason)")
    long countBlockedExcludingReason(@Param("retentionReason") String retentionReason);

    /** 로그인 실패 등으로 막힌 계정 수 — 퇴원 보존 익명화로 막힌 계정은 세지 않는다(API_SPEC §6.10 · §6.18). */
    @AcademyScopeExempt(reason = "§6.18 대시보드 차단 계정 수 — countBlockedExcludingReason 을 사유 상수로 부를 뿐이며 전 학원 범위 근거가 같다")
    default long countLoginBlocked() {
        return countBlockedExcludingReason(Account.RETENTION_BLOCK_REASON);
    }
}
