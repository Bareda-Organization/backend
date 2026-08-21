package src.backend.observability.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import src.backend.observability.metrics.PipelineMetrics;

/**
 * {@link KafkaListenerMetricsAspect#measure} 의 성공·실패 분기와 이름 유도 로직을 mock
 * {@link ProceedingJoinPoint} 로 검증한다. 이 테스트는 aspect 가 실제 {@code @KafkaListener} 빈에
 * 걸렸는지는 증명하지 못한다 — 그건 {@link KafkaListenerMetricsAspectAttachmentTest} 의 몫이다.
 */
class KafkaListenerMetricsAspectTest {

    private final PipelineMetrics metrics = mock(PipelineMetrics.class);
    private final KafkaListenerMetricsAspect aspect = new KafkaListenerMetricsAspect(metrics);

    @Test
    void measure_onSuccess_doesNotCountAndReturnsProceedResult() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.measure(joinPoint);

        assertThat(result).isEqualTo("ok");
        verify(metrics, never()).kafkaConsumeFailed(any());
    }

    @Test
    void measure_onFailure_countsWithDerivedConsumerNameAndRethrows() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getTarget()).thenReturn(new SampleConsumer());
        RuntimeException boom = new RuntimeException("consume 실패");
        when(joinPoint.proceed()).thenThrow(boom);

        // 삼키지 않고 그대로 다시 던지는지 확인한다 — 삼키면 Kafka 재처리 동작이 바뀐다.
        assertThatThrownBy(() -> aspect.measure(joinPoint)).isSameAs(boom);

        verify(metrics).kafkaConsumeFailed("sample");
    }

    @Test
    void measure_onFailure_stripsCglibProxyMarkerFromTargetClassName() throws Throwable {
        // 실제 런타임에서 getTarget() 이 반환하는 값이 순수 POJO 가 아니라 CGLIB 로 감싸인 인스턴스일 수
        // 있다는 것을 Spring 의 실제 프록시 생성기로 재현한다(문자열을 조작해 흉내내지 않는다) — 결과
        // 클래스명은 "SampleConsumer$$SpringCGLIB$$0" 형태로, "$$" 마커를 포함한다.
        ProxyFactory proxyFactory = new ProxyFactory(new SampleConsumer());
        proxyFactory.setProxyTargetClass(true);
        Object cglibProxiedTarget = proxyFactory.getProxy();
        assertThat(cglibProxiedTarget.getClass().getSimpleName()).contains("$$");

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getTarget()).thenReturn(cglibProxiedTarget);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.measure(joinPoint)).isInstanceOf(IllegalStateException.class);

        // "$$" 이후를 잘라내고 Consumer 접미사를 뗀 뒤 케밥으로 바꾸면 여전히 "sample" 이어야 한다.
        verify(metrics).kafkaConsumeFailed("sample");
    }
}
