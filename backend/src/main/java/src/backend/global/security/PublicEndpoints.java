package src.backend.global.security;

import java.util.List;

/**
 * 인증 없이 여는 API 경로(bare path, 접두사 없음) 목록 — Ruling 103.
 *
 * <p>{@code SecurityConfig} 가 이 목록에 {@link src.backend.global.config.ApiPathPrefixConfig#API_PREFIX}
 * 를 붙여 시큐리티 매처를 만든다. {@code ControllerAuthorizationConventionTest.EXPECTED_PUBLIC_ENDPOINTS}
 * 는 사양(API_SPEC §2.1·2.2·2.5·2.6·2.9)에서 그대로 옮긴 하드코딩 목록으로 <b>일부러 이 클래스를
 * 참조하지 않는다</b> — 이 클래스 자체가 실수로 잘못 채워지는 것을 잡으려면 대조 대상이 이 클래스와
 * 독립적이어야 한다(같은 곳을 참조하면 둘 다 같이 틀려도 테스트가 못 잡는다).
 */
public final class PublicEndpoints {

    /** {@code GET /academies/search}(API_SPEC §2.1). */
    public static final String ACADEMY_SEARCH = "/academies/search";

    /** {@code POST /auth/signup}(API_SPEC §2.2). */
    public static final String SIGNUP = "/auth/signup";

    /** {@code POST /auth/login}(API_SPEC §2.5, Task 4 소관 — 경로만 여기서 함께 관리). */
    public static final String LOGIN = "/auth/login";

    /** {@code POST /auth/refresh}(API_SPEC §2.6, Task 4 소관). */
    public static final String REFRESH = "/auth/refresh";

    /** {@code POST /auth/recover}(API_SPEC §2.9, Task 4 소관). */
    public static final String RECOVER = "/auth/recover";

    /** {@code SecurityConfig} 가 GET 매처에 쓰는 목록. */
    public static final List<String> GET_ENDPOINTS = List.of(ACADEMY_SEARCH);

    /** {@code SecurityConfig} 가 POST 매처에 쓰는 목록. */
    public static final List<String> POST_ENDPOINTS = List.of(SIGNUP, LOGIN, REFRESH, RECOVER);

    private PublicEndpoints() {
    }
}
