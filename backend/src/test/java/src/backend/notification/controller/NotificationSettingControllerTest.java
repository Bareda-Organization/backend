package src.backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.notification.repository.NotificationSettingRepository;

/**
 * {@code GET}·{@code PATCH /me/notification-settings}(API_SPEC §3.14, Phase 12 목표 7·9).
 * 목표 7(조회·수정)·목표 9(설정 대상 밖 항목 422) 를 각각 시험한다 — 목표 8(끄면 푸시만 막히고
 * 로그는 남는다)은 발송 경로까지 걸쳐 {@link src.backend.notification.NotificationDispatchGateTest}
 * 가 따로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationSettingControllerTest {

    private static final String NOTIFICATION_SETTINGS = "/api/v1/me/notification-settings";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private NotificationSettingRepository notificationSettingRepository;

    private long parentAccount() {
        String loginId = "p12t1parent" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return accountRepository.save(Account.forSignup(1L, loginId, "{noop}password", "학부모",
                "010-7000-0001", null, Role.PARENT)).getId();
    }

    private long staffAccount() {
        String loginId = "p12t1staff" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return accountRepository.save(Account.forSignup(1L, loginId, "{noop}password", "관계자",
                "010-7000-0002", null, Role.STAFF)).getId();
    }

    private String 토큰(long accountId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, 1L, role, AccountStatus.ACTIVE);
    }

    // ── 목표7 — 조회: 설정 행이 없어도 기본값(전부 on)으로 자가 치유해 돌려준다 ─────

    @Test
    @DisplayName("목표7 — 설정 행이 없는 계정도 GET 하면 기본값(전부 on)을 돌려주고 행을 자가 치유한다")
    void 설정_행이_없어도_GET_하면_기본값을_돌려준다() throws Exception {
        long accountId = parentAccount();
        assertThat(notificationSettingRepository.findById(accountId)).as("사전 조건 — 설정 행이 없다").isEmpty();

        mockMvc.perform(get(NOTIFICATION_SETTINGS).header("Authorization", 토큰(accountId, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrive").value(true))
                .andExpect(jsonPath("$.data.boarding").value(true))
                .andExpect(jsonPath("$.data.no_show").value(true));

        assertThat(notificationSettingRepository.findById(accountId)).as("자가 치유로 행이 생겨야 한다").isPresent();
    }

    // ── 목표7 — 수정: PATCH 로 바뀐 값이 응답과 다음 GET 조회 모두에 반영된다 ─────

    @Test
    @DisplayName("목표7 — PATCH 로 바꾼 값이 그 응답과 다음 GET 조회 모두에 반영된다")
    void PATCH_로_바꾼_값이_다음_조회에도_반영된다() throws Exception {
        long accountId = parentAccount();
        String token = 토큰(accountId, Role.PARENT);

        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrive\":false,\"boarding\":true,\"no_show\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrive").value(false))
                .andExpect(jsonPath("$.data.boarding").value(true))
                .andExpect(jsonPath("$.data.no_show").value(false));

        assertThat(notificationSettingRepository.findById(accountId).orElseThrow().isArrive())
                .as("①DB 값이 실제로 바뀌어야 한다").isFalse();

        mockMvc.perform(get(NOTIFICATION_SETTINGS).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrive").value(false))
                .andExpect(jsonPath("$.data.no_show").value(false));
    }

    // ── 목표7 — 학생 계정도 같은 화면을 쓸 수 있다(API_SPEC §3.14 "학부모·학생") ───

    @Test
    @DisplayName("목표7 — 학생 계정도 조회·수정할 수 있다")
    void 학생_계정도_조회_수정할_수_있다() throws Exception {
        String loginId = "p12t1student" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        long accountId = accountRepository.save(Account.forSignup(1L, loginId, "{noop}password", "학생",
                "010-7000-0003", null, Role.STUDENT)).getId();

        mockMvc.perform(get(NOTIFICATION_SETTINGS).header("Authorization", 토큰(accountId, Role.STUDENT)))
                .andExpect(status().isOk());
    }

    // ── 목표9 — 설정 대상 밖 항목을 PATCH 하면 422 VALIDATION_FAILED ────────────

    @Test
    @DisplayName("목표9 — 설정 대상 밖 항목(delay)을 섞어 PATCH 하면 422 VALIDATION_FAILED 이고 값은 바뀌지 않는다")
    void 설정_대상_밖_항목을_PATCH_하면_422_VALIDATION_FAILED_이다() throws Exception {
        long accountId = parentAccount();
        String token = 토큰(accountId, Role.PARENT);

        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrive\":true,\"boarding\":true,\"no_show\":true,\"delay\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(notificationSettingRepository.findById(accountId))
                .as("거부됐으니 행이 새로 생기면 안 된다 — 유효하지 않은 요청이 자가 치유를 유발하면 안 된다")
                .isEmpty();
    }

    /**
     * 목표 9 는 "대상 밖 항목을 전달하면 422" 이지 "누락하면 통과" 가 아니다 — 3개 필드 표가 전부
     * 필수라서, 일부만 보내는 부분 갱신도 같은 오류로 거부해야 한다(판단 근거는
     * {@code NotificationSettingCommandService} 자바독).
     */
    @Test
    @DisplayName("목표9 — 3개 항목 중 일부만 PATCH 하면(부분 갱신) 422 VALIDATION_FAILED 이다")
    void 항목이_누락된_PATCH_는_422_VALIDATION_FAILED_이다() throws Exception {
        long accountId = parentAccount();

        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", 토큰(accountId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"arrive\":false}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── 목표9 — 3개 항목만 정확히 보내면(대상 안) 200 이다 — 위 422 의 대응하는 양성 사례 ───

    @Test
    @DisplayName("목표9 — 3개 항목만 정확히 PATCH 하면 200 이다")
    void 대상_안_3개_항목만_PATCH_하면_200_이다() throws Exception {
        long accountId = parentAccount();

        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", 토큰(accountId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrive\":true,\"boarding\":false,\"no_show\":true}"))
                .andExpect(status().isOk());
    }

    // ── 학부모·학생 전용 — 학원 관계자(STAFF) 는 이 화면을 쓸 수 없다 ────────────

    @Test
    @DisplayName("STAFF 계정으로 조회·수정하면 403 FORBIDDEN 이다")
    void STAFF_는_403_FORBIDDEN_이다() throws Exception {
        long accountId = staffAccount();
        String token = 토큰(accountId, Role.STAFF);

        mockMvc.perform(get(NOTIFICATION_SETTINGS).header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrive\":true,\"boarding\":true,\"no_show\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
