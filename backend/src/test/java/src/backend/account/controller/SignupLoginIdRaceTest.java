package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 같은 {@code login_id} 로 동시에 가입하면 한 건만 201 이고 나머지는 500 이 아니라 409 {@code DUPLICATE_LOGIN_ID} 다(C-5).
 *
 * <p>두 요청이 중복 확인을 <b>둘 다 통과한 뒤</b> 저장에서 {@code uk_account_login_id} 로 만나야 제약 위반을 409 로 옮기는 분기가
 * 시험된다. 늦게 온 요청이 사전 확인에서 같은 409 로 거절되면 그 분기를 지나지 않고도 통과하므로, 비밀번호 해시 단계(사전 확인
 * 뒤 · 저장 앞)에 두 요청이 모두 닿을 때까지 서로 기다리게 해 만남을 고정한다. {@code @Transactional} 이 부재하다 — 요청마다 자기
 * 트랜잭션을 커밋해야 경쟁이 성립한다.
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

    /** 사전 확인 뒤 · 저장 앞에서 실행되는 해시 단계를 붙잡는 자리. */
    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    @AfterEach
    void 뒷정리() {
        jdbcTemplate.update("DELETE FROM signup_request WHERE account_id IN (SELECT id FROM account WHERE login_id = ?)",
                LOGIN_ID);
        jdbcTemplate.update("DELETE FROM account WHERE login_id = ?", LOGIN_ID);
    }

    @Test
    void 같은_아이디로_동시에_가입하면_한_건은_201_나머지는_409_이다() throws Exception {
        CyclicBarrier bothPassedPreCheck = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothPassedPreCheck.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());
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
