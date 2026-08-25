package src.backend.observability.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.observability.metrics.PipelineMetrics;

/**
 * {@code @KafkaListener} 메서드를 감싸 소비 실패를 계측한다.
 *
 * <p>이 저장소에는 컨슈머가 예외를 삼켜 알림 저장이 조용히 막힌 선례가 있다 — 앱은 생존하고 로그만
 * 남아서 뒤늦게야 드러났다. 실패 건수를 지표로 두면 그 상태를 대시보드에서 볼 수 있다.
 *
 * <p>예외는 계측 후 그대로 다시 던진다. 삼키면 Kafka 재처리 동작이 바뀐다.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class KafkaListenerMetricsAspect {

    private final PipelineMetrics metrics;

    @Around("@annotation(org.springframework.kafka.annotation.KafkaListener)")
    public Object measure(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            metrics.kafkaConsumeFailed(consumerName(joinPoint));
            throw t;
        }
    }

    /** {@code BusLocationPushConsumer} → {@code bus-location-push}. 접미사 Consumer 를 떼고 케밥으로 바꾼다. */
    private String consumerName(ProceedingJoinPoint joinPoint) {
        String simpleName = joinPoint.getTarget().getClass().getSimpleName();
        int proxyMarker = simpleName.indexOf("$$");
        if (proxyMarker > 0) {
            simpleName = simpleName.substring(0, proxyMarker);
        }
        String base = simpleName.endsWith("Consumer")
                ? simpleName.substring(0, simpleName.length() - "Consumer".length())
                : simpleName;
        return base.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }
}
