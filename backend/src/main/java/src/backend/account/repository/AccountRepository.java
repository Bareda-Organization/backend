package src.backend.account.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.account.entity.Account;
import src.backend.global.security.access.AcademyScopeExempt;

/** {@link Account} 영속성 접근. */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 회원가입 아이디 중복 확인(API_SPEC §2.2 {@code DUPLICATE_LOGIN_ID}). */
    @AcademyScopeExempt(reason = "§2.2 아이디는 전 학원 통틀어 유일 — 학원별로 좁히면 타 학원과 같은 아이디를 허용해 로그인이 어느 계정인지 결정 불가")
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
}
