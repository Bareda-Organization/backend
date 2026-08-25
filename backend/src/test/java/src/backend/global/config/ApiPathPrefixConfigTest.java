package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Ruling 102 — {@link ApiPathPrefixConfig} 가 실제 핸들러 매핑에 접두사를 붙였는지 컨테이너를
 * 띄워 검증한다. {@code ControllerAuthorizationConventionTest} 는 소스 텍스트(bare path)만 보므로
 * 이 접두사 배선은 그 테스트로 잡히지 않는다 — 여기서 {@link RequestMappingHandlerMapping} 이
 * 실제로 등록한 경로 패턴을 조회해야 "런타임에 붙었는가"를 확인할 수 있다.
 */
@SpringBootTest
class ApiPathPrefixConfigTest {

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    void src_backend_소속_RestController_의_모든_핸들러는_API_PREFIX_를_받는다() {
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = requestMappingHandlerMapping.getHandlerMethods();

        List<String> unprefixed = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            Class<?> beanType = entry.getValue().getBeanType();
            if (!isOwnRestController(beanType)) {
                continue;
            }
            for (String pattern : patternsOf(entry.getKey())) {
                if (!pattern.startsWith(ApiPathPrefixConfig.API_PREFIX)) {
                    unprefixed.add(beanType.getSimpleName() + "#" + entry.getValue().getMethod().getName()
                            + " -> " + pattern);
                }
            }
        }

        assertThat(unprefixed)
                .as("src.backend 소속 @RestController 핸들러는 전부 " + ApiPathPrefixConfig.API_PREFIX
                        + " 접두사를 받아야 한다 — ApiPathPrefixConfig 의 대상 Predicate 를 벗어난 컨트롤러가 있다")
                .isEmpty();
    }

    /** {@link ApiPathPrefixConfig#configurePathMatch} 가 접두사를 붙이는 대상과 같은 조건이다. */
    private boolean isOwnRestController(Class<?> beanType) {
        return beanType.isAnnotationPresent(RestController.class)
                && beanType.getPackageName().startsWith("src.backend");
    }

    private List<String> patternsOf(RequestMappingInfo info) {
        var pathPatternsCondition = info.getPathPatternsCondition();
        if (pathPatternsCondition == null) {
            return List.of();
        }
        return pathPatternsCondition.getPatterns().stream().map(Object::toString).toList();
    }
}
