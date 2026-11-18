package src.backend.run.command;

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
import src.backend.run.entity.Run;
import src.backend.run.entity.RunForcedAddition;
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.run.roster.ProjectedRosterReader;
import testsupport.clock.FixedClock20300401Config;

/**
 * 강제 확정이 명단을 처음 읽은 직후 다른 곳에서 그 회차가 취소되거나 확정되고 명단도 바뀌면, 잠금 뒤 회차 행을 새로 읽어
 * {@code RUN_CANCELED} · {@code RUN_NOT_IDLE} 로 답한다 — 바깥 트랜잭션이 먼저 읽어 둔 낡은 회차로 판정하면 명단 불일치(409
 * {@code RUN_CONFIRM_INPUTS_CHANGED})가 앞서 원인이 가려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class RunForceConfirmStaleReadTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @Autowired private BoardingIntentRepository boardingIntentRepository;
    @Autowired private RunForcedAdditionRepository forcedAdditionRepository;
    @MockitoSpyBean private ProjectedRosterReader rosterReader;

    @Test
    void 명단을_읽은_뒤_취소된_회차는_명단이_바뀌어도_RUN_CANCELED_로_답한다() throws Exception {
        long[] ids = 확정_대상_회차를_만든다();

        읽은_직후에_바꾼다(ids, () -> jdbcTemplate.update(
                "UPDATE run SET canceled_at = now(), cancel_source = 'staff' WHERE id = ?", ids[0]));

        강제_확정한다(ids[0]).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_CANCELED"));
    }

    @Test
    void 명단을_읽은_뒤_다른_곳에서_확정된_회차는_명단이_바뀌어도_RUN_NOT_IDLE_로_답한다() throws Exception {
        long[] ids = 확정_대상_회차를_만든다();

        읽은_직후에_바꾼다(ids, () -> jdbcTemplate.update("UPDATE run SET status = 'confirmed' WHERE id = ?", ids[0]));

        강제_확정한다(ids[0]).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_IDLE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE target_type = 'run' AND target_id = ?",
                Long.class, ids[0])).isZero();
    }

    @Test
    void 명단을_읽은_뒤_강제_추가_행의_승하차지가_제자리에서_바뀌면_회차가_idle_이어도_RUN_CONFIRM_INPUTS_CHANGED_로_답한다()
            throws Exception {
        long[] ids = 확정_대상_회차를_만든다();
        Phase9RosterFixtures fx = Phase9RosterFixtures.of(applicationContext);
        long firstStop = fx.stop(ids[2], "37.570000", "126.980000");
        long secondStop = fx.stop(ids[2], "37.580000", "126.990000");
        long added = fx.student(ids[2], "추가학생");
        forcedAdditionRepository.saveAndFlush(RunForcedAddition.forRun(ids[2], ids[0], added, firstStop, 1L,
                OffsetDateTime.now(clock), null));

        // 강제 확정 트랜잭션이 이미 읽어 둔 강제 추가 행을 다른 트랜잭션이 제자리에서 고친다 — 회차는 idle 그대로다
        읽은_직후에(() -> jdbcTemplate.update("UPDATE run_forced_addition SET stop_id = ? WHERE run_id = ?", secondStop,
                ids[0]));

        강제_확정한다(ids[0]).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_CONFIRM_INPUTS_CHANGED"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, ids[0]))
                .isEqualTo("idle");
    }

    /** 회차 id · 학생 id · 학원 id 를 돌려준다. */
    private long[] 확정_대상_회차를_만든다() {
        Phase9RosterFixtures fx = Phase9RosterFixtures.of(applicationContext);
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.560000", "126.970000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.560000", "126.970000");
        OffsetDateTime depart = OffsetDateTime.now(clock).plusMinutes(29);
        long runId = fx.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, depart, depart.minusMinutes(30));
        return new long[] { runId, studentId, academyId };
    }

    /** 확정 계산 입력을 처음 읽은 직후 회차를 바꾸고 학부모의 ①구간 탑승 끄기도 커밋된 것으로 만든다. */
    private void 읽은_직후에_바꾼다(long[] ids, Runnable runChange) {
        읽은_직후에(() -> {
            runChange.run();
            OffsetDateTime now = OffsetDateTime.now(clock);
            BoardingIntent intent = BoardingIntent.forRun(ids[2], ids[0], ids[1], now);
            intent.applyRiding(false, ChangeWindow.IMMEDIATE, now, null);
            boardingIntentRepository.saveAndFlush(intent);
        });
    }

    /** 확정 계산 입력을 처음 읽은 직후 {@code change} 를 한 번 돌린다. */
    private void 읽은_직후에(Runnable change) {
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(invocation -> {
            Object roster = invocation.callRealMethod();
            if (changed.compareAndSet(false, true)) {
                change.run();
            }
            return roster;
        }).when(rosterReader).read(any(Run.class), any(Weekday.class), anyList());
    }

    private org.springframework.test.web.servlet.ResultActions 강제_확정한다(long runId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/runs/%d/force-confirm".formatted(runId))
                .header("Authorization", 메인관리자_토큰(tokenProvider, 1L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"기사 무응답으로 콘솔 강제 확정\"}"));
    }
}
