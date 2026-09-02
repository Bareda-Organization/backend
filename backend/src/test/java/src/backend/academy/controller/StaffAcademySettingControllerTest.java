package src.backend.academy.controller;

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

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademySettingRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 학원별 설정 조회·수정(API_SPEC §5.21, Phase 11 목표 4) — {@code GET}·{@code PATCH
 * /staff/academy-settings}. 완료 조건이 셋(조회·수정·범위 밖 422)이라 이 셋을 각각 시험한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffAcademySettingControllerTest {

    private static final String ACADEMY_SETTINGS = "/api/v1/staff/academy-settings";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademySettingRepository academySettingRepository;

    private long academy() {
        String code = "P11T1F1" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, "학원설정시험학원", "서울", null, null)).getId();
    }

    private long staffAccount(long academyId) {
        String loginId = "staff" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        Account account = accountRepository.save(Account.forSignup(academyId, loginId, "{noop}password", "관계자",
                "010-4444-4444", null, Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, account.getId()));
        return account.getId();
    }

    private long driverAccount(long academyId) {
        String loginId = "driver" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return accountRepository.save(Account.forSignup(academyId, loginId, "{noop}password", "기사",
                "010-5555-5555", null, Role.DRIVER)).getId();
    }

    // ── 목표4 — 조회: 설정 행이 없으면 기본값(3분)으로 자가 치유해 돌려준다 ─────────

    @Test
    @DisplayName("목표4 — 설정 행이 없는 학원도 GET 하면 기본 3분을 돌려주고 행을 자가 치유한다")
    void 설정_행이_없어도_GET_하면_기본값을_돌려준다() throws Exception {
        long academyId = academy();
        long staffAccountId = staffAccount(academyId);
        assertThat(academySettingRepository.findById(academyId)).as("사전 조건 — 설정 행이 없다").isEmpty();

        mockMvc.perform(get(ACADEMY_SETTINGS).header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.no_show_wait_minutes").value(3));

        assertThat(academySettingRepository.findById(academyId)).as("자가 치유로 행이 생겨야 한다").isPresent();
    }

    // ── 목표4 — 수정: PATCH 로 바뀐 값이 다음 조회에 그대로 반영된다 ────────────────

    @Test
    @DisplayName("목표4 — PATCH 로 값을 바꾸면 그 응답과 다음 GET 조회 모두 바뀐 값을 돌려준다")
    void PATCH_로_바꾼_값이_다음_조회에도_반영된다() throws Exception {
        long academyId = academy();
        long staffAccountId = staffAccount(academyId);
        String token = 토큰(staffAccountId, academyId, Role.STAFF);

        mockMvc.perform(patch(ACADEMY_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"no_show_wait_minutes\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.no_show_wait_minutes").value(15));

        assertThat(academySettingRepository.findById(academyId).orElseThrow().getNoShowWaitMinutes())
                .as("①DB 값이 실제로 바뀌어야 한다").isEqualTo(15);

        mockMvc.perform(get(ACADEMY_SETTINGS).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.no_show_wait_minutes").value(15));
    }

    // ── 목표4 — 범위 밖 값은 422 VALIDATION_FAILED ──────────────────────────

    @Test
    @DisplayName("목표4 — 0 이하 값을 PATCH 하면 422 VALIDATION_FAILED 이고 값은 기본값(3분)으로 자가 치유만 된다")
    void 범위_밖_값은_422_VALIDATION_FAILED_이다() throws Exception {
        long academyId = academy();
        long staffAccountId = staffAccount(academyId);
        String token = 토큰(staffAccountId, academyId, Role.STAFF);

        mockMvc.perform(patch(ACADEMY_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"no_show_wait_minutes\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(patch(ACADEMY_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"no_show_wait_minutes\":-5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(academySettingRepository.findById(academyId).orElseThrow().getNoShowWaitMinutes())
                .as("거부된 값이 아니라 자가 치유 기본값(3분)이어야 한다").isEqualTo(3);
    }

    // ── 목표4 — 권한: 학원 관계자(STAFF) 전용, 다른 역할은 거부된다 ────────────────

    @Test
    @DisplayName("목표4 — 기사 토큰으로 조회·수정하면 403 FORBIDDEN 이다")
    void STAFF_가_아니면_403_FORBIDDEN_이다() throws Exception {
        long academyId = academy();
        long driverAccountId = driverAccount(academyId);
        String token = 토큰(driverAccountId, academyId, Role.DRIVER);

        mockMvc.perform(get(ACADEMY_SETTINGS).header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(patch(ACADEMY_SETTINGS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"no_show_wait_minutes\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── 목표4 — 학원 범위: 자기 학원 값만 바뀌고 남의 학원과 섞이지 않는다 ──────────

    @Test
    @DisplayName("목표4 — 한 학원의 PATCH 가 다른 학원의 설정에 영향을 주지 않는다")
    void 학원별_설정이_서로_섞이지_않는다() throws Exception {
        long academyA = academy();
        long staffA = staffAccount(academyA);
        long academyB = academy();
        long staffB = staffAccount(academyB);

        mockMvc.perform(patch(ACADEMY_SETTINGS).header("Authorization", 토큰(staffA, academyA, Role.STAFF))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"no_show_wait_minutes\":20}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(ACADEMY_SETTINGS).header("Authorization", 토큰(staffB, academyB, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.no_show_wait_minutes").value(3));
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
