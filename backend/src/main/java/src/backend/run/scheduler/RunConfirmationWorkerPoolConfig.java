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
 *
 * <p>크기의 기본값은 <b>배치 전용</b> 지도 API 격벽 상한({@code mapRouteBatch.max-concurrent-calls})
 * 이다(ARCHITECTURE §9.4 "워커 풀 크기를 외부 지도 API 레이트리밋에 맞춤"). 격벽은 대기 없이
 * 거부하므로 풀이 더 크면 한 틱에 몰린 회차 중 넘치는 몫이 공급자에 닿지도 못하고 직선 근사로
 * 확정된다 — 2026-09-23 실측, 풀 8 · 격벽 4 에서 동시 도래 60건 중 53건이 직선 근사였다.
 *
 * <p>⚠ <b>전체 칸 수가 아니라 배치 칸 수를 본다</b>(`Ruling 350`, BR-169) — 예전에는 배치·온디맨드가
 * 격벽 하나(4칸)를 공유해 이 풀도 4를 기본값으로 삼았다. 그러면 관리자 화면 조회 1건이 칸 하나를
 * 쥔 순간 풀이 여전히 4건을 동시에 밀어 넣어 그중 1건이 거부돼 직선 근사로 확정됐다. 격벽을
 * 배치 3 · 온디맨드 1로 가른 뒤에는 이 풀도 배치 몫(3)만 본다 — 풀이 애초에 배치 칸 수 이상으로
 * 동시에 두드리지 않으므로 화면 쪽 점유와 무관해진다.
 */
@Configuration
public class RunConfirmationWorkerPoolConfig {

    /** 고정 크기(기본값 = 배치 전용 지도 API 격벽 상한) 스레드 풀 빈 — 확정 배치가 회차 처리에 쓴다. */
    @Bean
    public ExecutorService runConfirmationExecutor(
            @Value("${app.run.confirmation.pool-size:${resilience4j.bulkhead.instances.mapRouteBatch.max-concurrent-calls}}")
            int poolSize) {
        return Executors.newFixedThreadPool(poolSize);
    }
}
