package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.academy.command.StaffAccountCommandService;
import src.backend.academy.dto.StaffAccountUpdateRequest;
import src.backend.academy.event.StaffResignedEvent;
import src.backend.account.command.AccountUnblockCommandService;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.manager.event.ManagerDeletedEvent;

/**
 * 계정 상태 확인 캐시(R57 RT-1 · Ruling 899 A) — 같은 계정의 연속 요청은 상태를 한 번만 묻고, 차단 · 퇴사 · 매니저 삭제 · 차단 해제가 커밋되면
 * 그 계정 항목을 바로 비워 Ruling 880 의 "다음 요청부터 거절" 이 유지된다.
 *
 * <p>시험 묶음 전체는 캐시를 끄고({@code build.gradle} 의 0초) 직접 SQL 로 상태를 바꾸는 옛 시험이 그대로 돈다 — 이 시험만 5초로 켠다. 차단은
 * 로그인 실패 5회라는 실제 경로로 커밋한다. 서버 스레드가 DB 를 읽으므로 시험 트랜잭션에 묶지 않고 {@link #restore} 가 되돌린다.
 */
@SpringBootTest(properties = "app.security.account-standing-cache-ttl=5s")
@AutoConfigureMockMvc
class AccountStandingCacheTest {

    private static final long PARENT_ACCOUNT = 5L;
    private static final long STAFF_ACCOUNT = 2L;
    private static final long ACADEMY_A = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountStanding accountStanding;

    @Autowired
    private AccountUnblockCommandService accountUnblockCommandService;

    @Autowired
    private StaffAccountCommandService staffAccountCommandService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoSpyBean
    private AccountRepository accountRepository;

    @BeforeEach
    void startCold() {
        accountStanding.evict(PARENT_ACCOUNT); // 같은 컨텍스트의 앞선 시험이 남긴 항목을 치운다
    }

    @AfterEach
    void restore() {
        jdbcTemplate.update("UPDATE account SET status = 'active', status_before_block = NULL, blocked_at = NULL, "
                + "block_reason = NULL, failed_attempts = 0 WHERE id = ?", PARENT_ACCOUNT);
        jdbcTemplate.update("UPDATE academy_staff SET status = 'active' WHERE account_id = ?", STAFF_ACCOUNT);
        accountStanding.evict(STAFF_ACCOUNT);
    }

    @Test
    void 같은_계정의_연속_요청_10회는_계정_상태를_한_번만_묻는다() throws Exception {
        String token = bearer();
        clearInvocations(accountRepository);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isOk());
        }

        verify(accountRepository, times(1)).findIdsWithLostStanding(anyCollection());
    }

    @Test
    void 로그인_실패로_차단이_커밋되면_캐시에_남은_옛_값과_상관없이_다음_요청이_TOKEN_EXPIRED_다() throws Exception {
        String token = bearer();
        mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isOk());

        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"login_id\":\"parentA1\",\"password\":\"wrong-password\"}"));
        }

        mockMvc.perform(get("/api/v1/me").header("Authorization", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    /** 해제는 실제 경로(메인 관리자의 차단 해제 서비스)로 커밋한다 — 이 경로가 비움 신호를 내지 않으면 5초 동안 해제된 계정이 거절된다. */
    @Test
    void 차단이_해제되면_캐시에_남은_차단_값이_아니라_현재_상태로_통과한다() throws Exception {
        jdbcTemplate.update("UPDATE account SET status = 'blocked', status_before_block = 'active', "
                + "blocked_at = now(), block_reason = '시험' WHERE id = ?", PARENT_ACCOUNT);
        String token = bearer();
        mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isUnauthorized());

        accountUnblockCommandService.unblock(PARENT_ACCOUNT, STAFF_ACCOUNT);

        mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isOk());
    }

    /** 재직 복귀는 실제 서비스 경로로 커밋한다 — 이 경로의 비움 발행이 빠지면 복귀한 관계자가 5초 동안 거절된다. */
    @Test
    void 재직_복귀가_커밋되면_캐시에_남은_퇴사_값이_아니라_현재_상태로_통과한다() throws Exception {
        jdbcTemplate.update("UPDATE academy_staff SET status = 'inactive' WHERE account_id = ?", STAFF_ACCOUNT);
        accountStanding.evict(STAFF_ACCOUNT);
        String token = "Bearer " + tokenProvider.createAccessToken(STAFF_ACCOUNT, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
        mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isUnauthorized()); // 퇴사 값이 보관된다

        staffAccountCommandService.update(STAFF_ACCOUNT, new StaffAccountUpdateRequest(null, null, null, null, "active"));

        mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isOk());
    }

    /** 퇴사 · 매니저 삭제는 이미 있는 이벤트를 같은 리스너가 받는다 — 트랜잭션 밖에서 낸 이벤트도 비운다. */
    @Test
    void 퇴사_이벤트와_매니저_삭제_이벤트도_보관_항목을_비운다() throws Exception {
        String token = bearer();
        mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE account SET status = 'blocked', status_before_block = 'active', "
                + "blocked_at = now(), block_reason = '시험' WHERE id = ?", PARENT_ACCOUNT);
        mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isOk()); // 보관된 옛 값

        publishInTransaction(new StaffResignedEvent(PARENT_ACCOUNT, OffsetDateTime.now()));
        mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isUnauthorized());

        jdbcTemplate.update("UPDATE account SET status = 'active', status_before_block = NULL, blocked_at = NULL, "
                + "block_reason = NULL WHERE id = ?", PARENT_ACCOUNT);
        publishInTransaction(new AccountStandingChangedEvent(PARENT_ACCOUNT));
        mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE account SET status = 'blocked', status_before_block = 'active', "
                + "blocked_at = now(), block_reason = '시험' WHERE id = ?", PARENT_ACCOUNT);

        publishInTransaction(new ManagerDeletedEvent(PARENT_ACCOUNT, OffsetDateTime.now()));
        mockMvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isUnauthorized());
    }

    /** 요청 트랜잭션처럼 커밋되는 경로로 낸다 — 같은 이벤트를 받는 동기 리스너(refresh 무효화 등)가 트랜잭션을 요구한다. */
    private void publishInTransaction(Object event) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(PARENT_ACCOUNT, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);
    }
}
