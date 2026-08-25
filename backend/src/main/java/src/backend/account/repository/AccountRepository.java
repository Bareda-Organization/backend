package src.backend.account.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
}
