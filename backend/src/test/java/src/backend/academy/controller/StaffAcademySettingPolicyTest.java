package src.backend.academy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.policy.PolicyConstants;
import src.backend.global.retention.RetentionPolicy;
import src.backend.global.security.JwtTokenProvider;
import src.backend.location.proximity.ProximityJudge;
import src.backend.request.entity.BoardingIntent;
import src.backend.routing.domain.GeoPoint;
import src.backend.run.domain.RunConfirmationPolicy;
import src.backend.run.domain.RunStartWindowPolicy;

/**
 * R48 이 학원 설정 {@code GET} 에 더한 읽기 전용 {@code academy{}} · {@code policy{}}(API_SPEC §5.21, Ruling 820).
 *
 * <p>{@code policy} 의 숫자를 시험에 박지 않는다 — 박으면 상수를 바꿀 때 시험도 같이 바뀌어 "화면 값과 서버 동작이 같다" 를
 * 아무것도 보증하지 않는다. 값은 {@link PolicyConstants}(와 보존 상수)를 참조하고, 더 나아가 <b>실제 판정 코드가 그 값대로
 * 움직이는지</b>까지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffAcademySettingPolicyTest {

    private static final String SETTINGS = "/api/v1/staff/academy-settings";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    /** {@code academy} 는 요청자 학원의 이름 · 코드 · 지역 · 상태(소문자)다 — 토큰의 학원이지 요청이 고른 학원이 아니다. */
    @Test
    void GET_은_요청자_학원의_name_code_region_status_를_싣는다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R48POL001", "R48정책학원", "부천", null, null));

        mockMvc.perform(get(SETTINGS).header("Authorization", 관계자_토큰(academy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.academy.name").value("R48정책학원"))
                .andExpect(jsonPath("$.data.academy.code").value("R48POL001"))
                .andExpect(jsonPath("$.data.academy.region").value("부천"))
                .andExpect(jsonPath("$.data.academy.status").value("active"));
    }

    /** {@code policy} 6개 값은 서버가 쓰는 상수 그대로다 — 숫자를 시험에 박지 않고 상수를 읽어 견준다. */
    @Test
    void GET_policy_는_서버_상수를_그대로_싣는다() throws Exception {
        String body = 설정을_읽는다();

        assertThat(정수(body, "confirm_lead_minutes")).isEqualTo(PolicyConstants.CONFIRM_LEAD.toMinutes());
        assertThat(정수(body, "start_window_minutes")).isEqualTo(PolicyConstants.START_WINDOW.toMinutes());
        assertThat(정수(body, "change_quota_per_run")).isEqualTo(PolicyConstants.CHANGE_QUOTA_PER_RUN);
        assertThat(정수(body, "delay_unit_minutes")).isEqualTo(PolicyConstants.DELAY_UNIT_MINUTES);
        assertThat(정수(body, "proximity_alert_meters")).isEqualTo(PolicyConstants.PROXIMITY_ALERT_METERS);
        assertThat(정수(body, "notification_retention_days"))
                .isEqualTo(RetentionPolicy.NOTIFICATION_LOG_RETENTION.toDays());
    }

    /**
     * 응답이 싣는 값이 <b>실제 판정 코드의 동작</b>과 같다 — 확정 시각 · 시작 창 · 근접 거리 · 변경 한도를 응답 값으로 시험한다.
     * 응답만 상수를 읽고 판정 코드가 따로 숫자를 쥐고 있으면(옛 구조) 이 시험이 갈라진 쪽에서 실패한다.
     */
    @Test
    void 응답의_policy_값대로_실제_판정이_움직인다() throws Exception {
        String body = 설정을_읽는다();
        OffsetDateTime depart = OffsetDateTime.parse("2026-10-05T16:00:00+09:00");

        assertThat(Duration.between(RunConfirmationPolicy.confirmAtOf(depart), depart).toMinutes())
                .as("확정 시각은 출발 confirm_lead_minutes 전이다").isEqualTo(정수(body, "confirm_lead_minutes"));

        RunStartWindowPolicy window = new RunStartWindowPolicy();
        long startWindow = 정수(body, "start_window_minutes");
        assertThat(Duration.between(window.earliestStart(depart), depart).toMinutes()).isEqualTo(startWindow);
        assertThat(Duration.between(depart, window.latestStart(depart)).toMinutes()).isEqualTo(startWindow);

        ProximityJudge judge = new ProximityJudge();
        long meters = 정수(body, "proximity_alert_meters");
        GeoPoint stop = new GeoPoint(new BigDecimal("37.500000"), new BigDecimal("127.000000"));
        assertThat(judge.isWithinThreshold(북쪽으로(stop, meters - 5), stop)).as("기준 안쪽 5m").isTrue();
        assertThat(judge.isWithinThreshold(북쪽으로(stop, meters + 5), stop)).as("기준 바깥 5m").isFalse();

        BoardingIntent intent = BoardingIntent.forRun(1L, 1L, depart);
        long quota = 정수(body, "change_quota_per_run");
        for (long used = 0; used < quota; used++) {
            assertThat(intent.hasChangeQuota()).as("한도 안에서는 변경이 남아 있다").isTrue();
            intent.consumeChangeQuota();
        }
        assertThat(intent.hasChangeQuota()).as("한도를 다 쓰면 더 변경할 수 없다").isFalse();
    }

    /** {@code PATCH} 응답은 그대로다 — {@code academy}·{@code policy} 는 {@code GET} 에만 싣는다. */
    @Test
    void PATCH_응답에는_academy_와_policy_가_없다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R48POL002", "R48정책학원2", "부천", null, null));

        mockMvc.perform(patch(SETTINGS).header("Authorization", 관계자_토큰(academy))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"no_show_wait_minutes\": 7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.no_show_wait_minutes").value(7))
                .andExpect(jsonPath("$.data.academy").doesNotExist())
                .andExpect(jsonPath("$.data.policy").doesNotExist());
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 설정을_읽는다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R48POLRD1", "R48정책읽기", "부천", null, null));
        return mockMvc.perform(get(SETTINGS).header("Authorization", 관계자_토큰(academy)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long 정수(String body, String key) {
        return JsonPath.<Number>read(body, "$.data.policy." + key).longValue();
    }

    /** 정류장에서 북쪽으로 {@code meters} 떨어진 점 — 위도 1도가 약 111,195m 다(지구 반경 6,371,008.8m). */
    private GeoPoint 북쪽으로(GeoPoint origin, long meters) {
        BigDecimal degrees = BigDecimal.valueOf(meters).divide(new BigDecimal("111194.93"), 8, RoundingMode.HALF_UP);
        return new GeoPoint(origin.lat().add(degrees).setScale(6, RoundingMode.HALF_UP), origin.lng());
    }

    private String 관계자_토큰(Academy academy) {
        Account account = accountRepository.save(Account.forSignup(academy.getId(),
                "r48pol" + Math.abs(System.nanoTime() % 1_000_000_000L), "x", "관계자", "010-4806-0000", null,
                Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academy.getId(), account.getId()));
        return "Bearer " + tokenProvider.createAccessToken(account.getId(), academy.getId(), Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
