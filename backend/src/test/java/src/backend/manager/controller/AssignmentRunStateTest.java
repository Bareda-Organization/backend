package src.backend.manager.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.web.TestResponses.본문;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 회차 배치(§5.14)는 출발 전 회차에만 바꿀 수 있다 — 운행 중({@code moving})이거나 끝난({@code finished}) 회차의 기사·동승자 변경은
 * {@code 409 RUN_ALREADY_STARTED} 이고 아무것도 바뀌지 않는다. 삭제된 매니저를 배치하면 {@code 404 MANAGER_NOT_FOUND} 다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AssignmentRunStateTest {

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    private static final long BUS_A1_ID = 1L;

    /** 시드 학원 A 의 기사 둘 — 이미 배치된 사람과 바꿔 넣을 사람. */
    private static final long 강기사 = 1L;

    private static final long 오기사 = 2L;

    private static final String SERVICE_DATE = "2031-09-17";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void 운행_중_회차의_배치_변경은_409_RUN_ALREADY_STARTED_이고_배치는_그대로다() throws Exception {
        long runId = 배치된_회차를_만든다();
        상태를_바꾼다(runId, "moving");

        배치한다(runId, 오기사).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_ALREADY_STARTED"));

        assertThat(기사_배치(runId)).isEqualTo(강기사);
    }

    @Test
    void 끝난_회차의_배치_변경은_409_RUN_ALREADY_STARTED_이고_배치는_그대로다() throws Exception {
        long runId = 배치된_회차를_만든다();
        상태를_바꾼다(runId, "finished");

        배치한다(runId, 오기사).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_ALREADY_STARTED"));

        assertThat(기사_배치(runId)).isEqualTo(강기사);
    }

    @Test
    void 출발_전_회차의_배치_변경은_계속_된다() throws Exception {
        long runId = 배치된_회차를_만든다();
        for (String state : new String[] {"idle", "confirmed"}) {
            상태를_바꾼다(runId, state);

            배치한다(runId, state.equals("idle") ? 오기사 : 강기사).andExpect(status().isOk());
        }
    }

    @Test
    void 삭제된_매니저를_배치하면_404_MANAGER_NOT_FOUND_이고_배치되지_않는다() throws Exception {
        long runId = 회차를_만든다();
        jdbcTemplate.update("UPDATE manager SET deleted_at = now() WHERE id = ?", 강기사);
        entityManager.clear();

        배치한다(runId, 강기사).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MANAGER_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM assignment WHERE run_id = ?", Integer.class,
                runId)).isZero();
    }

    private long 배치된_회차를_만든다() throws Exception {
        long runId = 회차를_만든다();
        배치한다(runId, 강기사).andExpect(status().isOk());
        return runId;
    }

    private void 상태를_바꾼다(long runId, String state) {
        entityManager.flush();
        jdbcTemplate.update("UPDATE run SET status = ? WHERE id = ?", state, runId);
        entityManager.clear();
    }

    private long 회차를_만든다() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/staff/runs").header("Authorization", 토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bus_id":%d,"service_date":"%s","direction":"to_academy","depart_time":"08:00",
                                 "origin_name":"배치 상태 집결지","destination_name":"바래다학원"}"""
                                .formatted(BUS_A1_ID, SERVICE_DATE)))
                .andExpect(status().isCreated()).andReturn();
        return Long.parseLong(JsonPath.read(본문(result), "$.data.id"));
    }

    private ResultActions 배치한다(long runId, long driverId) throws Exception {
        return mockMvc.perform(patch("/api/v1/staff/runs/" + runId + "/assignment").header("Authorization", 토큰())
                .contentType(MediaType.APPLICATION_JSON).content("{\"driver_manager_id\":%d}".formatted(driverId)));
    }

    private Long 기사_배치(long runId) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT manager_id FROM assignment WHERE run_id = ? AND role = 'driver'",
                Long.class, runId);
    }

    private String 토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
