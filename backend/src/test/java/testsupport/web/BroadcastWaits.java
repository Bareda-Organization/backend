package testsupport.web;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import src.backend.global.websocket.BroadcastRelay;

/**
 * 커밋 뒤 방송 실행기({@code broadcastListenerExecutor})를 기다리는 공용 도우미. 방송 리스너는 커밋 직후 요청 스레드에서
 * 작업을 실행기에 넣으므로, 요청이 끝난 뒤 실행기가 비면 그 요청의 방송은 모두 나갔다. 고정 시간만큼 기다리는 방식은
 * 느린 CI 에서 방송이 늦게 나와 실패하고, 앞 시험이 남긴 늦은 방송이 다음 시험의 목(mock)에 잡힌다.
 */
public final class BroadcastWaits {

    private static final long LIMIT_SECONDS = 10;

    private BroadcastWaits() {
    }

    /**
     * 방송 실행기가 비고, 그 방송이 Redis 중계를 거쳐 이 인스턴스의 구독자에게 나가기까지 끝날 때까지 기다린다 — 방송은 실행기를 지난 뒤 Redis 에 나갔다가 받는 스레드에서
     * 전송되므로 실행기만 보면 아직 전송 전일 수 있다. 10초 안에 비지 않으면 시험을 실패시킨다.
     */
    public static void 방송이_끝날_때까지(ThreadPoolTaskExecutor executor, BroadcastRelay relay) throws InterruptedException {
        방송이_끝날_때까지(executor);
        중계가_끝날_때까지(relay);
    }

    /** 게이트웨이로 직접 낸 방송(실행기를 거치지 않음)이 Redis 중계를 거쳐 이 인스턴스의 구독자 쪽 전송까지 끝날 때까지 기다린다 — 10초 안에 비지 않으면 시험을 실패시킨다. */
    public static void 중계가_끝날_때까지(BroadcastRelay relay) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS);
        while (relay.inFlight() > 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Redis 중계로 나간 방송 " + relay.inFlight() + "건이 " + LIMIT_SECONDS + "초 안에 도착하지 않았다");
            }
            Thread.sleep(20);
        }
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
