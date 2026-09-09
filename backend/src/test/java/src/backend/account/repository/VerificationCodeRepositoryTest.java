package src.backend.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import src.backend.account.entity.VerificationCode;
import src.backend.account.entity.VerificationPurpose;

/**
 * {@link VerificationCodeRepository} 신규 조회 — 호출부(Task 4)가 아직 없어 이 테스트가
 * 그 자리를 대신한다.
 */
@SpringBootTest
@Transactional
class VerificationCodeRepositoryTest {

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    /** §2.9 복구 — 같은 연락처·목적으로 재발송이 있었다면 가장 최근 코드만 대조 대상이다. */
    @Test
    void findTop_은_같은_연락처_목적_중_가장_최근_코드를_반환한다() {
        OffsetDateTime base = OffsetDateTime.now().minusMinutes(10);
        verificationCodeRepository.save(VerificationCode.issue("010-7777-0001", "111111",
                VerificationPurpose.LOGIN_ID, base.plusMinutes(3), base));
        verificationCodeRepository.save(VerificationCode.issue("010-7777-0001", "222222",
                VerificationPurpose.LOGIN_ID, base.plusMinutes(8), base.plusMinutes(5)));
        // 다른 목적(password)의 코드는 대상에서 제외돼야 한다 — 시각만 보면 이게 가장 최근이다.
        verificationCodeRepository.save(VerificationCode.issue("010-7777-0001", "999999",
                VerificationPurpose.PASSWORD, base.plusMinutes(15), base.plusMinutes(10)));

        VerificationCode found = verificationCodeRepository
                .findTopByPhoneAndPurposeOrderByCreatedAtDesc("010-7777-0001", VerificationPurpose.LOGIN_ID)
                .orElseThrow();

        assertThat(found.getCode()).isEqualTo("222222");
    }

    /** 발급 이력이 없는 연락처·목적 조합은 빈 결과다(§2.9 만료·불일치 판정의 전제). */
    @Test
    void findTop_은_발급_이력이_없으면_비어있다() {
        assertThat(verificationCodeRepository
                .findTopByPhoneAndPurposeOrderByCreatedAtDesc("010-7777-9999", VerificationPurpose.PASSWORD))
                .isEmpty();
    }
}
