package src.backend.observability.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.observability.metrics.SchedulerHealthMetrics;

/**
 * {@code @Scheduled} 메서드를 감싸 성공·실패를 계측한다.
 *
 * <p>계측 호출을 스케줄러 코드 안에 넣지 않는 이유는 두 가지다. 도메인이 관측 모듈을 참조하지 않게 하고,
 * 스케줄러가 4개에서 더 늘어도 계측이 자동으로 따라붙게 하려는 것이다.
 *
 * <p>태그 값은 선언 클래스명에서 유도한다 — {@code ConnectionLossScheduler} 는 {@code connection-loss} 다.
 * 이름 목록을 상수로 들고 있으면 스케줄러가 추가될 때 함께 고쳐야 하는데, 그 갱신을 잊으면 계측이 조용히 빠진다.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class ScheduledTaskMetricsAspect {

    private final SchedulerHealthMetrics metrics;

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object measure(ProceedingJoinPoint joinPoint) throws Throwable {
        String scheduler = schedulerName(joinPoint);
        try {
            Object result = joinPoint.proceed();
            metrics.recordSuccess(scheduler);
            return result;
        } catch (Throwable t) {
            // 계측만 하고 그대로 다시 던진다. 삼키면 스케줄러의 기존 예외 처리 동작이 바뀐다.
            metrics.recordFailure(scheduler);
            throw t;
        }
    }

    /** {@code ConnectionLossScheduler} → {@code connection-loss}. 접미사 Scheduler 를 떼고 케밥으로 바꾼다. */
    private String schedulerName(ProceedingJoinPoint joinPoint) {
        String simpleName = joinPoint.getTarget().getClass().getSimpleName();
        int proxyMarker = simpleName.indexOf("$$");
        if (proxyMarker > 0) {
            simpleName = simpleName.substring(0, proxyMarker);
        }
        String base = simpleName.endsWith("Scheduler")
                ? simpleName.substring(0, simpleName.length() - "Scheduler".length())
                : simpleName;
        return base.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }
}
