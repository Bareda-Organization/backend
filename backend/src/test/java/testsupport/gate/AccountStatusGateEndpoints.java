package testsupport.gate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import src.backend.global.config.ApiPathPrefixConfig;
import src.backend.global.security.authz.PublicEndpoint;
import src.backend.global.security.gate.AllowedWhenPending;
import src.backend.global.security.gate.AllowedWhenRejected;

/**
 * 계정 상태 게이트(C-01 · API_SPEC §1.4)의 <b>거부측 대상 목록</b>과, 그 목록이 낡지 않았는지 대조할
 * 실측 수단을 함께 둔다.
 *
 * <p>정본 문면은 "허용 목록 밖 <b>전부</b> 403" 이다 — 손으로 적은 목록만 두면 다음 Phase 가
 * 엔드포인트를 늘렸을 때 목록이 조용히 낡아 검사 범위와 문면이 벌어진다. 그래서 목록(사람이 읽는
 * 정본 대조용)과 {@link #productionEndpoints} 의 런타임 실측(핸들러 매핑에서 직접 뽑음)을 둘 다 두고
 * {@code AuthFlowIntegrationTest} 가 서로 대조한다. 두 출처가 독립이라 한쪽의 누락을 다른 쪽이 잡는다.
 */
public final class AccountStatusGateEndpoints {

    /**
     * {@code rejected} 계정이 두드리면 {@code 403 AUTH_REJECTED} 여야 하는 실제 핸들러 — 허용 6개
     * ({@code @AllowedWhenPending} 5 + {@code @AllowedWhenRejected} 1)와 {@code @PublicEndpoint} 5개를
     * 뺀 나머지 전부다. 경로는 컨트롤러 소스의 bare path 로 적고 접두사는 {@link #uriOf} 가 붙인다.
     *
     * <p>Phase 5 가 {@code /staff/buses} 3개와 {@code /staff/managers} 4개를 더했다 — 차량·매니저는
     * 관계자 전용 관리 화면이라 승인 대기·거절 계정에 열어 줄 근거가 부재하다.
     *
     * <p>이어서 {@code /staff/schedules} 4개와 {@code /staff/runs} 4개(회차 조회·임시 추가·임시 취소·
     * 매니저 배치)를 더했다 — 운행 계획과 그날의 회차도 같은 관리 화면이라 근거가 같다.
     */
    public static final List<String> DENIED_WHEN_REJECTED = List.of(
            "POST /auth/password",
            "GET /admin/academies",
            "POST /admin/academies",
            "GET /admin/academies/{id}",
            "PATCH /admin/academies/{id}",
            "GET /admin/staff-accounts",
            "PATCH /admin/staff-accounts/{id}",
            "GET /admin/blocked-accounts",
            "POST /admin/blocked-accounts/{id}/unblock",
            "GET /admin/staff-signup-requests",
            "POST /admin/staff-signup-requests/{id}/decide",
            "GET /staff/signup-requests",
            "POST /staff/signup-requests/{id}/decide",
            "GET /staff/students",
            "POST /staff/students",
            "GET /staff/students/{id}",
            "PATCH /staff/students/{id}",
            "DELETE /staff/students/{id}",
            "GET /staff/buses",
            "POST /staff/buses",
            "PATCH /staff/buses/{id}",
            "GET /staff/managers",
            "POST /staff/managers",
            "PATCH /staff/managers/{id}",
            "DELETE /staff/managers/{id}",
            "GET /staff/schedules",
            "POST /staff/schedules",
            "PATCH /staff/schedules/{id}",
            "DELETE /staff/schedules/{id}",
            "GET /staff/runs",
            "POST /staff/runs",
            "DELETE /staff/runs/{id}",
            "PATCH /staff/runs/{runId}/assignment",
            // 자녀 목록·연결 3단계(§3.1~§3.4) — 승인된 학부모·학생의 기능이라 허용 목록 밖이다(Ruling 145).
            "GET /me/students",
            "POST /me/students/link-requests",
            "POST /me/link-code",
            "POST /me/students/link",
            // 요일별 등하원 주소(§3.7) — 승인된 학부모의 기능이라 허용 목록 밖이다(Ruling 145).
            "GET /students/{id}/weekly-address",
            "PATCH /students/{id}/weekly-address");

    /** {@code pending} 의 거부측 — {@code rejected} 거부측에 재신청 1개를 더한다({@code @AllowedWhenRejected} 는 pending 을 열지 않는다). */
    public static final List<String> DENIED_WHEN_PENDING = concat(DENIED_WHEN_REJECTED, "POST /auth/signup/reapply");

    private AccountStatusGateEndpoints() {
    }

    /**
     * 프로덕션 컨트롤러의 핸들러 매핑을 {@code "HTTP메서드 bare경로"} 로 뽑는다 — {@code filter} 로
     * 애너테이션 조건을 건다.
     *
     * <p>대상을 패키지가 {@code src.backend..controller} 인 빈으로 좁힌다. 테스트 소스의 합성 컨트롤러
     * ({@code GateTestController} 등)도 컴포넌트 스캔에 잡혀 그것까지 세면 프로덕션 핸들러 수가 흐려진다.
     * 프로덕션 컨트롤러가 이 패키지 규칙 밖에 놓이면 이 메서드가 그것을 놓치는데, 호출부의 전체 개수
     * 단언이 그때 실패해 드러난다.
     */
    public static List<String> productionEndpoints(RequestMappingHandlerMapping handlerMapping,
            Predicate<HandlerMethod> filter) {
        List<String> endpoints = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            if (!handlerMethod.getBeanType().getPackageName().startsWith("src.backend")
                    || !handlerMethod.getBeanType().getPackageName().endsWith(".controller")
                    || !filter.test(handlerMethod)) {
                return;
            }
            endpoints.add(httpMethodOf(info) + " " + barePathOf(info));
        });
        endpoints.sort(Comparator.naturalOrder());
        return endpoints;
    }

    /** {@code pending} 토큰이 통과하면 안 되는 핸들러인가 — 공개도 아니고 pending 허용도 아닌 것. */
    public static boolean deniedWhenPending(HandlerMethod handlerMethod) {
        return !handlerMethod.hasMethodAnnotation(PublicEndpoint.class)
                && !handlerMethod.hasMethodAnnotation(AllowedWhenPending.class);
    }

    /** {@code rejected} 토큰이 통과하면 안 되는 핸들러인가 — 위 조건에 재신청 허용까지 뺀 것. */
    public static boolean deniedWhenRejected(HandlerMethod handlerMethod) {
        return deniedWhenPending(handlerMethod)
                && !handlerMethod.hasMethodAnnotation(AllowedWhenRejected.class);
    }

    /**
     * {@code "HTTP메서드 bare경로"} 를 실제로 부를 수 있는 URI 로 바꾼다 — 접두사를 붙이고 경로 변수를
     * {@code 1} 로 채운다.
     *
     * <p>경로 변수 값이 실재하지 않아도 된다 — 게이트는 {@code HandlerInterceptor.preHandle} 이라
     * 인자 해석·조회보다 먼저 돌아, 거부측에서는 그 값이 읽히는 지점까지 가지 않는다.
     */
    public static String uriOf(String endpoint) {
        return ApiPathPrefixConfig.API_PREFIX + pathOf(endpoint).replaceAll("\\{[^}]+}", "1");
    }

    /** {@code "HTTP메서드 bare경로"} 의 HTTP 메서드. */
    public static HttpMethod httpMethodOf(String endpoint) {
        return HttpMethod.valueOf(endpoint.substring(0, endpoint.indexOf(' ')));
    }

    private static String pathOf(String endpoint) {
        return endpoint.substring(endpoint.indexOf(' ') + 1);
    }

    private static String httpMethodOf(RequestMappingInfo info) {
        Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                info.getMethodsCondition().getMethods();
        if (methods.size() != 1) {
            throw new IllegalStateException("HTTP 메서드가 하나로 정해지지 않은 매핑이다: " + info);
        }
        return methods.iterator().next().name();
    }

    /** 런타임 매핑 패턴에서 {@link ApiPathPrefixConfig#API_PREFIX} 를 떼어 컨트롤러 소스의 bare path 로 되돌린다. */
    private static String barePathOf(RequestMappingInfo info) {
        Set<org.springframework.web.util.pattern.PathPattern> patterns =
                info.getPathPatternsCondition() == null ? Set.of() : info.getPathPatternsCondition().getPatterns();
        if (patterns.size() != 1) {
            throw new IllegalStateException("경로가 하나로 정해지지 않은 매핑이다: " + info);
        }
        String pattern = patterns.iterator().next().getPatternString();
        if (!pattern.startsWith(ApiPathPrefixConfig.API_PREFIX)) {
            throw new IllegalStateException("API 접두사가 붙지 않은 프로덕션 매핑이다: " + pattern);
        }
        return pattern.substring(ApiPathPrefixConfig.API_PREFIX.length());
    }

    private static List<String> concat(List<String> base, String extra) {
        List<String> merged = new ArrayList<>(base);
        merged.add(extra);
        return List.copyOf(merged);
    }
}
