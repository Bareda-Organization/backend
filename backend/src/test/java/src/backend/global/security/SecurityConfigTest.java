package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code SecurityConfig.filterChain} 이 {@link PublicEndpoints} 상수를 실제로 소비하는지
 * 런타임으로 잠근다(보완 리뷰 Minor #6).
 *
 * <p>{@code SecurityConfig} 는 {@code withPrefix(PublicEndpoints.GET_ENDPOINTS)} 로 매처를
 * 조립하지만, 이 배선을 직접 검증하는 테스트가 그동안 없었다 — 각 컨트롤러 테스트가 Authorization
 * 헤더 없이 호출해 우연히 그 효과를 봤을 뿐(예: {@code AcademySearchControllerTest}), "이 응답이
 * permitAll 때문에 통과했다"를 명시적으로 확인하는 테스트는 아니었다. 컨트롤러 테스트가 나중에
 * Authorization 헤더를 추가하면 이 안전망이 조용히 사라질 수 있어, 별도 테스트로 고정한다.
 *
 * <p>{@link PublicEndpoints#GET_ENDPOINTS} 소속인 학원검색은 인증 없이 401 이 아니어야 하고,
 * 그 목록에 없는 {@code /me} 는 인증 없이 401 이어야 한다 — permitAll 목록이 의도한 범위보다
 * 넓지 않다는 것까지 함께 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void PublicEndpoints_GET_ENDPOINTS_소속_경로는_인증_없이_401_이_아니다() throws Exception {
        int statusCode = mockMvc.perform(get("/api/v1" + PublicEndpoints.ACADEMY_SEARCH).param("q", "서울"))
                .andReturn().getResponse().getStatus();

        assertThat(statusCode).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void PublicEndpoints_에_없는_경로는_인증_없이_401_이다() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
    }
}
