package src.backend.observability.aspect;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@link ScheduledTaskMetricsAspectTest} 전용 더미 대상. 실제 도메인 스케줄러가 아직 하나도
 * 없어(Phase 0) 짧은 주기로 직접 도는 대체 빈을 둔다. 최상위 클래스로 두는 이유는
 * {@link SampleConsumer} 와 같다 — 중첩 static 클래스면 CGLIB 프록시 생성 시 바깥 클래스 이름까지
 * simpleName 에 섞여 아스펙트가 유도하는 태그 값이 실제 런타임과 달라진다.
 */
public class DummyScheduler {

    @Scheduled(fixedRate = 1000)
    public void tick() {
    }
}
