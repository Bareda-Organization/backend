package src.backend.global.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * {@code @Scheduled} 전용 스케줄러(BR-010).
 *
 * <p>이 설정이 없으면 컨텍스트의 유일한 {@code TaskScheduler} 빈이 WebSocket 하트비트용(스레드 1개,
 * {@link WebSocketConfig#heartbeatScheduler})이라 Boot 기본 스케줄러가 물러나고, {@code @Scheduled} 작업
 * 전부가 그 스레드 하나를 차례로 쓴다. 확정 배치 한 틱이 수 분을 돌면 그동안 미승차 에스컬레이션
 * (EXC-01 3분) · 근접 알림 · 아웃박스 재발송 · 자동 거절이 전부 멈춘다.
 *
 * <p>⚠ <b>빈으로 등록하지 않는다</b> — 스케줄러는 {@code AsyncTaskExecutor} 이기도 해서, 빈이 되면 Boot 의
 * WebSocket 자동 구성이 그것을 STOMP 입·출력 채널 실행기로 바꿔 끼운다(2026-09-25 실측: 출력 채널
 * 실행기가 이 9스레드 스케줄러로 바뀌어 {@code app.ws.outbound.core-pool-size} 가 무력화됨). 그래서
 * 등록기에만 넘기고 종료는 이 설정이 맡는다.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer, DisposableBean {

    /**
     * 스레드 수 = {@code @Scheduled} 메서드 수(2026-09-25 기준 9개 — 데모 시뮬레이터 포함). 전부
     * {@code fixedDelay}·{@code cron} 이라 한 작업이 자기 자신과 겹쳐 돌지 않으므로, 이 수면 어떤
     * 작업도 남의 작업을 기다리지 않는다. 작업을 더하면 이 값도 함께 올린다.
     *
     * <p>DB 커넥션(Hikari 기본 10)과의 관계 — 작업들은 짧은 트랜잭션만 쓰고 외부 호출은 트랜잭션 밖
     * (§7 규칙 16)이라 9개가 동시에 커넥션을 쥐는 경우는 드물다. 동시 도래가 커넥션을 밀어내는지는
     * 관측 지표(hikaricp_connections_pending)로 본다.
     */
    private static final int POOL_SIZE = 9;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
