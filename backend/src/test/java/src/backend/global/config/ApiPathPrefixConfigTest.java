package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Ruling 102 — {@link ApiPathPrefixConfig} 가 실제 핸들러 매핑에 접두사를 붙였는지 컨테이너를
 * 띄워 검증한다. {@code ControllerAuthorizationConventionTest} 는 소스 텍스트(bare path)만 보므로
 * 이 접두사 배선은 그 테스트로 잡히지 않는다 — 여기서 {@link RequestMappingHandlerMapping} 이
 * 실제로 등록한 경로 패턴을 조회해야 "런타임에 붙었는가"를 확인할 수 있다.
 *
 * <p>대상 판정은 {@code @RestController} 애너테이션이 아니라 {@code src.backend} 패키지 소속
 * 여부만으로 한다(보완 리뷰 Important #2) — 애너테이션으로 걸렀던 옛 판정은 {@link
 * ApiPathPrefixConfig#configurePathMatch} 의 대상 Predicate 와 문자 그대로 동일해서, 누군가
 * {@code @RestController} 대신 {@code @Controller} 로 API 를 만들어 접두사 배선을 통째로 피해가는
 * 사고가 나도 이 테스트가 production 판정을 그대로 베껴 통과시켜 버렸다 — 안전망이 자신이 지키는
 * 대상과 같은 눈을 쓴 것이다. 패키지 기준으로 바꾸면 어떤 애너테이션을 쓰든 src.backend 소속
 * 핸들러는 전부 걸린다. {@code /actuator}·{@code /ws}·{@code /swagger-ui} 는 프레임워크가 제공하는
 * 비-API 경로라 src.backend 패키지 밖에 있으므로 이 판정에 자연히 걸리지 않지만, 혹시 나중에
 * src.backend 안에 그런 예외 성격의 핸들러가 생기는 경우를 대비해 허용 목록을 둔다.
 */
@SpringBootTest
class ApiPathPrefixConfigTest {

    /** src.backend 안에 있어도 API 접두사 대상이 아닌 경로(프레임워크 관용 경로 성격). */
    private static final List<String> EXCEPTION_PREFIXES = List.of("/actuator", "/ws", "/swagger-ui");

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    void src_backend_소속_핸들러는_애너테이션_종류와_무관하게_전부_API_PREFIX_를_받는다() {
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = requestMappingHandlerMapping.getHandlerMethods();

        List<String> unprefixed = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            Class<?> beanType = entry.getValue().getBeanType();
            if (!isOwnHandler(beanType)) {
                continue;
            }
            for (String pattern : patternsOf(entry.getKey())) {
                if (!pattern.startsWith(ApiPathPrefixConfig.API_PREFIX) && !isException(pattern)) {
                    unprefixed.add(beanType.getSimpleName() + "#" + entry.getValue().getMethod().getName()
                            + " -> " + pattern);
                }
            }
        }

        assertThat(unprefixed)
                .as("src.backend 소속 핸들러는 @RestController 든 @Controller 든 전부 "
                        + ApiPathPrefixConfig.API_PREFIX + " 접두사를 받아야 한다 — 애너테이션 종류로 접두사 배선을 피해간 "
                        + "핸들러가 있다")
                .isEmpty();
    }

    /** {@code src.backend} 패키지 소속이면 애너테이션 종류를 가리지 않는다 — production 판정과 독립적이다. */
    private boolean isOwnHandler(Class<?> beanType) {
        return beanType.getPackageName().startsWith("src.backend");
    }

    private boolean isException(String pattern) {
        return EXCEPTION_PREFIXES.stream().anyMatch(pattern::startsWith);
    }

    private List<String> patternsOf(RequestMappingInfo info) {
        var pathPatternsCondition = info.getPathPatternsCondition();
        if (pathPatternsCondition == null) {
            return List.of();
        }
        return pathPatternsCondition.getPatterns().stream().map(Object::toString).toList();
    }
}
