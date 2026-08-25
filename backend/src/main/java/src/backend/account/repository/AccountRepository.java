package src.backend.account.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.account.entity.Account;

/** {@link Account} 영속성 접근. */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 회원가입 아이디 중복 확인(API_SPEC §2.2 {@code DUPLICATE_LOGIN_ID}). */
    boolean existsByLoginId(String loginId);

    Optional<Account> findByLoginId(String loginId);
}
