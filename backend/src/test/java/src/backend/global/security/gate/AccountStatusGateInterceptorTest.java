package src.backend.global.security.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.security.JwtTokenProvider;
import src.backend.global.security.SecurityConfig;

/**
 * 계정 상태 게이트(C-01 · API_SPEC §1.4)를 컨트롤러 없이도 검증하는 슬라이스 테스트 —
 * {@link GateTestController}(테스트 소스 전용)가 실제 엔드포인트를 대신한다.
 *
 * <p>{@code SecurityConfig}·{@code JwtTokenProvider}는 {@code @WebMvcTest} 의 컴포넌트 스캔이
 * 자동으로 들이지 않는 일반 {@code @Component}·{@code @Configuration}이라 명시적으로
 * {@code @Import} 한다. {@code AccountStatusGateInterceptor}(HandlerInterceptor)·
 * {@code AccountStatusGateWebConfig}(WebMvcConfigurer)·{@code GlobalExceptionHandler}
 * (ControllerAdvice)는 슬라이스가 알려진 웹 계층 타입으로 인식해 스캔만으로 들어온다.
 */
@WebMvcTest(controllers = GateTestController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class})
class AccountStatusGateInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private String bearer(String status) {
        return "Bearer " + tokenProvider.createAccessToken(1L, 10L, "parent", status);
    }

    @Test
    void pending_토큰은_허용_애너테이션이_붙은_핸들러만_통과한다() throws Exception {
        String token = bearer("pending");

        mockMvc.perform(get("/gate-test/signup-status").header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/gate-test/logout").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void pending_토큰이_애너테이션_없는_핸들러를_부르면_403_AUTH_PENDING_이다() throws Exception {
        String token = bearer("pending");

        mockMvc.perform(get("/gate-test/protected").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejected_토큰은_pending_허용분에_더해_재신청_핸들러도_통과한다() throws Exception {
        String token = bearer("rejected");

        mockMvc.perform(get("/gate-test/signup-status").header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/gate-test/logout").header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/gate-test/reapply").header("Authorization", token))
                .andExpect(status().isOk());
    }

    /** 이것이 없으면 pending·rejected 의 허용 집합이 하나로 합쳐진 구현(둘 다 3개 허용)이 통과한다. */
    @Test
    void pending_토큰으로_재신청_핸들러를_부르면_403_이다() throws Exception {
        String token = bearer("pending");

        mockMvc.perform(post("/gate-test/reapply").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void active_토큰은_게이트를_그대로_통과한다() throws Exception {
        String token = bearer("active");

        mockMvc.perform(get("/gate-test/protected").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void 허용_애너테이션이_붙은_핸들러_수가_pending_2개_rejected_3개다() {
        Method[] methods = GateTestController.class.getDeclaredMethods();

        long allowedWhenPending = Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(AllowedWhenPending.class))
                .count();
        long allowedWhenRejectedAdditional = Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(AllowedWhenRejected.class))
                .count();

        assertThat(allowedWhenPending).isEqualTo(2);
        assertThat(allowedWhenPending + allowedWhenRejectedAdditional).isEqualTo(3);
    }
}
