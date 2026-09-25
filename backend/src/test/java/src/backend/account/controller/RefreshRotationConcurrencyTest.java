package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/**
 * 같은 refresh 토큰의 동시 재발급은 한 건만 성공한다(§2.6 회전 · C-14 · BR-061).
 *
 * <p>무효화 결과(갱신 행 수)를 보지 않으면 두 요청이 모두 "미해지" 를 읽고 각자 새 토큰을 받아 세션 사슬이
 * 두 갈래로 갈린다 — 탈취된 토큰을 정상 사용자와 공격자가 함께 이어 쓸 수 있다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 요청마다 자기 트랜잭션을 커밋해야 경쟁이 성립한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefreshRotationConcurrencyTest {

    private static final String LOGIN_ID = "br061conc";

    private static final int CONCURRENT_REFRESHES = 6;

    private static final long TIMEOUT_SECONDS = 60;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void 활성_계정을_만든다() {
        뒷정리한다();
        jdbcTemplate.update("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
                VALUES (1, ?, ?, '동시회전', '010-0000-0610', 'parent', 'active')
                """, LOGIN_ID, passwordEncoder.encode("password"));
    }

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM audit_log WHERE actor_login_id = ?", LOGIN_ID);
        jdbcTemplate.update("DELETE FROM account WHERE login_id = ?", LOGIN_ID);
    }

    @Test
    void 같은_refresh_토큰의_동시_재발급은_한_건만_성공한다() throws Exception {
        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"%s\",\"password\":\"password\"}".formatted(LOGIN_ID)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String refreshToken = JsonPath.read(loginBody, "$.data.refresh_token");

        CountDownLatch 출발 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REFRESHES);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REFRESHES; i++) {
                results.add(pool.submit(재발급(refreshToken, 출발)));
            }
            출발.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            assertThat(statuses.stream().filter(status -> status == 200).count())
                    .as("같은 토큰으로 새 토큰을 받은 요청은 하나여야 한다 — 실제=%s", statuses).isEqualTo(1);
            assertThat(statuses).as("나머지는 401 TOKEN_EXPIRED").filteredOn(status -> status != 200)
                    .allMatch(status -> status == 401);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Integer> 재발급(String refreshToken, CountDownLatch 출발) {
        return () -> {
            출발.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refresh_token\":\"%s\"}".formatted(refreshToken)))
                    .andReturn().getResponse().getStatus();
        };
    }
}
