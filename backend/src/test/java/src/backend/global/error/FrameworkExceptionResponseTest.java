package src.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 자체 상태를 가진 프레임워크 예외는 그 상태에 맞는 {@code ErrorResponse} 로 나간다 — catch-all 로 떨어지면 클라이언트 실수(Accept 헤더 · 필수 헤더 누락)가 서버 고장({@code 500} + ERROR 스택)으로 보인다.
 * 클라이언트가 응답을 받기 전에 끊은 요청은 쓸 곳이 없으므로 본문을 쓰지 않고 ERROR 로 남기지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("프레임워크 예외는 자기 상태의 ErrorResponse 로 나간다")
class FrameworkExceptionResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger handlerLogger;

    @BeforeEach
    void captureLogs() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        handlerLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("만들 수 없는 형식을 Accept 로 요청하면 500 이 아니라 422 VALIDATION_FAILED")
    void 받을_수_없는_Accept_는_422() throws Exception {
        mockMvc.perform(get("/api/v1/framework-error-test/json").header("Authorization", staff())
                        .header("Accept", "application/x-not-produced"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(errorLogs()).as("클라이언트 실수는 ERROR 로 남기지 않는다").isZero();
    }

    @Test
    @DisplayName("필수 요청 헤더가 없으면 500 이 아니라 422 VALIDATION_FAILED")
    void 필수_헤더_누락은_422() throws Exception {
        mockMvc.perform(get("/api/v1/framework-error-test/needs-header").header("Authorization", staff()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(errorLogs()).isZero();
    }

    @ParameterizedTest(name = "ResponseStatusException {0} → {1} {2}")
    @CsvSource({"400,422,VALIDATION_FAILED", "404,404,ENDPOINT_NOT_FOUND", "403,403,FORBIDDEN", "401,401,UNAUTHORIZED",
            "405,405,METHOD_NOT_ALLOWED", "409,422,VALIDATION_FAILED", "500,500,INTERNAL_ERROR"})
    @DisplayName("ResponseStatusException 은 상태 분류에 맞는 코드로 나간다")
    void ResponseStatusException_은_상태에_맞는_코드(int thrown, int expectedStatus, String expectedCode) throws Exception {
        mockMvc.perform(get("/api/v1/framework-error-test/status/" + thrown).header("Authorization", staff()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.error.code").value(expectedCode));
    }

    @Test
    @DisplayName("클라이언트가 끊은 요청은 본문을 쓰지 않고 ERROR 로 남기지 않는다")
    void 클라이언트_중단은_본문도_ERROR_로그도_없다() throws Exception {
        mockMvc.perform(get("/api/v1/framework-error-test/client-abort").header("Authorization", staff()))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty())
                .andExpect(status().is(org.hamcrest.Matchers.not(500)));
        assertThat(errorLogs()).isZero();
    }

    @Test
    @DisplayName("지원하지 않는 메서드의 405 는 Allow 헤더에 허용 메서드를 싣는다")
    void 가능한_메서드가_Allow_로_나간다() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login").header("Authorization", staff()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("목록의 page 가 숫자가 아니면 자바 타입명 없이 고정 문구의 422")
    void page_형식_오류는_고정_문구() throws Exception {
        mockMvc.perform(get("/api/v1/staff/buses").param("page", "abc").header("Authorization", staff()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("page 값의 형식이 올바르지 않습니다"))
                .andExpect(jsonPath("$.error.message", not(containsString("java"))));
    }

    private long errorLogs() {
        return logs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).count();
    }

    private String staff() {
        return "Bearer " + tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);
    }
}
