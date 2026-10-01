package src.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * DB 자원 오류(풀 고갈 · 잠금 대기 초과 · 쿼리 취소)는 {@code 500 INTERNAL_ERROR} 가 아니라 {@code 503 SERVER_BUSY} +
 * {@code Retry-After} 이고 로그는 스택 없는 한 줄이다(R46 S-7) — 서버 결함 5xx 와 과부하 5xx 가 섞이면 5xx 경보가
 * 오탐이 되고, 풀이 마른 동안 요청마다 스택트레이스가 쏟아져 로그가 폭주한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class DbResourceUnavailableHandlingTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @ParameterizedTest
    @ValueSource(strings = {"pool-exhausted", "connection-lost", "lock-timeout", "query-timeout"})
    @DisplayName("DB 자원 오류 4종은 503 SERVER_BUSY + Retry-After 3초이고 스택트레이스를 로그에 남기지 않는다")
    void DB_자원_오류는_503_이고_스택을_남기지_않는다(String kind, CapturedOutput output) throws Exception {
        mockMvc.perform(get("/boom/" + kind))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.error.code").value("SERVER_BUSY"));

        assertThat(output.getAll()).as("원인 추적용 한 줄은 남는다").contains("[db-unavailable]");
        assertThat(output.getAll()).as("스택트레이스는 남기지 않는다").doesNotContain("\tat ");
    }

    @Test
    @DisplayName("그 밖의 예외는 그대로 500 INTERNAL_ERROR 이다 — 서버 결함까지 503 으로 덮지 않는다")
    void 그_밖의_예외는_500_이다() throws Exception {
        mockMvc.perform(get("/boom/bug"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/boom/{kind}")
        String boom(@PathVariable String kind) {
            throw switch (kind) {
                case "pool-exhausted" -> new CannotCreateTransactionException("Could not open JPA EntityManager",
                        new SQLTransientConnectionException("Connection is not available, request timed out after 3000ms"));
                case "connection-lost" -> new DataAccessResourceFailureException("Unable to acquire JDBC Connection");
                case "lock-timeout" -> new CannotAcquireLockException("canceling statement due to lock timeout");
                case "query-timeout" -> new QueryTimeoutException("canceling statement due to user request");
                default -> new IllegalStateException("서버 결함");
            };
        }
    }
}
