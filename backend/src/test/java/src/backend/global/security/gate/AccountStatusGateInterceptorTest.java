package src.backend.global.security.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
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

    private String bearer(AccountStatus status) {
        return "Bearer " + tokenProvider.createAccessToken(1L, 10L, Role.PARENT, status);
    }

    @Test
    void pending_토큰은_허용_애너테이션이_붙은_핸들러를_통과한다() throws Exception {
        String token = bearer(AccountStatus.PENDING);

        mockMvc.perform(get("/gate-test/signup-status").header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/gate-test/logout").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void pending_토큰이_애너테이션_없는_핸들러를_부르면_403_AUTH_PENDING_이다() throws Exception {
        String token = bearer(AccountStatus.PENDING);

        mockMvc.perform(get("/gate-test/protected").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_PENDING"));
    }

    @Test
    void rejected_토큰은_pending_허용분에_더해_재신청_핸들러도_통과한다() throws Exception {
        String token = bearer(AccountStatus.REJECTED);

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
        String token = bearer(AccountStatus.PENDING);

        mockMvc.perform(post("/gate-test/reapply").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_PENDING"));
    }

    /** rejected 의 거부는 pending 과 코드가 달라야 한다 — 같으면 클라이언트가 대기 화면과 거절 화면을 구별 못 한다. */
    @Test
    void rejected_토큰이_허용분_밖_핸들러를_부르면_403_AUTH_REJECTED_이다() throws Exception {
        String token = bearer(AccountStatus.REJECTED);

        mockMvc.perform(get("/gate-test/protected").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_REJECTED"));
    }

    @Test
    void active_토큰은_게이트를_그대로_통과한다() throws Exception {
        String token = bearer(AccountStatus.ACTIVE);

        mockMvc.perform(get("/gate-test/protected").header("Authorization", token))
                .andExpect(status().isOk());
    }

    /**
     * blocked 는 로그인 단계({@code Account.assertNotBlocked})에서 이미 걸러지는 상태라 이 게이트까지
     * 도달하는 것 자체가 이례적이나, 활성으로 로그인한 뒤 차단되면 기존 토큰이 만료될 때까지는 이
     * 분기가 §1.4 의 "blocked → 접근 부재"를 강제하는 유일한 지점이다 — 허용 애너테이션이 붙은
     * 핸들러조차 예외 없이 막아야 한다(리뷰 라운드 1 Important #4).
     */
    @Test
    void blocked_토큰은_허용_애너테이션이_붙은_핸들러에서도_403_AUTH_ACCOUNT_BLOCKED_이다() throws Exception {
        String token = bearer(AccountStatus.BLOCKED);

        mockMvc.perform(get("/gate-test/signup-status").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_BLOCKED"));
    }

    /**
     * {@link GateTestController} 는 이 테스트와 같은 커밋의 같은 저자가 쓴 픽스처라, 그것만 세면
     * 프로덕션 컨트롤러의 부착 누락을 검출하지 못한다(리뷰 라운드 1 Important #1) —
     * {@code src/main} 을 직접 스캔한다({@code ControllerAuthorizationConventionTest} 의 소스
     * 스캔·의도적 RED 선례를 따른다, Ruling 73). 컨트롤러가 0개인 지금은 RED(0 개)이고, Task 3·4 가
     * 실제 핸들러에 애너테이션을 붙이는 순간 GREEN 으로 전환된다.
     */
    @Test
    void 허용_애너테이션이_붙은_실제_핸들러_수가_pending_2개_rejected_3개다() {
        Path sourceRoot = Path.of("src/main/java/src/backend");
        assertThat(sourceRoot).as("테스트 작업 디렉토리가 backend/ 가 아니면 경로를 고쳐야 한다").isDirectory();

        long allowedWhenPending = countAnnotatedMethods(sourceRoot, "@AllowedWhenPending");
        long allowedWhenRejected = countAnnotatedMethods(sourceRoot, "@AllowedWhenRejected");

        assertThat(allowedWhenPending)
                .as("pending 이 통과해야 하는 실제 핸들러는 승인 대기 조회·로그아웃 2개다(API_SPEC §1.4)")
                .isEqualTo(2);
        assertThat(allowedWhenPending + allowedWhenRejected)
                .as("rejected 가 통과해야 하는 실제 핸들러는 pending 의 2개 + 재신청 1개, 총 3개다(API_SPEC §1.4)")
                .isEqualTo(3);
    }

    /** {@code src/main} 소스를 줄 단위로 스캔해 지정한 애너테이션이 정확히 붙은 줄의 개수를 센다. */
    private long countAnnotatedMethods(Path sourceRoot, String annotation) {
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .flatMap(p -> readLines(p).stream())
                    .map(String::trim)
                    .filter(line -> line.equals(annotation))
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
