package src.backend.observability.aspect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import src.backend.location.projection.BusLocationPushConsumer;

/**
 * {@link KafkaListenerMetricsAspect} 가 실제 {@code @KafkaListener} 빈에 AOP 프록시로 걸렸는지
 * 확인한다. {@link KafkaListenerMetricsAspectTest}(단위 테스트)는 catch 분기의 계측·재던짐 로직만
 * 보고 Spring 이 실제로 프록시를 씌웠는지는 증명하지 못한다 — 그 공백을 이 테스트가 메운다.
 *
 * <p>Kafka 로 메시지를 흘려 소비 실패를 실제로 재현할 필요는 없다. 부착 여부만 증명하면, 실패 시
 * catch 분기가 도는 것은 이미 단위 테스트로 검증된 결정론적 로직이라 별도 증명이 필요 없다.
 */
@SpringBootTest
class KafkaListenerMetricsAspectAttachmentTest {

    @Autowired
    private BusLocationPushConsumer busLocationPushConsumer;

    @Test
    void kafkaListenerBean_isWrappedByAopProxy() {
        assertThat(AopUtils.isAopProxy(busLocationPushConsumer))
                .as("KafkaListenerMetricsAspect 가 붙지 않았다면 이 빈은 평범한 POJO 라 AOP 프록시가 아니다")
                .isTrue();
    }
}
