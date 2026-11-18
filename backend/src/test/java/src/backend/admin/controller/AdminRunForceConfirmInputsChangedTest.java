package src.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.메인관리자_토큰;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.run.command.RunRosterFixtures;
import src.backend.run.entity.Run;
import src.backend.run.roster.ProjectedRosterReader;
import testsupport.clock.FixedClock20300401Config;

/**
 * 강제 확정(API_SPEC §6.14)이 확정 계산 뒤 명단 입력이 바뀐 것을 만나면 {@code 409 RUN_CONFIRM_INPUTS_CHANGED} 로 답하고 아무것도 쓰지 않는다 —
 * 회차는 {@code idle} 로 남고 감사 행도 없다. 명단을 처음 읽은 직후 학부모의 ①구간 탑승 끄기가 커밋되는 경쟁을 그대로 재현한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class AdminRunForceConfirmInputsChangedTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @Autowired private BoardingIntentRepository boardingIntentRepository;
    @MockitoSpyBean private ProjectedRosterReader rosterReader;

    @Test
    @DisplayName("강제 확정 저장이 바뀐 명단 입력을 만나면 409 RUN_CONFIRM_INPUTS_CHANGED 이고 회차는 idle · 감사 행은 없다")
    void 명단이_바뀌면_409_이고_아무것도_쓰지_않는다() throws Exception {
        RunRosterFixtures fx = RunRosterFixtures.of(applicationContext);
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.560000", "126.970000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.560000", "126.970000");
        OffsetDateTime depart = OffsetDateTime.now(clock).plusMinutes(29);
        long runId = fx.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, depart, depart.minusMinutes(30));

        AtomicBoolean toggled = new AtomicBoolean();
        doAnswer(invocation -> {
            Object roster = invocation.callRealMethod();
            if (toggled.compareAndSet(false, true)) { // 첫 읽기(확정 계산 입력) 직후 학부모의 탑승 끄기가 커밋된다
                OffsetDateTime now = OffsetDateTime.now(clock);
                BoardingIntent intent = BoardingIntent.forRun(academyId, runId, studentId, now);
                intent.applyRiding(false, ChangeWindow.IMMEDIATE, now, null);
                boardingIntentRepository.saveAndFlush(intent);
            }
            return roster;
        }).when(rosterReader).read(any(Run.class), any(Weekday.class), anyList());

        mockMvc.perform(post("/api/v1/admin/runs/%d/force-confirm".formatted(runId))
                        .header("Authorization", 메인관리자_토큰(tokenProvider, 1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"기사 무응답으로 콘솔 강제 확정\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_CONFIRM_INPUTS_CHANGED"))
                .andExpect(jsonPath("$.error.message").value("확정하는 동안 명단이 바뀌었습니다. 다시 시도해주세요"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("idle");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE target_type = 'run' AND target_id = ?", Long.class, runId))
                .isZero();
    }
}
