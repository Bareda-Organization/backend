package src.backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.notification.entity.NotificationType;
import testsupport.clock.FixedClock20300401Config;
import testsupport.db.ConnectionCountingConfig;

/**
 * 묶어 보기({@code group=true})가 묶음 목록과 전체 묶음 수를 한 문장으로 가져온다 — 같은 집계를 목록용과 개수용으로 두 번 돌리면 알림이 많은
 * 학원일수록 요청 하나가 같은 GROUP BY 를 두 번 치른다. 결과(묶음 · 수신자 수 · 전체 묶음 수)는 그대로여야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({FixedClock20300401Config.class, ConnectionCountingConfig.class})
class StaffNotificationGroupStatementTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @Test
    void 묶어_보기는_집계를_한_문장으로_읽고_전체_묶음_수는_쪽과_무관하다() throws Exception {
        StaffNotificationFixtures fx = StaffNotificationFixtures.of(applicationContext);
        long academyId = fx.academy();
        long staffAccountId = fx.staffAccount(academyId, "관계자");
        OffsetDateTime t = OffsetDateTime.now(clock);
        for (int i = 1; i <= 3; i++) {
            fx.sentNotification(academyId, NotificationType.RUN_STARTED, "수신" + i, Role.PARENT, "출발했습니다", "01호차",
                    t.plusNanos(i * 1_000_000L), t.plusSeconds(5));
        }
        fx.sentNotification(academyId, NotificationType.RUN_STARTED, "늦은수신", Role.PARENT, "출발했습니다", "01호차",
                t.plusSeconds(2), t.plusSeconds(7));
        fx.sentNotification(academyId, NotificationType.BOARDING, "다른종류", Role.STAFF, "탑승", "01호차", t,
                t.plusSeconds(3));
        String token = 토큰(tokenProvider, staffAccountId, academyId, Role.STAFF);
        ConnectionCountingConfig.reset();

        String firstPage = 본문("/api/v1/staff/notifications?group=true&page=0&size=1", token);

        assertThat((int) JsonPath.read(firstPage, "$.data.total_count")).as("묶음 3개 — 행 수가 아니다").isEqualTo(3);
        assertThat((int) JsonPath.read(firstPage, "$.data.items.length()")).isEqualTo(1);
        assertThat(ConnectionCountingConfig.preparedStatements())
                .filteredOn(sql -> sql.contains("from notification_log") && sql.contains("group by"))
                .as("GROUP BY 집계 문장").hasSize(1);
        String lastPage = 본문("/api/v1/staff/notifications?group=true&page=2&size=1", token);
        assertThat((int) JsonPath.read(lastPage, "$.data.total_count")).isEqualTo(3);
        assertThat(JsonPath.<List<Integer>>read(lastPage, "$.data.items[*].recipient_count")).hasSize(1);
    }

    @Test
    void 쪽_범위를_벗어나도_전체_묶음_수는_맞다() throws Exception {
        StaffNotificationFixtures fx = StaffNotificationFixtures.of(applicationContext);
        long academyId = fx.academy();
        long staffAccountId = fx.staffAccount(academyId, "관계자");
        OffsetDateTime t = OffsetDateTime.now(clock);
        fx.sentNotification(academyId, NotificationType.RUN_STARTED, "수신", Role.PARENT, "출발했습니다", "01호차", t,
                t.plusSeconds(5));
        fx.sentNotification(academyId, NotificationType.BOARDING, "다른종류", Role.STAFF, "탑승", "01호차", t,
                t.plusSeconds(3));
        String token = 토큰(tokenProvider, staffAccountId, academyId, Role.STAFF);

        String beyond = 본문("/api/v1/staff/notifications?group=true&page=9&size=1", token);

        assertThat((int) JsonPath.read(beyond, "$.data.items.length()")).isZero();
        assertThat((int) JsonPath.read(beyond, "$.data.total_count")).as("빈 쪽에서도 전체 묶음 수는 2").isEqualTo(2);
    }

    private String 본문(String url, String token) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", token)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
