package src.backend.account.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.account.entity.Account;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.persistence.AcademyCount;
import src.backend.global.security.access.AcademyScopeExempt;

/** {@link Account} 영속성 접근. */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 회원가입 아이디 중복 확인(API_SPEC §2.2 {@code DUPLICATE_LOGIN_ID}). */
    @AcademyScopeExempt(reason = "§2.2 격리 예외가 아니라 유일성 제약 자체 — 아이디는 전 학원 통틀어 유일해야 하고, 학원별로 좁혀 세면 타 학원과 같은 아이디를 허용하게 되어 로그인 시 어느 계정인지 결정할 수단이 부재")
    boolean existsByLoginId(String loginId);

    /** 로그인 아이디로 계정을 찾는다(API_SPEC §2.5 로그인). */
    @AcademyScopeExempt(reason = "§2.5 로그인 — 아이디만 들고 시작해 소속 학원이 이 조회의 결과로 비로소 결정")
    Optional<Account> findByLoginId(String loginId);

    /**
     * 로그인 대조용 조회 — 행을 잠가 같은 계정의 로그인을 직렬화한다(C-11 · BR-026).
     *
     * <p>잠그지 않으면 동시 실패가 모두 같은 {@code failed_attempts} 를 읽고 "+1" 을 덮어써 누적이 유실되고,
     * 5회 차단이 걸리지 않는다. 비밀번호 대조(BCrypt)는 이 잠금을 잡기 전에 끝낸다({@code LoginCommandService}) —
     * 잠금 구간에 넣으면 그 시간만큼 DB 연결이 묶인다.
     */
    @AcademyScopeExempt(reason = "§2.5 로그인 — 아이디만 들고 시작해 소속 학원이 이 조회의 결과로 비로소 결정")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.loginId = :loginId")
    Optional<Account> findByLoginIdForUpdate(@Param("loginId") String loginId);

    /**
     * 재신청할 계정을 잠그고 읽는다(§2.4 · BR-063) — 동시 재신청이 둘 다 {@code rejected} 를 보고 요청 행을 두 개
     * 쌓지 않게, 두 번째는 {@code pending} 을 읽어 {@code 409 REAPPLY_NOT_ALLOWED} 가 된다.
     */
    @AcademyScopeExempt(reason = "§2.4 본인 재신청 — 계정 자체의 조회라 학원 조건이 판정에 개입 부재. 재신청은 학원을 다시 "
            + "고르는 흐름이라 좁힐 학원도 미확정. 호출부가 토큰의 accountId 만 넘긴다는 전제")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * 학원의 특정 계정들을 가져온다 — 메인 관리자 콘솔의 학원 상세({@code staff_accounts[]}, API_SPEC §6.3)가
     * {@code academy_staff} 행에 이름·아이디·연락처를 채울 때 쓴다.
     *
     * <p>식별자만으로 찾지 않고 학원 조건을 함께 거는 이유는, 이 조회에 학원이 걸리지 않으면
     * {@code academy_staff} 행이 가리키는 계정이 실제로 그 학원 소속인지 아무도 보지 않게 되기 때문이다.
     */
    List<Account> findAllByAcademyIdAndIdIn(Long academyId, Collection<Long> ids);

    /** 학원 안의 계정 한 건 — 관리자 경유 비밀번호 초기화(API_SPEC §5.22)가 타 학원 계정을 존재 비노출 404 로 거른다. */
    Optional<Account> findByIdAndAcademyId(Long id, Long academyId);

    /**
     * {@link #findByIdAndAcademyId} 를 <b>행 잠금</b>으로 읽는다(BR-249) — 비밀번호 초기화처럼 전 컬럼을 덮어쓰는 변경이
     * 그 사이 커밋된 로그인 실패 차단({@code failed_attempts}·{@code status})을 지우지 않게 로그인 대조({@link
     * #findByLoginIdForUpdate})와 같은 행 잠금으로 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.academyId = :academyId")
    Optional<Account> findByIdAndAcademyIdForUpdate(@Param("id") Long id, @Param("academyId") Long academyId);

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
     * ({@code EmergencyStaffQueryService}, Phase 11 T2)이 확인자(acked_by)를 이름으로 바꿀 때도 쓴다.
     *
     * <p>학원 조건을 함께 걸 수 없는 것이 §6.3 의 {@link #findAllByAcademyIdAndIdIn} 과 갈리는
     * 지점이다 — 그쪽은 목록 전체가 한 학원이고 이쪽은 한 페이지(또는 목록)에 여러 학원이 섞일 수 있다.
     */
    @AcademyScopeExempt(reason = "§6.4 메인 관리자 콘솔의 전 학원 조회(ARCHITECTURE §6.2 격리 예외) — 한 페이지에 여러 학원이 "
            + "섞여 좁힐 학원이 부재. 호출부가 이미 학원 범위로 확인된 조회가 돌려준 account_id 만 넘긴다는 전제 "
            + "(승인 큐 조회의 account_id, 또는 emergency_alert.acked_by — 후자는 토큰의 accountId 로만 채워져 "
            + "요청 파라미터가 아니다, 또는 §6.13 접속 이력 해제 행의 target_id — 감사 행에서 읽은 값, 또는 보존 정리 배치(Ruling 480 ②)가 파기 대상 학생에서 읽은 계정 id — 호출 주체가 스케줄러) — 요청 파라미터의 식별자를 직접 넘기면 임의 계정의 연락처를 읽는 통로가 된다")
    List<Account> findAllByIdIn(Collection<Long> ids);

    /**
     * 차단 계정 목록(API_SPEC §6.10 {@code GET /admin/blocked-accounts}) — 호출부가 {@code blocked} 를 넘긴다.
     *
     * <p>상태를 상수로 박지 않고 인자로 받는 이유는, 박아 두면 이 조회의 이름과 조건이 갈릴 때
     * (예: 나중에 {@code rejected} 목록이 필요해질 때) 같은 형태의 조회가 하나 더 복제되기 때문이다.
     *
     * <p>정렬은 {@code Pageable} 이 실어 온다 — 파생 쿼리 이름에 {@code OrderBy} 를 박으면
     * {@code Pageable} 의 정렬과 어느 쪽이 이기는지가 호출부에서 보이지 않는다.
     */
    @AcademyScopeExempt(reason = "§6.10 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "차단 해제는 계정 단위 조치라 대상을 학원으로 좁히면 운영사가 어느 학원에서 사고가 났는지 "
            + "알아야만 목록을 볼 수 있게 된다. 예외를 여는 판정은 컨트롤러의 @CanUnblockAccount 하나다")
    Page<Account> findAllByStatus(AccountStatus status, Pageable pageable);

    /**
     * 관계자 계정 목록(API_SPEC §6.6 {@code GET /admin/staff-accounts}).
     *
     * <p>{@code role='staff'} 가 아니라 <b>{@code academy_staff} 행의 존재</b>로 대상을 정한다 — 역할만
     * 보면 아직 승인되지 않아 어느 학원에도 소속되지 않은 계정이 함께 실리고, 그러면 관리자가 그
     * 계정을 퇴사·재직 전환하려 하게 된다. 승인 대기 축은 §6.4 승인 큐가 따로 맡는다.
     *
     * <p>조인이 아니라 {@code EXISTS} 인 이유는 이 조회의 <b>결과가 계정</b>이어서다 — 조인으로 쓰면
     * 정렬 속성이 어느 쪽 것인지 호출부에서 갈리고, {@code academy_staff} 행이 늘면 계정이 중복된다.
     */
    @AcademyScopeExempt(reason = "§6.6 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "응답이 academy_name 을 실어 어느 학원 관계자인지 드러내는 것이 이 화면의 요건이라 "
            + "학원으로 좁히면 화면이 성립하지 않는다. 예외 판정은 컨트롤러의 @CanManageStaffAccount 하나다")
    @Query(value = "SELECT a FROM Account a WHERE EXISTS "
            + "(SELECT 1 FROM AcademyStaff s WHERE s.accountId = a.id)",
            countQuery = "SELECT COUNT(a) FROM Account a WHERE EXISTS "
                    + "(SELECT 1 FROM AcademyStaff s WHERE s.accountId = a.id)")
    Page<Account> findStaffAccountsForConsole(Pageable pageable);

    /**
     * 특정 역할·상태의 계정 전부(Phase 11 T2, EXC-04) — 비상 알림이 메인 관리자 전원에게 설정과
     * 무관하게 동시 도달해야 하는데(목표 6), 메인관리자는 {@code academy_staff} 소속이 없어
     * {@link src.backend.academy.repository.AcademyStaffRepository#findActiveAccountsByAcademyId} 로
     * 찾을 수 없다 — 이 조회가 그 갈래를 담당한다.
     */
    @AcademyScopeExempt(reason = "메인관리자(SYSTEM_ADMIN)는 academy_id 가 null 이라(ck_account_academy_scope) "
            + "학원으로 좁힐 수 없다 — 이 역할 자체가 전 학원 범위라는 것이 §1.5 의 정의(Role#hasPlatformScope)다. "
            + "role=SYSTEM_ADMIN 조건이 이미 좁힌 대상이라 학원 조건을 더할 근거가 없다")
    List<Account> findAllByRoleAndStatus(Role role, AccountStatus status);

    /**
     * {@link #findAllByRoleAndStatus} 와 같은 대상을 개수만 센다(API_SPEC §4.14 {@code notified},
     * BE-R1 목표 1) — 비상 신고 응답이 실제 발송 대상 이름까지는 필요 없고 도달 수신자 수만 필요해,
     * 계정 전체를 불러 크기를 재는 대신 count 전용 질의로 좁힌다.
     */
    @AcademyScopeExempt(reason = "메인관리자(SYSTEM_ADMIN)는 academy_id 가 null 이라(ck_account_academy_scope) "
            + "학원으로 좁힐 수 없다 — §1.5 정의(Role#hasPlatformScope)와 {@link #findAllByRoleAndStatus} 가 "
            + "이미 같은 판단을 내렸다")
    long countByRoleAndStatus(Role role, AccountStatus status);

    /**
     * 감사 화면의 행위자 찾기(R46 감사, API_SPEC §6.13 {@code GET /admin/audit-actors}) — 이름 또는 로그인 아이디에
     * {@code q} 가 들어 있는 계정을 이름순으로 {@code limit} 건까지. 빈 검색어는 전건과 같아지므로 호출부가 막는다.
     */
    @AcademyScopeExempt(reason = "§6.13 메인 관리자 콘솔 — 감사 이력은 전 학원 범위이고 행위자는 어느 학원의 계정이든 될 수 있어 "
            + "학원으로 좁히면 화면이 성립하지 않는다. 예외 판정은 컨트롤러의 @CanReadAudit 하나다")
    @Query("""
            SELECT a FROM Account a
            WHERE LOWER(a.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(a.loginId) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY a.name, a.id
            """)
    List<Account> searchByNameOrLoginId(@Param("q") String q, Limit limit);
}
