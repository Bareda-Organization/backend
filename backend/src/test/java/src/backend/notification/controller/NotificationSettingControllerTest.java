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
import src.backend.notification.entity.NotificationSetting;
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

    // ── 목표7 — 조회: 설정 행이 없어도 기본값(전부 on)을 돌려준다(저장은 하지 않는다, BR-096) ─────

    @Test
    @DisplayName("목표7 — 설정 행이 없는 계정도 GET 하면 기본값(전부 on)을 돌려주되 행을 만들지 않는다")
    void 설정_행이_없어도_GET_하면_기본값을_돌려준다() throws Exception {
        long accountId = parentAccount();
        assertThat(notificationSettingRepository.findById(accountId)).as("사전 조건 — 설정 행이 없다").isEmpty();

        mockMvc.perform(get(NOTIFICATION_SETTINGS).header("Authorization", 토큰(accountId, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrive").value(true))
                .andExpect(jsonPath("$.data.boarding").value(true))
                .andExpect(jsonPath("$.data.no_show").value(true));

        assertThat(notificationSettingRepository.findById(accountId))
                .as("조회는 쓰기를 겸하지 않는다(BR-096) — 행을 만드는 것은 PATCH 뿐이다").isEmpty();
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

        String token = 토큰(accountId, Role.STUDENT);

        mockMvc.perform(get(NOTIFICATION_SETTINGS).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrive").value(true));

        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrive\":false,\"boarding\":true,\"no_show\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrive").value(false));

        mockMvc.perform(get(NOTIFICATION_SETTINGS).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrive").value(false));
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
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(notificationSettingRepository.findById(accountId))
                .as("거부됐으니 행이 새로 생기면 안 된다 — 유효하지 않은 요청이 자가 치유를 유발하면 안 된다")
                .isEmpty();
    }

    /**
     * Ruling 869 — 보낸 항목만 바꾸고 빠진 키는 그대로 둔다(API_SPEC §1.14). 이전에는 일부만 보내면 422 였다.
     */
    @Test
    @DisplayName("Ruling 869 — 한 항목만 PATCH 하면 그 항목만 바뀌고 나머지는 그대로다")
    void 한_항목만_PATCH_하면_나머지는_그대로다() throws Exception {
        long accountId = parentAccount();
        String token = 토큰(accountId, Role.PARENT);
        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrive\":true,\"boarding\":false,\"no_show\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"arrive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrive").value(false))
                .andExpect(jsonPath("$.data.boarding").value(false))
                .andExpect(jsonPath("$.data.no_show").value(true));

        NotificationSetting saved = notificationSettingRepository.findById(accountId).orElseThrow();
        assertThat(saved.isArrive()).isFalse();
        assertThat(saved.isBoarding()).as("보내지 않은 항목은 직전 값(꺼짐)을 유지해야 한다").isFalse();
        assertThat(saved.isNoShow()).isTrue();
    }

    /** 아무 키도 없는 요청은 바꿀 것이 없다 — §1.14 "보낸 필드만 고친다" 라 200 이고 값은 그대로다. */
    @Test
    @DisplayName("Ruling 869 — 아무 항목도 없는 PATCH 는 200 이고 값은 그대로다")
    void 빈_본문_PATCH_는_200_이고_값은_그대로다() throws Exception {
        long accountId = parentAccount();
        String token = 토큰(accountId, Role.PARENT);
        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrive\":true,\"boarding\":false,\"no_show\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.boarding").value(false));
    }

    /** §1.14 — 이 PATCH 는 {@code null} = 유지다(키가 없는 것과 같다). */
    @Test
    @DisplayName("Ruling 869 — null 로 보낸 항목은 유지된다")
    void null_항목은_유지된다() throws Exception {
        long accountId = parentAccount();
        String token = 토큰(accountId, Role.PARENT);
        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrive\":true,\"boarding\":false,\"no_show\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"boarding\":null,\"no_show\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.boarding").value(false))
                .andExpect(jsonPath("$.data.no_show").value(false));
    }

    // ── 목표9 — 3개 항목만 정확히 보내면(대상 안) 200 이다 — 위 422 의 대응하는 양성 사례 ───

    @Test
    @DisplayName("목표9 — 3개 항목만 정확히 PATCH 하면 200 이다")
    void 대상_안_3개_항목만_PATCH_하면_200_이다() throws Exception {
        long accountId = parentAccount();

        mockMvc.perform(patch(NOTIFICATION_SETTINGS).header("Authorization", 토큰(accountId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrive\":true,\"boarding\":false,\"no_show\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.boarding").value(false));

        assertThat(notificationSettingRepository.findById(accountId).orElseThrow().isBoarding())
                .as("200 만 오고 저장이 안 됐다").isFalse();
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
