package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * F1(Ruling 904 ③) — 서킷 {@code sms} 에 느린 호출 규칙이 있다. 응답이 늦는 문자 업체는 실패가 아니라 "느림" 으로 세어져야 서킷이 열린다 —
 * 값이 없으면(기본 100%·60초) 업체가 매번 몇 초씩 걸려도 서킷이 닫힌 채 요청 스레드가 묶인다. yml 인스턴스 이름을 바꾸거나 지우면 resilience4j 가
 * 기본 설정으로 조용히 인스턴스를 만들어 이 시험이 실패한다.
 */
@SpringBootTest
class SmsCircuitConfigTest {

    @Autowired
    private CircuitBreakerRegistry registry;

    @Test
    void 서킷_sms_는_느린_호출_규칙을_가진다() {
        CircuitBreakerConfig config = registry.circuitBreaker("sms").getCircuitBreakerConfig();

        assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(2));
        assertThat(config.getSlowCallRateThreshold()).isEqualTo(50f);
    }
}
