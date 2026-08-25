package src.backend.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.global.common.enums.Role;

/**
 * {@link AccountRepository} 신규 조회 — Phase 2 Task 4(로그인·복구)가 쓸 자리를 이 태스크가
 * 미리 만들어 두는 것이라, 호출부 없이도 이 테스트가 그 자리를 지킨다.
 */
@SpringBootTest
@Transactional
class AccountRepositoryTest {

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    /** §2.9 복구 — 연락처로 계정을 되찾는다. */
    @Test
    void findByPhone_은_연락처로_계정을_찾는다() {
        Academy academy = academyRepository.save(Academy.register("P2T3PHNQQQQ", "학원P2T3PHN연락처", "서울", null, null));
        accountRepository.save(Account.forSignup(academy.getId(), "p2t3phoneqqqq", "x", "연락처테스트",
                "010-9999-0001", null, Role.PARENT));

        Account found = accountRepository.findByPhone("010-9999-0001").orElseThrow();

        assertThat(found.getLoginId()).isEqualTo("p2t3phoneqqqq");
    }

    /** 등록되지 않은 연락처는 빈 결과를 반환한다(§2.9 {@code 404 ACCOUNT_NOT_FOUND} 판정의 근거). */
    @Test
    void findByPhone_은_등록되지_않은_연락처면_비어있다() {
        assertThat(accountRepository.findByPhone("010-0000-9999")).isEmpty();
    }
}
