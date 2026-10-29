package src.backend.account.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.account.entity.Account;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.access.AcademyScopeExempt;

/** {@link Account} 영속성 접근. 콘솔 · 대시보드용 조회는 {@link AccountConsoleQueries} 에 있다. */
public interface AccountRepository extends JpaRepository<Account, Long>, AccountConsoleQueries {

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
     *
     * <p>계정 전 컬럼을 쓰는 다른 경로(승인 · 비밀번호 변경·초기화 · 관계자 수정 · 매니저 역할 동기 · 차단 해제)도 같은 읽기를
     * 쓴다 — 잠금 없이 읽어 쓰면 그 사이 커밋된 로그인 실패 차단을 지운다(BR-249 · BR-335).
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
     * 연락처가 같은 복구 대상 계정을 <b>행 잠금</b>으로 읽는다(저장된 표기와 무관하게 하이픈을 뺀 숫자 {@code digits} 로 비교한다 —
     * {@code PhoneNumber#digits})(API_SPEC §2.9 · Ruling 513) — 전화번호 복구가 비밀번호를
     * 교체할 때 그 사이 커밋된 로그인 실패 차단을 지우지 않게 한다(BR-249). 연락처는 유일하지 않아 여러 건일 수 있다.
     * <b>차단된 계정은 대상이 아니다</b>(BR-336) — 퇴원 파기로 익명화된 계정이 모두 같은 가짜 번호로 걸리고, 차단 계정은
     * 복구해도 로그인이 {@code 403} 이다. 코드 발급은 존재 여부만 보므로 이 잠금 읽기가 아니라
     * {@link #findAllByPhoneAndRoleIn} 을 쓴다(BR-308 — 문자 발송 동안 행을 쥐지 않는다).
     */
    @AcademyScopeExempt(reason = "§2.9 계정 복구 — 전화번호만 들고 시작해 소속 학원이 미상")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE REPLACE(a.phone, '-', '') = :digits AND a.role IN :roles "
            + "AND a.status <> src.backend.global.common.enums.AccountStatus.BLOCKED ORDER BY a.id")
    List<Account> findAllByPhoneAndRoleInForUpdate(@Param("digits") String digits, @Param("roles") Collection<Role> roles);

    /**
     * 같은 번호의 복구 대상 계정을 잠금 없이 읽는다(차단 계정 제외 · {@link #findAllByPhoneAndRoleInForUpdate} 와 같은 조건)
     * — 코드 발급이 문자를 보낼지 정하는 존재 여부와, 대조가 임시 비밀번호(BCrypt)를 몇 개 미리 만들지 정하는 데만 쓴다.
     * 저장은 {@link #findAllByPhoneAndRoleInForUpdate} 로 다시 읽은 행에 한다.
     */
    @AcademyScopeExempt(reason = "§2.9 계정 복구 — 전화번호만 들고 시작해 소속 학원이 미상")
    @Query("SELECT a FROM Account a WHERE REPLACE(a.phone, '-', '') = :digits AND a.role IN :roles "
            + "AND a.status <> src.backend.global.common.enums.AccountStatus.BLOCKED ORDER BY a.id")
    List<Account> findAllByPhoneAndRoleIn(@Param("digits") String digits, @Param("roles") Collection<Role> roles);

    /**
     * 주어진 계정 중 <b>권한을 잃은</b> 계정의 id(Ruling 880) — 차단(blocked) · 퇴사한 관계자(inactive) · 삭제된 매니저.
     * 이미 받은 access 토큰이 계정의 현재 상태를 모르고 통하는 것을 요청마다 막는 판정이 이 한 문장이다.
     *
     * <p>행이 없는 계정은 결과에 없다 — "권한을 잃었다" 와 "알 수 없다" 를 섞으면 계정 행 없이 발급한 토큰이 전부 거절된다.
     */
    @AcademyScopeExempt(reason = "Ruling 880 — 토큰의 계정 id 로 그 계정 자신의 현재 상태만 읽는다. 호출부(AccountStanding)가 토큰·세션이 "
            + "싣고 있는 accountId, 또는 로그인이 비밀번호 대조를 마친 계정 자신의 id 만 넘긴다는 전제 — 요청 파라미터의 id 를 넘기면 타 학원 계정의 상태를 묻는 통로가 된다")
    @Query("SELECT a.id FROM Account a WHERE a.id IN :ids AND ("
            + "a.status = src.backend.global.common.enums.AccountStatus.BLOCKED "
            + "OR EXISTS (SELECT 1 FROM AcademyStaff s WHERE s.accountId = a.id "
            + "AND s.status = src.backend.academy.entity.StaffStatus.INACTIVE) "
            + "OR EXISTS (SELECT 1 FROM Manager m WHERE m.accountId = a.id AND m.deletedAt IS NOT NULL))")
    List<Long> findIdsWithLostStanding(@Param("ids") Collection<Long> ids);
}
