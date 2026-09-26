package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.account.entity.Account;

/**
 * 비밀번호 대조(BCrypt)는 DB 연결·행 잠금을 쥐지 않은 채 돈다 — 그리고 그 사이 차단된 계정을 뒤따르는 성공 처리가
 * 덮어쓰지 않는다.
 *
 * <p>대조를 잠금 트랜잭션 안에서 하면 BCrypt 시간(요청당 수십~수백 ms)만큼 연결이 묶여, 등원 전 로그인이 몰릴 때
 * 풀이 대조를 기다리는 요청으로 찬다(FIX-LOAD2 §3-1 — 학생 2,000명 램프에서 연결 대기 최대 80, 풀 20).
 *
 * <p>대조를 가짜로 멈춰 세워 판정한다 — {@link #SLOW_PASSWORD} 로 들어온 대조는 {@link #대조_해제} 가 열릴 때까지
 * 기다린다. 연결 풀은 2개다(1개로는 기동 중 Flyway 가 두 번째를 요구해 뜨지 못한다 — {@code
 * NaverGeocodingResilienceTest} 와 같은 이유). {@code @Transactional} 이 부재하다 — 요청마다 자기 트랜잭션을
 * 커밋해야 경쟁이 성립한다. 만든 행은 {@link #뒷정리한다()} 가 멈춘 요청을 풀어 끝낸 뒤 지운다.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=2000"
})
@AutoConfigureMockMvc
class LoginPasswordCheckOutsideLockTest {

    private static final String SLOW_PASSWORD = "slow-password";

    private static final String FAST_PASSWORD = "fast-password";

    private static final String SLOW_A = "lockout-slow-a";

    private static final String SLOW_B = "lockout-slow-b";

    private static final String FAST = "lockout-fast";

    private static final String TARGET = "lockout-target";

    private static final long TIMEOUT_SECONDS = 30;

    /** 대조가 멈춘 동안 다른 요청이 끝나기를 기다리는 시간 — 멈춘 대조가 잠금·연결을 쥐고 있으면 이 안에 못 끝난다. */
    private static final Duration WHILE_PAUSED = Duration.ofSeconds(5);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    private volatile CountDownLatch 대조_진입;

    private final CountDownLatch 대조_해제 = new CountDownLatch(1);

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @BeforeEach
    void 느린_대조를_심는다() {
        행을_지운다();
        doAnswer(invocation -> {
            if (SLOW_PASSWORD.equals(invocation.getArgument(0))) {
                대조_진입.countDown();
                대조_해제.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());
    }

    @AfterEach
    void 뒷정리한다() throws InterruptedException {
        대조_해제.countDown();
        pool.shutdown();
        pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        행을_지운다();
    }

    private void 행을_지운다() {
        for (String loginId : new String[] {SLOW_A, SLOW_B, FAST, TARGET}) {
            jdbcTemplate.update("DELETE FROM audit_log WHERE actor_login_id = ?", loginId);
            jdbcTemplate.update("DELETE FROM account WHERE login_id = ?", loginId);
        }
    }

    /** 풀(2개)보다 많은 대조가 멈춰 있어도 다른 로그인은 연결을 얻는다 — 대조가 연결을 쥐면 세 번째가 시간 초과한다. */
    @Test
    void 비밀번호_대조가_도는_동안_DB_연결을_붙들지_않는다() throws Exception {
        계정을_만든다(SLOW_A, SLOW_PASSWORD, "010-0000-9001", 0);
        계정을_만든다(SLOW_B, SLOW_PASSWORD, "010-0000-9002", 0);
        계정을_만든다(FAST, FAST_PASSWORD, "010-0000-9003", 0);
        대조_진입 = new CountDownLatch(2);

        Future<Integer> slowA = pool.submit(() -> 로그인(SLOW_A, SLOW_PASSWORD));
        Future<Integer> slowB = pool.submit(() -> 로그인(SLOW_B, SLOW_PASSWORD));
        assertThat(대조_진입.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("두 로그인이 대조에 들어갔다").isTrue();

        assertThat(pool.submit(() -> 로그인(FAST, FAST_PASSWORD)))
                .as("대조 2건이 멈춘 동안 세 번째 로그인이 끝나야 한다 — 대조가 연결을 쥐면 풀(2개)이 비지 않는다")
                .succeedsWithin(WHILE_PAUSED)
                .isEqualTo(200);

        대조_해제.countDown();
        assertThat(slowA.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(slowB.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(200);
    }

    /**
     * 대조가 잠금 밖으로 나가면 새로 생기는 경합 — 옳은 비밀번호의 대조가 도는 사이 다른 요청의 실패가 상한을 채워
     * 차단했으면, 뒤따르는 성공 처리는 <b>잠근 행을 다시 보고</b> 거부한다. 처음 읽은 상태({@code active})로 판정하면
     * 카운터를 0 으로 되돌리고 차단된 계정에 토큰을 발급한다.
     */
    @Test
    void 대조_도중_차단된_계정은_비밀번호가_맞아도_거부되고_차단이_유지된다() throws Exception {
        계정을_만든다(TARGET, SLOW_PASSWORD, "010-0000-9004", Account.MAX_FAILED_ATTEMPTS - 1);
        대조_진입 = new CountDownLatch(1);

        Future<Integer> 옳은_시도 = pool.submit(() -> 로그인(TARGET, SLOW_PASSWORD));
        assertThat(대조_진입.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("옳은 비밀번호의 대조가 시작됐다").isTrue();

        assertThat(pool.submit(() -> 로그인(TARGET, "wrong-password")))
                .as("마지막 실패가 대조 중인 로그인을 기다리지 않고 차단을 확정해야 한다 — 대조가 행 잠금을 쥐면 못 끝난다")
                .succeedsWithin(WHILE_PAUSED)
                .isEqualTo(403);

        대조_해제.countDown();
        assertThat(옳은_시도.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("대조 도중 차단된 계정은 비밀번호가 맞아도 403 AUTH_ACCOUNT_BLOCKED")
                .isEqualTo(403);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM account WHERE login_id = ?", String.class, TARGET)).isEqualTo("blocked");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM account WHERE login_id = ?", Integer.class, TARGET))
                .as("성공 처리가 차단된 계정의 카운터를 0 으로 되돌리면 안 된다")
                .isEqualTo(Account.MAX_FAILED_ATTEMPTS);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM refresh_token t JOIN account a ON a.id = t.account_id
                WHERE a.login_id = ? AND t.revoked_at IS NULL
                """, Integer.class, TARGET))
                .as("차단된 계정에 유효한 refresh 토큰이 발급되면 안 된다")
                .isZero();
    }

    private void 계정을_만든다(String loginId, String rawPassword, String phone, int failedAttempts) {
        jdbcTemplate.update("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status, failed_attempts)
                VALUES (1, ?, ?, '대조경합', ?, 'parent', 'active', ?)
                """, loginId, passwordEncoder.encode(rawPassword), phone, failedAttempts);
    }

    private int 로그인(String loginId, String rawPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"%s\",\"password\":\"%s\"}".formatted(loginId, rawPassword)))
                .andReturn().getResponse().getStatus();
    }
}
