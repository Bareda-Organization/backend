package src.backend.run.scheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 확정 배치가 회차를 동시에 처리할 스레드 풀(RTE-08, Phase 7 목표 6) — 한 틱이 집은 회차들을 이
 * 풀 크기만큼만 동시에 돌린다.
 *
 * <p>상한을 두는 이유는 회차 1건의 확정({@code RunConfirmationService.confirmOne})이 외부 지도
 * API 를 부르기 때문이다 — 상한 없이 한 틱의 회차 전부를 한꺼번에 던지면 지도 API 호출이 몰려
 * 서로의 응답을 늦추고, DB 커넥션도 회차 수만큼 동시에 쥔다.
 *
 * <p>고정 크기 풀을 쓴다 — 가상 스레드 기반의 무제한 확장형 executor 는 이 상한 자체를 무의미하게
 * 만든다.
 */
@Configuration
public class RunConfirmationWorkerPoolConfig {

    @Bean
    public ExecutorService runConfirmationExecutor(
            @Value("${app.run.confirmation.pool-size:8}") int poolSize) {
        return Executors.newFixedThreadPool(poolSize);
    }
}
