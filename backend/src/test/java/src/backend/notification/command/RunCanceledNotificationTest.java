package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.run.controller.DriverRunFixtures;

/**
 * Ruling 894 ⑤ — 확정된({@code confirmed}) 회차를 관계자가 취소하면 배치된 기사·동승자에게
 * {@code run_canceled} 알림이 간다. 확정 전({@code idle}) 취소는 매니저가 아직 회차를 모르므로 보내지 않는다.
 *
 * <p>적재는 취소와 같은 트랜잭션 안이라 {@code @Transactional} 시험에서 같은 연결로 행이 보인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunCanceledNotificationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;



    @Test
    @DisplayName("confirmed 회차를 취소하면 배치된 기사·동승자에게 run_canceled 가 각 1건 적재된다")
    void 확정_회차_취소는_기사와_동승자에게_통지한다() throws Exception {
        DriverRunFixtures fx = DriverRunFixtures.of(applicationContext);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(2);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", OffsetDateTime.now());
        long escortAccountId = fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자1", OffsetDateTime.now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(delete("/api/v1/staff/runs/" + runId).header("Authorization", 토큰(tokenProvider, staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isNoContent());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT recipient_account_id, recipient_role, run_id FROM notification_log "
                        + "WHERE type = 'run_canceled' AND run_id = ? ORDER BY recipient_role",
                runId);
        assertThat(rows).as("기사·동승자 각 1건").hasSize(2);
        assertThat(rows).extracting(row -> row.get("recipient_account_id"))
                .containsExactlyInAnyOrder(driverAccountId, escortAccountId);
        assertThat(rows).extracting(row -> row.get("recipient_role")).containsExactly("driver", "escort");
    }

    @Test
    @DisplayName("idle(확정 전) 회차를 취소하면 알림이 한 건도 적재되지 않는다")
    void 확정_전_회차_취소는_통지하지_않는다() throws Exception {
        DriverRunFixtures fx = DriverRunFixtures.of(applicationContext);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(2);
        long runId = fx.idleRun(academyId, busId, Direction.TO_ACADEMY, departTime);
        fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", OffsetDateTime.now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(delete("/api/v1/staff/runs/" + runId).header("Authorization", 토큰(tokenProvider, staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = 'run_canceled' AND run_id = ?", Integer.class,
                runId)).isZero();
    }

    @Test
    @DisplayName("이미 취소된 확정 회차를 다시 취소해도(멱등) 알림이 늘지 않는다")
    void 두_번_취소해도_통지는_한_번이다() throws Exception {
        DriverRunFixtures fx = DriverRunFixtures.of(applicationContext);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(2);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", OffsetDateTime.now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete("/api/v1/staff/runs/" + runId)
                            .header("Authorization", 토큰(tokenProvider, staffAccountId, academyId, Role.STAFF)))
                    .andExpect(status().isNoContent());
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = 'run_canceled' AND run_id = ?", Integer.class,
                runId)).isEqualTo(1);
    }
}
