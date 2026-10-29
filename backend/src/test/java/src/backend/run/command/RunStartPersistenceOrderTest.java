package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.request.command.ChangeRequestAutoRejectionService;
import src.backend.run.controller.DriverRunFixtures;
import testsupport.clock.FixedClock20300401Config;

/**
 * 운행 시작(§4.4)은 미결 변경 요청을 자동 거절한 <b>뒤</b>에 회차를 {@code moving} 으로 쓴다 — 자동 거절의 갱신 쿼리가 영속성 컨텍스트를 비우므로, 회차를 먼저 바꿔 두면 그 뒤에 회차에 더하는
 * 변경은 분리된 엔티티에 얹혀 조용히 사라진다. 미결 요청이 있어 자동 거절이 실제로 컨텍스트를 비우는 경우에, 자동 거절이 도는 시점에 회차는 아직 {@code confirmed} 이고 시작 뒤에는 {@code moving} 과
 * 시작 시각이 커밋돼 있어야 한다(자동 거절 뒤에 회차에 쓰는 변경은 이 시작 전이 하나뿐이다).
 */
@SpringBootTest
@Import(FixedClock20300401Config.class)
class RunStartPersistenceOrderTest {

    @Autowired private ApplicationContext applicationContext;
    @Autowired private RunStartCommandService startService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;
    @MockitoSpyBean private ChangeRequestAutoRejectionService autoRejectionService;

    @AfterEach
    void 뒷정리한다() {
        String academies = "(SELECT id FROM academy WHERE name = '" + DriverRunFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + DriverRunFixtures.ACADEMY_NAME + "'");
    }

    @Test
    @DisplayName("자동 거절이 도는 시점에 회차는 아직 confirmed 이고, 시작이 끝나면 moving 과 시작 시각이 커밋돼 있다")
    void 자동_거절이_끝난_뒤에_회차를_시작_상태로_쓴다() {
        DriverRunFixtures fx = DriverRunFixtures.of(applicationContext);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now, now.minusMinutes(30));
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now);
        // 미결 변경 요청이 있어야 자동 거절의 갱신 쿼리가 돌아 영속성 컨텍스트를 실제로 비운다
        fx.pendingChangeRequest(academyId, runId, fx.student(academyId, "학생1"), driverAccountId, now, now.plusMinutes(10));
        AtomicReference<String> statusDuringAutoReject = new AtomicReference<>();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            entityManager.flush(); // 지금까지 회차에 쌓인 변경을 DB 에 내보낸 뒤의 상태를 본다
            statusDuringAutoReject.set(
                    jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId));
            return result;
        }).when(autoRejectionService).terminateForRun(anyLong(), anyLong(), any());

        startService.start(new AuthUser(driverAccountId, academyId, Role.DRIVER, AccountStatus.ACTIVE), runId);

        assertThat(statusDuringAutoReject.get()).as("자동 거절 전에 moving 을 써 두면 컨텍스트가 비워지며 그 뒤 변경이 분리된 엔티티에 얹힌다")
                .isEqualTo("confirmed");
        assertThat(jdbcTemplate.queryForMap("SELECT status, started_at FROM run WHERE id = ?", runId))
                .containsEntry("status", "moving")
                .doesNotContainEntry("started_at", null);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM change_request WHERE run_id = ?", String.class, runId))
                .as("컨텍스트를 비우는 자동 거절이 실제로 돌았다").isNotEqualTo("pending");
    }
}
