package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import src.backend.notification.domain.NotificationRetryPolicy;

/**
 * 재시도 정책의 <b>성질</b>을 고정한다 — 동작을 보는 단언들과 축이 다르다.
 *
 * <p>선점이 성립하는지는 경합 상황을 만들어야 보이고, 그 관측은 스레드 스케줄링에 따라 갈린다.
 * 여기서는 그 성질이 <b>기대는 값</b>을 직접 재어, 경합을 만들지 않고도 회귀가 드러나게 한다.
 * 동작 쪽 단언({@code 방금_시도한_행은_최소_간격_전에는_다시_집히지_않는다})과 <b>둘 다</b> 둔다 —
 * 하나는 값이 무너진 것을, 다른 하나는 그 값을 쓰는 조건문이 무너진 것을 잡는다.
 */
class NotificationRetryPolicyTest {

    /**
     * 최소 재시도 간격은 <b>0 보다 커야 한다.</b>
     *
     * <p>선점은 "마지막 시도가 {@code now - MIN_RETRY_INTERVAL} 보다 이전" 인 행만 집는 조건부
     * UPDATE 다. 간격이 0 이면 그 경계가 <b>지금</b>이 되어, 방금 선점하며 찍은 시각도 조건을
     * 통과한다 — 즉 두 실행이 같은 행을 함께 집고 수신자는 같은 알림을 두 번 받는다.
     */
    @Test
    void 선점이_성립하려면_최소_재시도_간격은_0보다_커야_한다() {
        assertThat(NotificationRetryPolicy.MIN_RETRY_INTERVAL)
                .as("간격이 0 이면 선점 조건의 경계가 '지금'이 되어 방금 찍은 시각도 통과한다")
                .isGreaterThan(Duration.ZERO);
    }
}
