package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 방송 리스너 실행기에서 한 작업(예: 리스너의 DB 조회)이 예외를 던져도 작업 스레드가 죽지 않고 다음 작업이 같은 스레드에서 돈다 —
 * 리스너는 {@code try}/{@code catch} 를 두지 않고 이 실행기가 한 번만 받는다(목적지별 전송 실패는 게이트웨이의 몫).
 */
@SpringBootTest
class BroadcastListenerExecutorTest {

    @Autowired
    @Qualifier("broadcastListenerExecutor")
    private ThreadPoolTaskExecutor executor;

    @Test
    void 작업이_예외를_던져도_다음_작업이_같은_스레드에서_돈다() throws Exception {
        AtomicReference<String> firstThread = new AtomicReference<>();
        AtomicReference<String> secondThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(2);

        executor.execute(() -> {
            firstThread.set(Thread.currentThread().getName());
            done.countDown();
            throw new IllegalStateException("리스너 조회 실패");
        });
        executor.execute(() -> {
            secondThread.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondThread.get()).as("작업 스레드가 예외로 죽으면 새 스레드(이름 번호가 늘어난다)가 다음 작업을 받는다")
                .isEqualTo(firstThread.get());
    }
}
