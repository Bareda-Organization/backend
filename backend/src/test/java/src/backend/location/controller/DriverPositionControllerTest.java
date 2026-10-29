package src.backend.location.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.관계자_토큰;
import static testsupport.auth.TestTokens.토큰;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.run.controller.DriverRunFixtures;

/**
 * {@code POST /runs/{runId}/position} 의 거절 코드를 HTTP 로 고정한다(API_SPEC §4.12) — 토큰 없음 401 · 기사가 아님
 * 403 DRIVER_ONLY · 이 회차에 배치되지 않음 403 FORBIDDEN · 학원이 다른 토큰 404 RUN_NOT_FOUND · 운행 중이 아님 409
 * RUN_NOT_MOVING · 범위 밖 좌표 422 VALIDATION_FAILED. 거절된 요청은 이력 행을 남기지 않는다.
 *
 * <p>실제 커밋을 남기므로 {@code @Transactional} 을 쓰지 않고 만든 행을 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriverPositionControllerTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> academyIds = new ArrayList<>();

    @AfterEach
    void 뒷정리한다() {
        if (academyIds.isEmpty()) {
            return;
        }
        Object[] ids = { academyIds.toArray(new Long[0]) };
        jdbcTemplate.update("DELETE FROM run_position WHERE run_id IN (SELECT id FROM run WHERE academy_id = ANY(?))", ids);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id = ANY(?)", ids);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id = ANY(?)", ids);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id = ANY(?)", ids);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ANY(?)", ids);
        jdbcTemplate.update("DELETE FROM academy WHERE id = ANY(?)", ids);
        academyIds.clear();
    }

    @Test
    void 토큰이_없으면_401_이다() throws Exception {
        Run run = 운행_중_회차();

        mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/position").contentType(MediaType.APPLICATION_JSON)
                .content(본문("37.5", "127.0")))
                .andExpect(status().isUnauthorized());

        assertThat(행_수(run)).isZero();
    }

    @Test
    void 기사가_아닌_역할은_403_DRIVER_ONLY_이다() throws Exception {
        Run run = 운행_중_회차();

        mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/position")
                .header("Authorization", 관계자_토큰(tokenProvider, run.academyId()))
                .contentType(MediaType.APPLICATION_JSON).content(본문("37.5", "127.0")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DRIVER_ONLY"));

        assertThat(행_수(run)).isZero();
    }

    @Test
    void 이_회차에_배치되지_않은_기사는_403_FORBIDDEN_이다() throws Exception {
        Run run = 운행_중_회차();
        long otherDriver = DriverRunFixtures.of(applicationContext).unassignedManager(run.academyId(),
                ManagerRole.DRIVER, "미배치기사");

        mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/position")
                .header("Authorization", 토큰(tokenProvider, otherDriver, run.academyId(), Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON).content(본문("37.5", "127.0")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(행_수(run)).isZero();
    }

    @Test
    void 배치된_기사라도_토큰의_학원이_회차의_학원과_다르면_404_RUN_NOT_FOUND_이다() throws Exception {
        Run run = 운행_중_회차();
        long otherAcademy = DriverRunFixtures.of(applicationContext).academy();
        academyIds.add(otherAcademy);

        mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/position")
                .header("Authorization", 토큰(tokenProvider, run.driverAccountId(), otherAcademy, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON).content(본문("37.5", "127.0")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));

        assertThat(행_수(run)).isZero();
    }

    @Test
    void 운행_중이_아닌_회차는_409_RUN_NOT_MOVING_이다() throws Exception {
        DriverRunFixtures fixtures = DriverRunFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long runId = fixtures.confirmedRun(academyId, fixtures.bus(academyId), Direction.TO_ACADEMY,
                OffsetDateTime.now().plusMinutes(9), OffsetDateTime.now().minusMinutes(30));
        long driver = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", OffsetDateTime.now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                .header("Authorization", 토큰(tokenProvider, driver, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON).content(본문("37.5", "127.0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_MOVING"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM run_position WHERE run_id = ?", Integer.class,
                runId)).isZero();
    }

    @Test
    void 위도_경도가_범위_밖이면_422_VALIDATION_FAILED_이다() throws Exception {
        Run run = 운행_중_회차();

        for (String[] outOfRange : new String[][] { { "90.1", "127.0" }, { "-90.1", "127.0" }, { "37.5", "180.1" },
                { "37.5", "-180.1" } }) {
            mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/position")
                    .header("Authorization", 토큰(tokenProvider, run.driverAccountId(), run.academyId(), Role.DRIVER))
                    .contentType(MediaType.APPLICATION_JSON).content(본문(outOfRange[0], outOfRange[1])))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        assertThat(행_수(run)).isZero();
    }

    private String 본문(String lat, String lng) {
        return "{\"lat\": %s, \"lng\": %s, \"recorded_at\": \"%s\"}".formatted(lat, lng, OffsetDateTime.now());
    }

    private int 행_수(Run run) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run_position WHERE run_id = ?", Integer.class,
                run.runId());
    }

    private record Run(long academyId, long runId, long driverAccountId) {
    }

    private Run 운행_중_회차() {
        DriverRunFixtures fixtures = DriverRunFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        return new Run(academyId, runId, driverAccountId);
    }
}
