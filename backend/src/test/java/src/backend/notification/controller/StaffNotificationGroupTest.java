package src.backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.repository.NotificationLogRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 알림 로그 R48 확장(API_SPEC §5.17, Ruling 813) — {@code recipient_role} 쿼리와 {@code group=true} 묶어 보기. 묶음 키는 같은
 * {@code type} · {@code run_id} · {@code body} · 적재 시각({@code created_at}) 초 단위이고, <b>쪽 나누기와 {@code total_count} 가 묶음
 * 단위</b>이며, {@code acked} 필터는 묶음 안에 그 상태 행이 하나라도 있으면 그 묶음을 싣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class StaffNotificationGroupTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    private StaffNotificationFixtures fixtures() {
        return new StaffNotificationFixtures(academyRepository, accountRepository, academyStaffRepository,
                notificationLogRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** 같은 사건(type·run·body·초)은 한 묶음이고, 다른 초·다른 body·다른 type·다른 run 은 다른 묶음이다 — 쪽·total_count 는 묶음 단위. */
    @Test
    void 묶음_키가_같으면_한_묶음이고_쪽_나누기와_total_count_는_묶음_단위다() throws Exception {
        StaffNotificationFixtures fx = fixtures();
        long academyId = fx.academy();
        long staffAccountId = fx.staffAccount(academyId, "관계자");
        OffsetDateTime t = now();
        for (int i = 1; i <= 5; i++) { // G1 — 5명, 같은 초
            fx.sentNotification(academyId, NotificationType.RUN_STARTED, "수신" + i, Role.PARENT, "출발했습니다", "01호차",
                    t.plusNanos(i * 1_000_000L), t.plusSeconds(5));
        }
        for (int i = 1; i <= 2; i++) { // G2 — 같은 type·body, 2초 뒤 적재(다른 묶음)
            fx.sentNotification(academyId, NotificationType.RUN_STARTED, "늦은수신" + i, Role.PARENT, "출발했습니다", "01호차",
                    t.plusSeconds(2), t.plusSeconds(7));
        }
        for (int i = 1; i <= 2; i++) { // G3 — 같은 시각, 다른 body
            fx.sentNotification(academyId, NotificationType.RUN_STARTED, "다른문구" + i, Role.PARENT, "문구가 다르다", "01호차",
                    t, t.plusSeconds(4));
        }
        fx.sentNotification(academyId, NotificationType.BOARDING, "다른종류", Role.STAFF, "출발했습니다", "01호차", t,
                t.plusSeconds(3)); // G4 — 다른 type
        long run77a = fx.sentNotification(academyId, NotificationType.RUN_STARTED, "다른회차1", Role.PARENT, "출발했습니다",
                "01호차", t, t.plusSeconds(2));
        long run77b = fx.sentNotification(academyId, NotificationType.RUN_STARTED, "다른회차2", Role.PARENT, "출발했습니다",
                "01호차", t, t.plusSeconds(2)); // G5 — 같은 type·body·초지만 run 이 다르다
        entityManager.flush();
        jdbcTemplate.update("UPDATE notification_log SET run_id = 77 WHERE id IN (?, ?)", run77a, run77b);
        entityManager.clear();
        String token = 토큰(staffAccountId, academyId);

        String ungrouped = 본문("/api/v1/staff/notifications?size=100", token);
        assertThat((int) JsonPath.read(ungrouped, "$.data.total_count")).as("묶지 않으면 행 12건").isEqualTo(12);

        String firstPage = 본문("/api/v1/staff/notifications?group=true&page=0&size=2", token);
        assertThat((int) JsonPath.read(firstPage, "$.data.total_count")).as("묶음 5개 — 행 수가 아니다").isEqualTo(5);
        assertThat((int) JsonPath.read(firstPage, "$.data.items.length()")).isEqualTo(2);
        assertThat((boolean) JsonPath.read(firstPage, "$.data.has_next")).isTrue();
        String lastPage = 본문("/api/v1/staff/notifications?group=true&page=2&size=2", token);
        assertThat((int) JsonPath.read(lastPage, "$.data.items.length()")).as("5개 묶음의 마지막 쪽은 1개").isEqualTo(1);
        assertThat((boolean) JsonPath.read(lastPage, "$.data.has_next")).isFalse();

        String all = 본문("/api/v1/staff/notifications?group=true&size=100", token);
        assertThat(JsonPath.<List<Integer>>read(all, "$.data.items[*].recipient_count"))
                .containsExactlyInAnyOrder(5, 2, 2, 1, 2);
        Map<String, Object> g1 = 묶음(all, 5);
        assertThat(g1.get("type")).isEqualTo("run_started");
        assertThat(g1.get("body")).isEqualTo("출발했습니다");
        assertThat(g1.get("bus_no")).isEqualTo("01호차");
        assertThat(g1.get("group_key")).isInstanceOf(String.class);
        assertThat(g1.get("sent_at")).isNotNull();
        assertThat(g1.get("acked_count")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recipients = (List<Map<String, Object>>) g1.get("recipients");
        assertThat(recipients).as("앞 3명").extracting(r -> r.get("recipient_name")).containsExactly("수신1", "수신2", "수신3");
        assertThat(recipients.get(0).get("recipient_role")).isEqualTo("parent");
        assertThat(JsonPath.<List<String>>read(all, "$.data.items[*].group_key")).doesNotHaveDuplicates();
        assertThat((int) JsonPath.read(all, "$.data.unacked_count")).as("미확인 배지는 묶지 않은 행 기준 그대로").isEqualTo(0);
    }

    @Test
    void recipient_role_쿼리는_묶지_않은_목록과_묶음_목록_모두_그_역할만_남긴다() throws Exception {
        StaffNotificationFixtures fx = fixtures();
        long academyId = fx.academy();
        long staffAccountId = fx.staffAccount(academyId, "관계자");
        OffsetDateTime t = now();
        fx.sentNotification(academyId, NotificationType.EMERGENCY, "관계자수신", Role.STAFF, "비상", "01호차", t, t);
        fx.sentNotification(academyId, NotificationType.EMERGENCY, "학부모수신", Role.PARENT, "비상", "01호차", t, t);
        String token = 토큰(staffAccountId, academyId);

        String ungrouped = 본문("/api/v1/staff/notifications?recipient_role=staff", token);
        assertThat(JsonPath.<List<String>>read(ungrouped, "$.data.items[*].recipient_name")).containsExactly("관계자수신");
        String grouped = 본문("/api/v1/staff/notifications?recipient_role=staff&group=true", token);
        assertThat((int) JsonPath.read(grouped, "$.data.total_count")).isEqualTo(1);
        assertThat(JsonPath.<List<Integer>>read(grouped, "$.data.items[*].recipient_count")).containsExactly(1);

        mockMvc.perform(get("/api/v1/staff/notifications").param("recipient_role", "unknown")
                .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/staff/notifications").param("group", "abc").header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** acked 필터 — 묶음 안에 그 상태 행이 하나라도 있으면 그 묶음을 싣는다. */
    @Test
    void acked_필터는_묶음_안에_그_상태_행이_하나라도_있으면_그_묶음을_싣는다() throws Exception {
        StaffNotificationFixtures fx = fixtures();
        long academyId = fx.academy();
        long staffAccountId = fx.staffAccount(academyId, "관계자");
        OffsetDateTime t = now();
        fx.ackedNotification(academyId, NotificationType.DELAY, "확인함", Role.PARENT, "지연", "01호차", t, t, t);
        fx.sentNotification(academyId, NotificationType.DELAY, "미확인", Role.PARENT, "지연", "01호차", t, t); // 같은 묶음 — 혼합
        fx.sentNotification(academyId, NotificationType.DELAY, "전원미확인1", Role.PARENT, "다른 지연", "01호차", t, t);
        fx.sentNotification(academyId, NotificationType.DELAY, "전원미확인2", Role.PARENT, "다른 지연", "01호차", t, t);
        String token = 토큰(staffAccountId, academyId);

        String ackedOnly = 본문("/api/v1/staff/notifications?group=true&acked=true", token);
        assertThat(JsonPath.<List<String>>read(ackedOnly, "$.data.items[*].body")).containsExactly("지연");
        assertThat(JsonPath.<List<Integer>>read(ackedOnly, "$.data.items[*].acked_count")).containsExactly(1);
        assertThat((int) JsonPath.read(ackedOnly, "$.data.total_count")).isEqualTo(1);

        String unackedOnly = 본문("/api/v1/staff/notifications?group=true&acked=false", token);
        assertThat(JsonPath.<List<String>>read(unackedOnly, "$.data.items[*].body"))
                .containsExactlyInAnyOrder("지연", "다른 지연");
        assertThat((int) JsonPath.read(unackedOnly, "$.data.total_count")).isEqualTo(2);
    }

    private Map<String, Object> 묶음(String body, int recipientCount) {
        List<Map<String, Object>> found = JsonPath.read(body, "$.data.items[?(@.recipient_count==" + recipientCount + ")]");
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private String 본문(String url, String token) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", token)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String 토큰(long accountId, long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.STAFF, AccountStatus.ACTIVE);
    }
}
