package testsupport.web;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 커밋 뒤 방송 실행기({@code broadcastListenerExecutor})를 기다리는 공용 도우미. 방송 리스너는 커밋 직후 요청 스레드에서
 * 작업을 실행기에 넣으므로, 요청이 끝난 뒤 실행기가 비면 그 요청의 방송은 모두 나갔다. 고정 시간만큼 기다리는 방식은
 * 느린 CI 에서 방송이 늦게 나와 실패하고, 앞 시험이 남긴 늦은 방송이 다음 시험의 목(mock)에 잡힌다.
 */
public final class BroadcastWaits {

    private static final long LIMIT_SECONDS = 10;

    private BroadcastWaits() {
    }

    /** 실행기에 들어간 작업이 모두 끝날 때까지 기다린다 — 10초 안에 비지 않으면 시험을 실패시킨다. */
    public static void 방송이_끝날_때까지(ThreadPoolTaskExecutor executor) throws InterruptedException {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS);
        while (pool.getCompletedTaskCount() < pool.getTaskCount()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("방송 실행기가 " + LIMIT_SECONDS + "초 안에 비지 않았다");
            }
            Thread.sleep(20);
        }
    }
}
