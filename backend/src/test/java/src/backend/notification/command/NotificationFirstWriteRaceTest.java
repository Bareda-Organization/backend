package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static testsupport.auth.TestTokens.토큰;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import testsupport.concurrency.RepositoryReadHooks;

/**
 * 같은 계정의 첫 쓰기 두 건이 겹쳐도 500 이 나지 않는다 — 알림 설정은 행이 없을 때 만들고, 단말 등록은 같은 (계정, 단말) 행을 바꿔 심는다.
 * 두 요청이 기존 행 조회를 끝낸 뒤 함께 쓰도록 조회 직후에 두 스레드를 모아 겹침을 시험이 만든다. 트랜잭션 없이 실제 커밋을 남기므로
 * 만든 행은 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RepositoryReadHooks.class)
class NotificationFirstWriteRaceTest {

    private static final long PARENT_ACCOUNT = 6L;

    private static final String DEVICE_ID = "race-device";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private List<Map<String, Object>> savedSetting;

    @BeforeEach
    void 설정_행을_치워_둔다() {
        savedSetting = jdbcTemplate.queryForList("SELECT * FROM notification_setting WHERE account_id = ?", PARENT_ACCOUNT);
        jdbcTemplate.update("DELETE FROM notification_setting WHERE account_id = ?", PARENT_ACCOUNT);
        jdbcTemplate.update("DELETE FROM device_token WHERE device_id = ?", DEVICE_ID);
    }

    @AfterEach
    void 원래대로_돌린다() {
        executor.shutdownNow();
        RepositoryReadHooks.clear();
        jdbcTemplate.update("DELETE FROM notification_setting WHERE account_id = ?", PARENT_ACCOUNT);
        savedSetting.forEach(row -> jdbcTemplate.update(
                "INSERT INTO notification_setting (account_id, arrive, boarding, no_show, updated_at) VALUES (?, ?, ?, ?, ?)",
                row.get("account_id"), row.get("arrive"), row.get("boarding"), row.get("no_show"), row.get("updated_at")));
        jdbcTemplate.update("DELETE FROM device_token WHERE device_id = ?", DEVICE_ID);
    }

    @Test
    void 설정_행이_없는_계정의_첫_변경_두_건은_둘_다_200_이다() throws Exception {
        모이게_한다("findById");

        List<Integer> statuses = 둘이_동시에(() -> mockMvc.perform(patch("/api/v1/me/notification-settings")
                        .header("Authorization", 부모_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrive\": false}"))
                .andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactly(200, 200);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_setting WHERE account_id = ?",
                Integer.class, PARENT_ACCOUNT)).isEqualTo(1);
    }

    @Test
    void 같은_단말을_처음_등록하는_두_요청은_둘_다_성공한다() throws Exception {
        모이게_한다("findByAccountIdAndDeviceId");

        List<Integer> statuses = 둘이_동시에(() -> mockMvc.perform(post("/api/v1/me/devices")
                        .header("Authorization", 부모_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device_id\": \"" + DEVICE_ID + "\", \"token\": \"race-token\", \"platform\": \"android\"}"))
                .andReturn().getResponse().getStatus());

        assertThat(statuses).allMatch(status -> status >= 200 && status < 300);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM device_token WHERE device_id = ?",
                Integer.class, DEVICE_ID)).isEqualTo(1);
    }

    private String 부모_토큰() {
        return 토큰(tokenProvider, PARENT_ACCOUNT, Long.valueOf(SeedFixtures.ACADEMY_A_ID), Role.PARENT);
    }

    /** 그 이름의 저장소 조회가 끝난 직후, 처음 두 번의 호출을 한 곳에 모은다. */
    private void 모이게_한다(String repositoryMethod) {
        CyclicBarrier together = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        RepositoryReadHooks.afterRead(repositoryMethod, () -> {
            if (!호출한_서비스가_대상이다()) {
                return;
            }
            if (calls.incrementAndGet() <= 2) {
                기다린다(together);
            }
        });
    }

    /**
     * 상대가 올 때까지 최대 2초 기다린다. 쓰기를 한 문장으로 직렬화하는 구현에서는 상대가 이쪽 커밋을 기다리느라 오지 못하므로, 안 와도 예외 없이 이어간다 —
     * 겹침을 만들 수 있는 구현에서만 둘이 함께 통과한다.
     */
    private static void 기다린다(CyclicBarrier together) {
        try {
            together.await(2, TimeUnit.SECONDS);
        } catch (BrokenBarrierException | TimeoutException e) {
            // 상대가 오지 않았다 — 그대로 이어간다
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 같은 이름의 조회가 요청 경로의 다른 곳(인증 등)에서도 불리므로, 쓰기 서비스 안에서 불린 것만 모은다. */
    private static boolean 호출한_서비스가_대상이다() {
        return java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(frame -> frame.getClassName().contains("CommandService"));
    }

    private List<Integer> 둘이_동시에(Callable<Integer> request) throws Exception {
        Future<Integer> first = executor.submit(request);
        Future<Integer> second = executor.submit(request);
        return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
    }
}
