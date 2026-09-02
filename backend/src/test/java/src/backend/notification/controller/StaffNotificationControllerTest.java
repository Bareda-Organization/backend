package src.backend.notification.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.repository.NotificationLogQueryRepository;

/**
 * 관계자 웹의 알림 로그 전수 조회 API(§5.17, Phase 12 T3 goal 10·11) — {@code type}·{@code date}·
 * {@code acked} 필터 각각 매칭·비매칭 행을 함께 심어 필터가 실제로 좁히는지 검사하고, {@code
 * unacked_count} 가 페이지·필터와 무관하게 학원 전체 미확인 건수인지, 푸시 off 로 막힌 행도 전수
 * 조회에서 빠지지 않는지 검사한다.
 *
 * <p>{@code acked} 필드가 T2 의 실제 확인 처리 쓰기 경로를 거쳐 채워지는지 검증하는 경계 시험은
 * 이 워크트리에 그 경로가 부재해 미포함이다 — {@link StaffNotificationFixtures} 클래스 javadoc과
 * report-p12-t3.md 2항 참고. 여기서 쓰는 "이미 확인된" 행은 구조적 시험 데이터일 뿐이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffNotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private NotificationLogQueryRepository notificationLogQueryRepository;

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private StaffNotificationFixtures fixtures() {
        return new StaffNotificationFixtures(academyRepository, accountRepository, academyStaffRepository,
                notificationLogQueryRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ── goal 10 — type 필터 ───────────────────────────────────────────────

    @Test
    @DisplayName("goal10 — type 필터는 매칭 종류만 남기고 비매칭 종류는 뺀다")
    void type_필터는_매칭만_남긴다() throws Exception {
        StaffNotificationFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long matching = fixtures.sentNotification(academyId, NotificationType.BOARDING, "학생1", Role.PARENT, "승차",
                "01호차", now(), now());
        fixtures.sentNotification(academyId, NotificationType.ALIGHTING, "학생1", Role.PARENT, "하차", "01호차", now(),
                now());

        mockMvc.perform(get("/api/v1/staff/notifications").param("type", "boarding")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value(matching))
                .andExpect(jsonPath("$.data.items[0].type").value("boarding"));
    }

    // ── goal 10 — date 필터(sent_at, 없으면 created_at) ─────────────────────

    @Test
    @DisplayName("goal10 — date 필터는 sent_at(없으면 created_at) 이 그 날짜인 행만 남기고 하루 밖은 뺀다")
    void date_필터는_매칭만_남긴다() throws Exception {
        StaffNotificationFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        // now() 는 2030-04-01 12:00 KST 로 고정돼 있다 — 매칭은 같은 날 발송, 비매칭은 하루 전날 발송.
        long matchingSent = fixtures.sentNotification(academyId, NotificationType.RUN_STARTED, "학생1", Role.PARENT,
                "운행 시작", "01호차", now(), now());
        fixtures.sentNotification(academyId, NotificationType.RUN_STARTED, "학생1", Role.PARENT, "운행 시작", "01호차",
                now().minusDays(1), now().minusDays(1));
        // 아직 발송 전(PENDING) 이라 sent_at 이 비어 있는 행 — created_at 이 같은 날이면 매칭돼야 한다.
        long matchingPending = fixtures.notification(academyId, NotificationType.RUN_STARTED, "학생2", Role.PARENT,
                "운행 시작", "01호차", now());

        mockMvc.perform(get("/api/v1/staff/notifications").param("date", "2030-04-01")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[*].notification_id")
                        .value(org.hamcrest.Matchers.containsInAnyOrder((int) matchingSent, (int) matchingPending)));
    }

    // ── goal 10 — acked 필터 ─────────────────────────────────────────────

    @Test
    @DisplayName("goal10 — acked 필터는 매칭 확인 상태만 남긴다")
    void acked_필터는_매칭만_남긴다() throws Exception {
        StaffNotificationFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long ackedId = fixtures.ackedNotification(academyId, NotificationType.NO_SHOW, "학생1", Role.PARENT, "미승차",
                "01호차", now(), now(), now());
        long unackedId = fixtures.sentNotification(academyId, NotificationType.NO_SHOW, "학생2", Role.PARENT, "미승차",
                "01호차", now(), now());

        mockMvc.perform(get("/api/v1/staff/notifications").param("acked", "true")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value(ackedId))
                .andExpect(jsonPath("$.data.items[0].acked").value(true));

        mockMvc.perform(get("/api/v1/staff/notifications").param("acked", "false")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value(unackedId))
                .andExpect(jsonPath("$.data.items[0].acked").value(false));
    }

    // ── goal 10 — 푸시 off 로 차단된 건도 전수 조회에 남는다 ─────────────────

    @Test
    @DisplayName("goal10 — 푸시 off 로 차단(SKIPPED)된 행도 필터 없는 목록에서 빠지지 않는다")
    void 푸시_off로_차단된_건도_전수_조회에_남는다() throws Exception {
        StaffNotificationFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long skippedId = fixtures.skippedNotification(academyId, NotificationType.DELAY, "학생1", Role.PARENT, "지연",
                "01호차", now());

        mockMvc.perform(get("/api/v1/staff/notifications")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value(skippedId));
    }

    // ── goal 11 — unacked_count 는 페이지·필터와 무관 ─────────────────────

    @Test
    @DisplayName("goal11 — unacked_count 는 type 필터를 걸어도 학원 전체 미확인 건수를 그대로 돌려준다")
    void unacked_count는_필터와_무관하게_학원_전체다() throws Exception {
        StaffNotificationFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        // boarding 미확인 1건 + alighting 미확인 1건 + boarding 확인 1건 → 미확인 총 2건.
        fixtures.sentNotification(academyId, NotificationType.BOARDING, "학생1", Role.PARENT, "승차", "01호차", now(),
                now());
        fixtures.sentNotification(academyId, NotificationType.ALIGHTING, "학생2", Role.PARENT, "하차", "01호차", now(),
                now());
        fixtures.ackedNotification(academyId, NotificationType.BOARDING, "학생3", Role.PARENT, "승차", "01호차", now(),
                now(), now());

        // type=boarding 으로 좁히면 boarding 행 2건(미확인 1 + 확인 1)이 나오지만, unacked_count 는
        // 그 페이지의 미확인 개수(1)가 아니라 학원 전체 미확인 개수(boarding 미확인 1 + alighting
        // 미확인 1 = 2)여야 한다.
        mockMvc.perform(get("/api/v1/staff/notifications").param("type", "boarding")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.unacked_count").value(2));
    }

    // ── goal 10·11 — 필드 조립(snake_case 리터럴 검사) ───────────────────

    @Test
    @DisplayName("goal10·11 — 응답 항목 필드가 §5.17 대로 snake_case 로 실린다(bus_no 는 선택)")
    void 필드는_snake_case_로_그대로_나온다() throws Exception {
        StaffNotificationFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long withBus = fixtures.sentNotification(academyId, NotificationType.BOARDING, "학생1", Role.PARENT, "승차",
                "01호차", now(), now());
        // 회차·버스와 무관한 알림 종류는 bus_no 가 없다(계정 승인 결과 통지).
        long withoutBus = fixtures.sentNotification(academyId, NotificationType.SIGNUP_DECIDED, "학부모1", Role.PARENT,
                "가입 승인", null, now(), now());

        mockMvc.perform(get("/api/v1/staff/notifications").param("type", "boarding")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].notification_id").value(withBus))
                .andExpect(jsonPath("$.data.items[0].sent_at").exists())
                .andExpect(jsonPath("$.data.items[0].bus_no").value("01호차"))
                .andExpect(jsonPath("$.data.items[0].recipient_name").value("학생1"))
                .andExpect(jsonPath("$.data.items[0].recipient_role").value("parent"))
                .andExpect(jsonPath("$.data.items[0].type").value("boarding"))
                .andExpect(jsonPath("$.data.items[0].body").value("승차"))
                .andExpect(jsonPath("$.data.items[0].acked").value(false))
                .andExpect(jsonPath("$.data.page").exists())
                .andExpect(jsonPath("$.data.size").exists())
                .andExpect(jsonPath("$.data.total_count").exists())
                .andExpect(jsonPath("$.data.has_next").exists())
                .andExpect(jsonPath("$.data.unacked_count").exists());

        mockMvc.perform(get("/api/v1/staff/notifications").param("type", "signup_decided")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].notification_id").value(withoutBus))
                .andExpect(jsonPath("$.data.items[0].bus_no").doesNotExist());
    }

    // ── 학원 격리 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("goal10 — 필터 없는 목록은 이 학원 알림만 돌려주고 다른 학원 알림은 새지 않는다")
    void 필터_없는_목록은_다른_학원_알림을_섞지_않는다() throws Exception {
        StaffNotificationFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long otherAcademyId = fixtures.academy();
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long matching = fixtures.sentNotification(academyId, NotificationType.ARRIVE, "학생1", Role.PARENT, "도착",
                "01호차", now(), now());
        fixtures.sentNotification(otherAcademyId, NotificationType.ARRIVE, "학생2", Role.PARENT, "도착", "02호차", now(),
                now());

        mockMvc.perform(get("/api/v1/staff/notifications")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value(matching))
                .andExpect(jsonPath("$.data.unacked_count").value(1));
    }

    // ── 호출 도우미 ──────────────────────────────────────────────────────

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
