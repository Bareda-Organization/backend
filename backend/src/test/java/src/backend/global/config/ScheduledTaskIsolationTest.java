package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 오래 도는 {@code @Scheduled} 작업 하나가 다른 {@code @Scheduled} 작업을 멈추지 않는다(BR-010).
 *
 * <p>확정 배치가 07:30 에 몰리면 한 틱이 수 분 동안 돈다. 모든 작업이 스레드 하나를 나눠 쓰면 그동안
 * 미승차 에스컬레이션(EXC-01 3분)·근접 알림·아웃박스 재발송·자동 거절이 전부 멈춘다. 운영 작업들은
 * 시험에서 {@code initial-delay} 가 하루로 밀려 있어(build.gradle) 여기 두 작업만 돈다.
 */
@SpringBootTest
@Import(ScheduledTaskIsolationTest.Probe.class)
class ScheduledTaskIsolationTest {

    private static final CountDownLatch BLOCKER_STARTED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE = new CountDownLatch(1);
    private static final CountDownLatch PROBE_RAN = new CountDownLatch(1);

    @Test
    void 오래_도는_작업이_있어도_다른_Scheduled_작업이_돈다() throws Exception {
        try {
            assertThat(BLOCKER_STARTED.await(10, TimeUnit.SECONDS)).as("오래 도는 작업이 먼저 시작돼야 한다").isTrue();
            assertThat(PROBE_RAN.await(5, TimeUnit.SECONDS))
                    .as("오래 도는 작업이 스레드를 붙든 동안 다른 작업이 한 번도 돌지 못했다").isTrue();
        } finally {
            RELEASE.countDown();
        }
    }

    /** 스레드를 붙드는 작업과, 그 뒤에 도래하는 작업. */
    public static class Probe {

        @Scheduled(fixedDelay = 60_000)
        public void blocker() throws InterruptedException {
            BLOCKER_STARTED.countDown();
            RELEASE.await(30, TimeUnit.SECONDS);
        }

        @Scheduled(fixedDelay = 60_000, initialDelay = 500)
        public void probe() {
            PROBE_RAN.countDown();
        }
    }
}
