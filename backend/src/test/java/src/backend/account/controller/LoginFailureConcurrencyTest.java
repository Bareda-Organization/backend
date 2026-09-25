package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

import src.backend.account.entity.Account;

/**
 * 동시에 들어온 로그인 실패도 전부 누적된다(C-11 · BR-026).
 *
 * <p>카운터가 "읽은 값 + 1" 을 덮어쓰면 동시 실패가 같은 값을 써서 누적이 사라지고, 5회 차단이 걸리지
 * 않는다 — 추측 시도 횟수 제한이 동시 요청 앞에서 무력해진다. 비밀번호 대조(BCrypt)가 수십 ms 걸려
 * 요청들이 "읽기 → 대조 → 쓰기" 구간에서 겹치므로 잠금이 없으면 이 시험이 안정적으로 실패한다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 요청마다 자기 트랜잭션을 커밋해야 경쟁이 성립한다. 만든 행은
 * {@link #뒷정리한다()} 가 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginFailureConcurrencyTest {

    private static final String LOGIN_ID = "br026conc";

    private static final int CONCURRENT_FAILURES = 10;

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
                VALUES (1, ?, ?, '동시실패', '010-0000-0260', 'parent', 'active')
                """, LOGIN_ID, passwordEncoder.encode("right-password"));
    }

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM audit_log WHERE actor_login_id = ?", LOGIN_ID);
        jdbcTemplate.update("DELETE FROM account WHERE login_id = ?", LOGIN_ID);
    }

    @Test
    void 동시에_들어온_로그인_실패가_유실되지_않아_상한에서_차단된다() throws Exception {
        CountDownLatch 출발 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_FAILURES);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_FAILURES; i++) {
                results.add(pool.submit(틀린_비밀번호_로그인(출발)));
            }
            출발.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            assertThat(statuses).as("실패는 401 또는 403 이어야 한다 — 500 은 경쟁이 새어 나간 것").allMatch(
                    status -> status == 401 || status == 403);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM account WHERE login_id = ?", String.class, LOGIN_ID))
                    .as("실패 %d회가 동시에 들어왔는데 차단되지 않았다 — 카운터 누적이 유실됐다", CONCURRENT_FAILURES)
                    .isEqualTo("blocked");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT failed_attempts FROM account WHERE login_id = ?", Integer.class, LOGIN_ID))
                    .isEqualTo(Account.MAX_FAILED_ATTEMPTS);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Integer> 틀린_비밀번호_로그인(CountDownLatch 출발) {
        return () -> {
            출발.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"login_id\":\"%s\",\"password\":\"wrong\"}".formatted(LOGIN_ID)))
                    .andReturn().getResponse().getStatus();
        };
    }
}
