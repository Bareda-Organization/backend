package src.backend.global.config;

import java.util.List;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;

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
 * 실행기가 이 스케줄러로 바뀌어 {@code app.ws.outbound.core-pool-size} 가 무력화됨). 그래서
 * 등록기에만 넘기고 종료는 이 설정이 맡는다.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer, DisposableBean {

    /**
     * 스레드 수 = {@code @Scheduled} 메서드 수(R57 기준 13개 — 데모 시뮬레이터 · 위치 이력 파티션 미리 만들기 · 끝나지 않은 이동 중 회차 지표 · 열린 WebSocket 세션의 계정 상태 점검 포함). 전부
     * {@code fixedDelay}·{@code cron} 이라 한 작업이 자기 자신과 겹쳐 돌지 않으므로, 이 수면 어떤
     * 작업도 남의 작업을 기다리지 않는다. 작업을 더하면 이 값도 함께 올린다 — {@code SchedulingPoolSizeTest} 가 개수를 세어 어긋나면 실패한다.
     *
     * <p>DB 커넥션(Hikari 기본 10)과의 관계 — 작업들은 짧은 트랜잭션만 쓰고 외부 호출은 트랜잭션 밖
     * (§7 규칙 16)이라 {@link #POOL_SIZE}개(운영은 {@code local} 전용 데모 시뮬레이터를 뺀 12개)가 동시에 커넥션을 쥐는 경우는 드물다. 동시 도래가 커넥션을 밀어내는지는
     * 관측 지표(hikaricp_connections_pending)로 본다.
     */
    static final int POOL_SIZE = 13;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    private final ObjectProvider<MeterRegistry> meterRegistry;

    public SchedulingConfig(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.initialize();
        // 스케줄러 풀도 Micrometer 기본 계측(executor.*, name=scheduling)으로 — 풀이 마르면 어느 배치가 줄을 섰는지 본다(DA-12)
        meterRegistry.ifAvailable(registry -> new ExecutorServiceMetrics(scheduler.getScheduledExecutor(), "scheduling",
                List.of()).bindTo(registry));
        registrar.setTaskScheduler(scheduler);
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
