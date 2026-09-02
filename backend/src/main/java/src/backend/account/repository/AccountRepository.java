package src.backend.account.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * 연락처로 계정을 찾는다(API_SPEC §2.9 아이디·비밀번호 복구 — {@code type} 이
     * {@code login_id}·{@code password} 둘 다 이 조회로 대상 계정을 특정한다).
     *
     * <p>{@code account.phone} 에는 DB UNIQUE 제약이 없다 — 같은 연락처로 여러 계정이 가입된
     * 경우 이 조회가 둘 이상을 만나면 {@code IncorrectResultSizeDataAccessException} 을 던진다.
     * 그 경우를 어떻게 다룰지(예: 최신 계정 우선)는 이 조회의 호출자(Task 4)가 판단할 몫이다.
     */
    @AcademyScopeExempt(reason = "§2.9 계정 복구 — 전화번호만 들고 시작해 소속 학원이 미상")
    Optional<Account> findByPhone(String phone);

    /**
     * 학원의 특정 계정들을 가져온다 — 메인 관리자 콘솔의 학원 상세({@code staff_accounts[]}, API_SPEC §6.3)가
     * {@code academy_staff} 행에 이름·아이디·연락처를 채울 때 쓴다.
     *
     * <p>식별자만으로 찾지 않고 학원 조건을 함께 거는 이유는, 이 조회에 학원이 걸리지 않으면
     * {@code academy_staff} 행이 가리키는 계정이 실제로 그 학원 소속인지 아무도 보지 않게 되기 때문이다.
     */
    List<Account> findAllByAcademyIdAndIdIn(Long academyId, Collection<Long> ids);

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
     * (API_SPEC §6.4)이 요청 행에 신청자 정보를 채울 때 쓴다.
     *
     * <p>학원 조건을 함께 걸 수 없는 것이 §6.3 의 {@link #findAllByAcademyIdAndIdIn} 과 갈리는
     * 지점이다 — 그쪽은 목록 전체가 한 학원이고 이쪽은 한 페이지에 여러 학원이 섞인다.
     */
    @AcademyScopeExempt(reason = "§6.4 메인 관리자 콘솔의 전 학원 조회(ARCHITECTURE §6.2 격리 예외) — 한 페이지에 여러 학원이 "
            + "섞여 좁힐 학원이 부재. 호출부가 승인 큐 조회가 돌려준 account_id 만 넘긴다는 전제 — 요청 파라미터의 "
            + "식별자를 넘기면 임의 계정의 연락처를 읽는 통로가 된다")
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
}
