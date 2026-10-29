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
import org.junit.jupiter.api.DisplayName;
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
 * 이미 있는 행에 대한 두 쓰기가 겹쳐도 서로의 변경을 지우지 않는다 — 같은 계정의 서로 다른 알림 토글 변경 둘, 서로 다른 계정이 같은 푸시 토큰을 등록하는 둘.
 * 두 요청이 행을 읽고(또는 다른 계정의 해지를 마치고) 쓰기 전에 만나도록 시험이 조회 직후에 두 스레드를 모아 겹침을 만든다. 트랜잭션 없이 실제 커밋을 남기므로 만든 행은 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RepositoryReadHooks.class)
class NotificationWriteRaceTest {

    private static final long PARENT_ACCOUNT = 6L;

    private static final long OTHER_PARENT_ACCOUNT = 5L;

    private static final String TOKEN = "race-shared-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private List<Map<String, Object>> savedSetting;

    @BeforeEach
    void 행을_정리해_둔다() {
        savedSetting = jdbcTemplate.queryForList("SELECT * FROM notification_setting WHERE account_id = ?", PARENT_ACCOUNT);
        jdbcTemplate.update("DELETE FROM notification_setting WHERE account_id = ?", PARENT_ACCOUNT);
        jdbcTemplate.update("DELETE FROM device_token WHERE token = ?", TOKEN);
    }

    @AfterEach
    void 원래대로_돌린다() {
        executor.shutdownNow();
        RepositoryReadHooks.clear();
        jdbcTemplate.update("DELETE FROM notification_setting WHERE account_id = ?", PARENT_ACCOUNT);
        savedSetting.forEach(row -> jdbcTemplate.update(
                "INSERT INTO notification_setting (account_id, arrive, boarding, no_show, updated_at) VALUES (?, ?, ?, ?, ?)",
                row.get("account_id"), row.get("arrive"), row.get("boarding"), row.get("no_show"), row.get("updated_at")));
        jdbcTemplate.update("DELETE FROM device_token WHERE token = ?", TOKEN);
    }

    @Test
    @DisplayName("같은 계정의 서로 다른 토글 변경 두 건이 겹쳐도 둘 다 남는다")
    void 서로_다른_토글_변경_두_건은_둘_다_남는다() throws Exception {
        jdbcTemplate.update("INSERT INTO notification_setting (account_id, arrive, boarding, no_show, updated_at) "
                + "VALUES (?, true, true, true, now())", PARENT_ACCOUNT);
        모이게_한다("findById", "findLockedById");

        List<Integer> statuses = 둘이_동시에(
                () -> 설정을_바꾼다("{\"arrive\": false}", PARENT_ACCOUNT),
                () -> 설정을_바꾼다("{\"boarding\": false}", PARENT_ACCOUNT));

        assertThat(statuses).containsExactly(200, 200);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT arrive, boarding, no_show FROM notification_setting WHERE account_id = ?", PARENT_ACCOUNT);
        assertThat(row).as("한쪽이 다른 쪽의 옛 값을 되써서 변경이 사라지면 안 된다")
                .containsEntry("arrive", false).containsEntry("boarding", false).containsEntry("no_show", true);
    }

    @Test
    @DisplayName("서로 다른 계정이 같은 푸시 토큰을 동시에 등록해도 유효한 행은 하나다")
    void 같은_토큰을_동시에_등록한_두_계정의_유효_행은_하나다() throws Exception {
        모이게_한다("revokeOtherAccounts");

        List<Integer> statuses = 둘이_동시에(
                () -> 단말을_등록한다(PARENT_ACCOUNT, "race-device-a"),
                () -> 단말을_등록한다(OTHER_PARENT_ACCOUNT, "race-device-b"));

        assertThat(statuses).allMatch(status -> status >= 200 && status < 300);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM device_token WHERE token = ? AND revoked_at IS NULL", Integer.class, TOKEN))
                .as("한 기기의 토큰은 한 계정에만 유효하다").isEqualTo(1);
    }

    private int 설정을_바꾼다(String body, long accountId) throws Exception {
        return mockMvc.perform(patch("/api/v1/me/notification-settings")
                        .header("Authorization", 부모_토큰(accountId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private int 단말을_등록한다(long accountId, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/v1/me/devices")
                        .header("Authorization", 부모_토큰(accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device_id\": \"" + deviceId + "\", \"token\": \"" + TOKEN + "\", \"platform\": \"android\"}"))
                .andReturn().getResponse().getStatus();
    }

    private String 부모_토큰(long accountId) {
        return 토큰(tokenProvider, accountId, Long.valueOf(SeedFixtures.ACADEMY_A_ID), Role.PARENT);
    }

    /** 그 이름들의 저장소 메서드가 끝난 직후, 쓰기 서비스 안에서 처음 불린 두 번의 호출을 한 곳에 모은다. */
    private void 모이게_한다(String... repositoryMethods) {
        CyclicBarrier together = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        Runnable hook = () -> {
            if (호출한_서비스가_대상이다() && calls.incrementAndGet() <= 2) {
                기다린다(together);
            }
        };
        for (String method : repositoryMethods) {
            RepositoryReadHooks.afterRead(method, hook);
        }
    }

    /**
     * 상대가 올 때까지 최대 2초 기다린다. 쓰기를 직렬화하는 구현에서는 상대가 이쪽 커밋을 기다리느라 오지 못하므로 안 와도 예외 없이 이어간다 —
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

    private static boolean 호출한_서비스가_대상이다() {
        return java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(frame -> frame.getClassName().contains("CommandService"));
    }

    private List<Integer> 둘이_동시에(Callable<Integer> first, Callable<Integer> second) throws Exception {
        Future<Integer> a = executor.submit(first);
        Future<Integer> b = executor.submit(second);
        return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }
}
