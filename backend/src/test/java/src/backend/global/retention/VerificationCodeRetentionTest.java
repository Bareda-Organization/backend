package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import src.backend.account.entity.VerificationCode;
import src.backend.account.entity.VerificationPurpose;
import src.backend.account.repository.VerificationCodeRepository;

/**
 * 복구 인증 코드 발급 행은 보존 정리 배치가 지운다 — 발급 요청이 돌리는 인스턴스 메모리 타이머가 아니라 하루 한 번의 분산 락 배치다(ARCHITECTURE §9 표 "보존 정리" · ERD §7.2 {@code verification_code}).
 * 만든 지 24시간이 지난 행만 지워지고 그 안쪽 행은 남는다. 배치는 실제 시각을 쓰므로 심는 행은 실제 {@code now} 기준 상대 시각이다.
 */
@SpringBootTest
@Transactional
class VerificationCodeRetentionTest {

    @Autowired
    private RetentionCleanupScheduler scheduler;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        // 프록시를 벗긴다 — @SchedulerLock 이 락을 잡고 풀 때 시험 트랜잭션을 커밋해 심은 행이 남지 않게 한다
        scheduler = AopTestUtils.getUltimateTargetObject(scheduler);
    }

    @Test
    @DisplayName("보존 정리 배치는 24시간이 지난 복구 코드 발급 행만 지우고 그 안쪽 행은 남긴다")
    void 하루가_지난_발급_행만_지워진다() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long inside = 발급한다("01000000001", now.minusHours(23));
        long outside = 발급한다("01000000002", now.minusHours(25));

        scheduler.cleanUp();

        entityManager.clear();
        assertThat(verificationCodeRepository.existsById(inside)).as("23시간 전 행은 남는다").isTrue();
        assertThat(verificationCodeRepository.existsById(outside)).as("25시간 전 행은 지워진다").isFalse();
    }

    private long 발급한다(String phone, OffsetDateTime createdAt) {
        VerificationCode saved = verificationCodeRepository.saveAndFlush(VerificationCode.issue(phone, "123456",
                VerificationPurpose.PASSWORD, createdAt.plusMinutes(5), createdAt));
        return saved.getId();
    }
}
