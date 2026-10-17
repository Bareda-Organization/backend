package src.backend.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * FCM 서킷 인스턴스({@code fcm})는 장소 검색({@code placeSearch})과 같은 값이다(R46 S-2) — 이름이 yml 에 없으면
 * resilience4j 가 <b>기본 설정</b>으로 조용히 인스턴스를 만들어, 값이 의도와 다른 서킷이 돈다. 코드의 인스턴스 이름과 yml 이 어긋나는
 * 그 사고를 문서 단위 비교로 막는다.
 */
class FcmCircuitConfigTest {

    private static final String PREFIX = "resilience4j.circuitbreaker.instances.";

    private static final List<String> KEYS = List.of("sliding-window-type", "sliding-window-size",
            "minimum-number-of-calls", "failure-rate-threshold", "wait-duration-in-open-state",
            "permitted-number-of-calls-in-half-open-state", "automatic-transition-from-open-to-half-open-enabled");

    @Test
    @DisplayName("fcm 서킷 인스턴스가 yml 에 있고 값이 placeSearch 와 같다")
    void fcm_서킷_인스턴스는_placeSearch_와_같은_값이다() throws Exception {
        PropertySource<?> common = commonDocument();

        for (String key : KEYS) {
            assertThat(common.getProperty(PREFIX + "fcm." + key)).as("fcm.%s", key).isNotNull()
                    .isEqualTo(common.getProperty(PREFIX + "placeSearch." + key));
        }
    }

    /** 프로파일 조건이 없는 문서 중 서킷 인스턴스를 정의한 것. */
    private PropertySource<?> commonDocument() throws Exception {
        for (PropertySource<?> document : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            if (document.getProperty(PREFIX + "placeSearch.sliding-window-size") != null) {
                return document;
            }
        }
        throw new AssertionError("서킷 인스턴스를 정의한 문서를 찾지 못했다");
    }
}
