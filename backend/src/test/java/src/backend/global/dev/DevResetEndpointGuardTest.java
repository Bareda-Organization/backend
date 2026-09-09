package src.backend.global.dev;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 개발용 초기화 API 가 <b>테스트 컨텍스트에 존재하지 않는다</b>는 것을 고정한다.
 *
 * <p>이 시험이 없으면 다음 사고를 아무것도 막지 못한다 — 이 저장소의 테스트는 {@code local} 프로파일로
 * 돌기 때문에 {@code @Profile("local")} 만으로는 이 컨트롤러가 걸러지지 않는다. 걸러지지 않은 채로
 * 전체 실행 중 그 경로가 한 번 불리면 <b>다른 시험들이 함께 쓰는 DB 가 통째로 지워진다.</b>
 * 유일한 방어선이 {@code build.gradle} 이 심는 {@code app.dev-tools.reset.enabled=false} 인데,
 * 그 한 줄은 지워져도 컴파일과 나머지 시험이 전부 통과한다.
 */
@SpringBootTest
class DevResetEndpointGuardTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /** 단언 1 — 빈 자체가 없다. */
    @Test
    void 개발용_초기화_컨트롤러는_테스트_컨텍스트에_등록되지_않는다() {
        assertThat(applicationContext.getBeanNamesForType(DevResetController.class))
                .as("테스트에서 이 빈이 살아 있으면 공유 DB 가 지워질 수 있다")
                .isEmpty();
    }

    /** 단언 2 — 경로도 없다. 빈 이름만 보면 다른 이름으로 다시 등록된 경우를 놓친다. */
    @Test
    void 개발용_초기화_경로가_어떤_핸들러에도_매핑되지_않는다() {
        boolean mapped = handlerMapping.getHandlerMethods().entrySet().stream()
                .map(Map.Entry::getKey)
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(condition -> condition != null)
                .flatMap(condition -> condition.getPatternValues().stream())
                .anyMatch(pattern -> pattern.contains("/dev/"));

        assertThat(mapped).as("개발 도구 경로가 테스트 컨텍스트에 매핑됐다").isFalse();
    }

    /**
     * 단언 3 — 속성을 켜도 {@code prod} 프로파일에서는 빈이 만들어지지 않는다.
     * 위 두 단언은 "속성이 꺼져 있다" 만 보므로, 속성이 켜진 배포 환경을 상정한 이 검사가 따로 필요하다.
     */
    @Test
    void 속성이_켜져도_prod_프로파일에서는_빈이_만들어지지_않는다() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of())
                .withUserConfiguration(DevResetController.class)
                .withPropertyValues("spring.profiles.active=prod", "app.dev-tools.reset.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DevResetController.class));
    }

    /** 애너테이션이 통째로 지워지는 사고를 잡는다 — 위 검사들은 조건이 "어떻게" 붙었는지는 보지 않는다. */
    @Test
    void 컨트롤러가_프로파일과_속성_두_겹을_모두_달고_있다() {
        assertThat(DevResetController.class.getAnnotation(org.springframework.context.annotation.Profile.class))
                .as("@Profile(\"local\") 이 없으면 배포물에 이 경로가 뜬다")
                .isNotNull();
        ConditionalOnProperty condition = DevResetController.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).as("속성 겹이 없으면 테스트 컨텍스트에도 등록된다").isNotNull();
        assertThat(condition.name()).contains("app.dev-tools.reset.enabled");
    }
}
