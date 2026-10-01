package src.backend.run.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * R46-KFIXBE K-2(Ruling 703) — 확정이 실패한 횟수에 따라 다음 시도까지의 간격이 30초부터 두 배씩 늘고 10분에서 멈춘다. 첫 실패는 다음 틱(30초)이라
 * 일시적 실패는 전과 같은 속도로 회복하고, 영구 실패만 하루 2,880회 → 약 150회로 줄어든다.
 */
class RunConfirmationRetryDelayTest {

    @Test
    void 실패_횟수가_늘수록_간격이_두_배씩_늘고_10분에서_멈춘다() {
        assertThat(RunConfirmationPolicy.retryDelayAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(RunConfirmationPolicy.retryDelayAfter(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(RunConfirmationPolicy.retryDelayAfter(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(RunConfirmationPolicy.retryDelayAfter(5)).isEqualTo(Duration.ofSeconds(480));
        assertThat(RunConfirmationPolicy.retryDelayAfter(6)).as("960초가 아니라 상한").isEqualTo(Duration.ofMinutes(10));
        assertThat(RunConfirmationPolicy.retryDelayAfter(Integer.MAX_VALUE)).as("지수가 넘치지 않는다")
                .isEqualTo(Duration.ofMinutes(10));
    }
}
