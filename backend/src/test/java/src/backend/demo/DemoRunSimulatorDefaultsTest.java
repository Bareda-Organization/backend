package src.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.env.MockEnvironment;

/**
 * 데모 시뮬레이터는 설정이 없으면 아무 회차도 움직이지 않는다 — 켜는 쪽(로컬 · fixture 프로파일 설정)이 값을 줘야 돈다.
 * 기본값이 켜짐 · 데모 선단 번호라면 {@code local} 프로파일을 쓰는 다른 환경(스테이징)에서 설정 누락만으로 실제 회차를 출발시킨다.
 */
class DemoRunSimulatorDefaultsTest {

    @Test
    void 설정이_없으면_시뮬레이터는_꺼져_있고_대상_버스와_학원이_비어_있다() {
        MockEnvironment emptyEnvironment = new MockEnvironment();

        assertThat(기본값("enabled", emptyEnvironment)).isEqualTo("false");
        assertThat(기본값("demoBusIds", emptyEnvironment)).isEmpty();
        assertThat(기본값("demoAcademyIds", emptyEnvironment)).isEmpty();
    }

    /** 필드의 {@code @Value} 자리표시자를 빈 환경에서 풀어 쓴 기본값. */
    private String 기본값(String fieldName, MockEnvironment environment) {
        try {
            Field field = DemoRunSimulator.class.getDeclaredField(fieldName);
            return environment.resolvePlaceholders(field.getAnnotation(Value.class).value());
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 대상_목록_필드는_목록_타입이다() throws NoSuchFieldException {
        assertThat(DemoRunSimulator.class.getDeclaredField("demoBusIds").getType()).isEqualTo(List.class);
    }
}
