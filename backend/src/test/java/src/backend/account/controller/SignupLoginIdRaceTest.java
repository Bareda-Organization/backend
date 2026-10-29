package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 같은 {@code login_id} 로 동시에 가입하면 한 건만 201 이고 나머지는 500 이 아니라 409 {@code DUPLICATE_LOGIN_ID} 다(C-5).
 *
 * <p>두 요청이 중복 확인을 함께 통과하는 순서가 이 시험의 재료다 — 확인 뒤 BCrypt 해시(수십~수백 ms)를 만들고서야 저장하므로 동시에
 * 출발한 두 요청은 저장 시점에 {@code uk_account_login_id} 에서 만난다. 순차로 두 번 부르는 시험은 사전 확인에서 걸려 이 길을 지나지
 * 않는다. {@code @Transactional} 이 부재하다 — 요청마다 자기 트랜잭션을 커밋해야 경쟁이 성립한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SignupLoginIdRaceTest {

    private static final String LOGIN_ID = "race.signup";

    private static final long TIMEOUT_SECONDS = 60;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void 뒷정리() {
        jdbcTemplate.update("DELETE FROM signup_request WHERE account_id IN (SELECT id FROM account WHERE login_id = ?)",
                LOGIN_ID);
        jdbcTemplate.update("DELETE FROM account WHERE login_id = ?", LOGIN_ID);
    }

    @Test
    void 같은_아이디로_동시에_가입하면_한_건은_201_나머지는_409_이다() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        Callable<MvcResult> signup = () -> {
            go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                    {"role": "parent", "login_id": "%s", "password": "password1234",
                     "name": "경쟁가입", "phone": "010-0000-7002", "academy_id": "1"}
                    """.formatted(LOGIN_ID))).andReturn();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = pool.submit(signup);
            Future<MvcResult> second = pool.submit(signup);
            go.countDown();
            List<MvcResult> results = List.of(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            List<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).toList();
            assertThat(statuses).as("본문=%s", results.stream().map(this::본문).toList())
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 409).map(this::본문))
                    .singleElement().asString().contains("DUPLICATE_LOGIN_ID");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM account WHERE login_id = ?", Integer.class,
                    LOGIN_ID)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private String 본문(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
