package src.backend.schedule.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import testsupport.clock.FixedClock20260826T01Config;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.command.RunCommandService;
import src.backend.schedule.dto.ScheduleRegisterRequest;
import src.backend.global.request.Patch;
import src.backend.schedule.dto.ScheduleUpdateRequest;

/**
 * 스케줄 등록·수정·삭제가 <b>내일의 아직 시작 전 회차</b>에 반영되는가(Ruling 366 ②, API_SPEC §5.10) — 회차를 하루
 * 앞서 만들면 "오늘 고친 스케줄이 내일에 안 먹는" 결함이 생기므로 함께 있어야 한다. 오늘 회차는 지금처럼 건드리지 않는다.
 *
 * <p>실제로 커밋을 남기는 시험이라 {@code @Transactional} 을 붙이지 않고 자기 표시가 붙은 행을 직접 지운다
 * ({@code RunGenerationServiceTest} 와 같은 형태). 시계는 2026-08-26(수) 10:00 KST 로 고정 — 내일은 목요일이다.
 */
@SpringBootTest
@Import(FixedClock20260826T01Config.class)
class ScheduleRunSyncTest {

    private static final long ACADEMY_A_ID = 1L;

    private static final long BUS_A_ID = 1L;

    private static final String MARKER = "R33B반영";

    /** 고정 시계의 내일 — 2026-08-27(목). */
    private static final LocalDate TOMORROW = LocalDate.of(2026, 8, 27);

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    private final AuthUser admin = new AuthUser(1L, ACADEMY_A_ID, Role.STAFF, AccountStatus.ACTIVE);

    @Autowired
    private ScheduleCommandService scheduleCommandService;

    @Autowired
    private RunCommandService runCommandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @BeforeEach
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM run WHERE origin_name LIKE ?", MARKER + "%");
        jdbcTemplate.update("DELETE FROM schedule WHERE origin_name LIKE ?", MARKER + "%");
    }

    @Test
    void 활성_스케줄을_등록하면_내일_회차가_생긴다() {
        long scheduleId = 등록한다("thu", "03:07", null);

        assertThat(내일_회차_수(scheduleId)).as("등록 직후 내일 회차가 없으면 전날 변경 신청이 다음 00:05 까지 막힌다").isEqualTo(1);
    }

    @Test
    void 내일이_아닌_요일의_스케줄을_등록하면_회차가_생기지_않는다() {
        long scheduleId = 등록한다("fri", "03:07", null);

        assertThat(회차_수(scheduleId)).isZero();
    }

    @Test
    void 비활성으로_등록하면_회차가_생기지_않는다() {
        long scheduleId = 등록한다("thu", "03:07", false);

        assertThat(회차_수(scheduleId)).isZero();
    }

    @Test
    void 출발_시각을_고치면_내일_회차의_출발과_확정_시각이_옮겨진다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();

        scheduleCommandService.update(admin, scheduleId, 수정("thu", "03:47", null, null));

        assertThat(내일_회차_시각(scheduleId, "depart_time"))
                .isEqualTo(TOMORROW.atTime(3, 47).atZone(clock.getZone()).toOffsetDateTime());
        assertThat(내일_회차_시각(scheduleId, "confirm_at"))
                .as("확정 시각은 저장된 컬럼이라 출발과 함께 다시 계산돼야 한다(ck_run_confirm_at)")
                .isEqualTo(내일_회차_시각(scheduleId, "depart_time").minusMinutes(30));
    }

    @Test
    void 출발지를_고치면_내일_회차의_출발지가_옮겨진다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();

        scheduleCommandService.update(admin, scheduleId, 수정(null, null, MARKER + "새출발지", null));

        assertThat(jdbcTemplate.queryForObject("SELECT origin_name FROM run WHERE schedule_id = ?", String.class,
                scheduleId)).isEqualTo(MARKER + "새출발지");
    }

    @Test
    void 스케줄을_고쳐도_오늘_회차는_그대로다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();
        long 오늘회차 = 회차를_넣는다(scheduleId, TODAY, "03:07", "idle");

        scheduleCommandService.update(admin, scheduleId, 수정("thu", "03:47", MARKER + "다른곳", false));

        assertThat(회차_시각(오늘회차, "depart_time"))
                .as("오늘 회차는 확정 배치가 이미 걸려 있을 수 있어 지금처럼 건드리지 않는다")
                .isEqualTo(TODAY.atTime(3, 7).atZone(clock.getZone()).toOffsetDateTime());
        assertThat(취소됨(오늘회차)).isFalse();
    }

    @Test
    void 이미_확정된_내일_회차는_스케줄을_고쳐도_그대로다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();
        jdbcTemplate.update("UPDATE run SET status = 'confirmed', confirmed_at = now() WHERE schedule_id = ?",
                scheduleId);

        scheduleCommandService.update(admin, scheduleId, 수정("thu", "03:47", null, null));

        assertThat(내일_회차_시각(scheduleId, "depart_time"))
                .isEqualTo(TOMORROW.atTime(3, 7).atZone(clock.getZone()).toOffsetDateTime());
    }

    @Test
    void 비활성으로_고치면_내일_회차가_취소_표시된다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();

        scheduleCommandService.update(admin, scheduleId, 수정(null, null, null, false));

        assertThat(내일_회차_취소됨(scheduleId)).as("행을 지우지 않고 canceled_at 을 채운다 — 이미 붙은 탑승 의사가 있을 수 있다").isTrue();
        assertThat(회차_수(scheduleId)).isEqualTo(1);
    }

    @Test
    void 요일을_바꾸면_옛_요일의_내일_회차가_취소된다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();

        scheduleCommandService.update(admin, scheduleId, 수정("fri", null, null, null));

        assertThat(내일_회차_취소됨(scheduleId)).isTrue();
    }

    @Test
    void 방향을_바꾸면_옛_방향의_내일_회차가_취소되고_새_방향의_회차가_생긴다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();

        scheduleCommandService.update(admin, scheduleId, new ScheduleUpdateRequest(null, null, Patch.of("from_academy"),
                null, null, null, null, null));

        List<Boolean> 취소여부 = jdbcTemplate.queryForList(
                "SELECT canceled_at IS NOT NULL FROM run WHERE schedule_id = ? ORDER BY direction", Boolean.class,
                scheduleId);
        assertThat(취소여부).as("from_academy(새 회차·취소 아님) · to_academy(옛 회차·취소됨)").containsExactly(false, true);
    }

    @Test
    void 요일을_내일로_옮기면_내일_회차가_생긴다() {
        long scheduleId = 스케줄을_넣는다("fri", "03:07", true);

        scheduleCommandService.update(admin, scheduleId, 수정("thu", null, null, null));

        assertThat(내일_회차_수(scheduleId)).isEqualTo(1);
    }

    @Test
    void 비활성_스케줄을_다시_켜면_내일_회차가_생긴다() {
        long scheduleId = 스케줄을_넣는다("thu", "03:07", false);

        scheduleCommandService.update(admin, scheduleId, 수정(null, null, null, true));

        assertThat(내일_회차_수(scheduleId)).isEqualTo(1);
    }

    @Test
    void 스케줄을_삭제하면_내일_회차가_취소_표시로_남는다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();
        long 회차 = jdbcTemplate.queryForObject("SELECT id FROM run WHERE schedule_id = ?", Long.class, scheduleId);

        scheduleCommandService.delete(admin, scheduleId);

        assertThat(취소됨(회차)).as("행을 지우면 안 된다 — schedule_id 만 비워지고 회차는 취소 표시로 남는다").isTrue();
    }

    @Test
    void 껐다_다시_켜면_스케줄이_취소한_내일_회차가_되살아난다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();

        scheduleCommandService.update(admin, scheduleId, 수정(null, null, null, false));
        assertThat(내일_회차_취소됨(scheduleId)).as("끄면 취소 표시(출처: 스케줄)").isTrue();
        scheduleCommandService.update(admin, scheduleId, 수정(null, null, null, true));

        assertThat(내일_회차_취소됨(scheduleId)).as("다시 켜면 스케줄이 취소한 회차는 취소가 풀린다").isFalse();
        assertThat(회차_수(scheduleId)).as("새 회차를 만들지 않고 그 회차를 되살린다").isEqualTo(1);
    }

    @Test
    void 관계자가_취소한_내일_회차는_스케줄을_껐다_켜도_취소로_남는다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();
        long 회차 = jdbcTemplate.queryForObject("SELECT id FROM run WHERE schedule_id = ?", Long.class, scheduleId);
        runCommandService.cancel(admin, 회차);

        scheduleCommandService.update(admin, scheduleId, 수정(null, null, null, false));
        scheduleCommandService.update(admin, scheduleId, 수정(null, null, null, true));

        assertThat(취소됨(회차)).as("관계자가 직접 취소한 회차를 스케줄 재활성이 되살리면 안 된다").isTrue();
        assertThat(회차_수(scheduleId)).isEqualTo(1);
    }

    @Test
    void 요일이_복귀하면_스케줄이_취소한_내일_회차가_되살아난다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();

        scheduleCommandService.update(admin, scheduleId, 수정("fri", null, null, null));
        scheduleCommandService.update(admin, scheduleId, 수정("thu", null, null, null));

        assertThat(내일_회차_취소됨(scheduleId)).isFalse();
        assertThat(회차_수(scheduleId)).isEqualTo(1);
    }

    @Test
    void 꺼진_동안_고친_출발_시각으로_되살아난다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();
        scheduleCommandService.update(admin, scheduleId, 수정(null, null, null, false));
        scheduleCommandService.update(admin, scheduleId, 수정(null, "03:47", null, null));

        scheduleCommandService.update(admin, scheduleId, 수정(null, null, null, true));

        assertThat(내일_회차_취소됨(scheduleId)).isFalse();
        assertThat(내일_회차_시각(scheduleId, "depart_time"))
                .as("되살린 회차에 계획을 다시 옮긴다")
                .isEqualTo(TOMORROW.atTime(3, 47).atZone(clock.getZone()).toOffsetDateTime());
        assertThat(내일_회차_시각(scheduleId, "confirm_at"))
                .isEqualTo(내일_회차_시각(scheduleId, "depart_time").minusMinutes(30));
    }

    @Test
    void 이미_확정된_회차는_취소_출처가_스케줄이어도_되살리지_않는다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();
        scheduleCommandService.update(admin, scheduleId, 수정(null, null, null, false));
        jdbcTemplate.update("UPDATE run SET status = 'confirmed', confirmed_at = now() WHERE schedule_id = ?",
                scheduleId);

        scheduleCommandService.update(admin, scheduleId, 수정(null, null, null, true));

        assertThat(내일_회차_취소됨(scheduleId)).as("확정된 회차는 스케줄 변경의 반영 대상이 아니다").isTrue();
    }

    @Test
    void 출발_시각을_임시_회차와_같은_시각으로_고치면_409_이고_스케줄과_회차가_그대로다() {
        long scheduleId = 시드로_스케줄과_내일_회차를_넣는다();
        회차를_넣는다(null, TOMORROW, "03:47", "idle");

        assertThatThrownBy(() -> scheduleCommandService.update(admin, scheduleId, 수정(null, "03:47", null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RUN));

        assertThat(jdbcTemplate.queryForObject("SELECT depart_time::text FROM schedule WHERE id = ?",
                String.class, scheduleId)).as("스케줄 변경 전체가 되돌려진다(부분 반영 금지)").isEqualTo("03:07:00");
        assertThat(내일_회차_시각(scheduleId, "depart_time"))
                .isEqualTo(TOMORROW.atTime(3, 7).atZone(clock.getZone()).toOffsetDateTime());
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    /** 등록 경로를 거치지 않고 직접 넣는다 — 수정·삭제 시험이 등록 시점 생성에 기대지 않게 한다. */
    private long 스케줄을_넣는다(String weekday, String departTime, boolean active) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO schedule (academy_id, bus_id, weekday, direction, depart_time, origin_name,
                                      destination_name, active)
                VALUES (?, ?, ?, 'to_academy', CAST(? AS time), ?, '바래다학원 A', ?)
                RETURNING id""", Long.class, ACADEMY_A_ID, BUS_A_ID, weekday, departTime, MARKER + departTime, active);
    }

    private long 시드로_스케줄과_내일_회차를_넣는다() {
        long scheduleId = 스케줄을_넣는다("thu", "03:07", true);
        회차를_넣는다(scheduleId, TOMORROW, "03:07", "idle");
        return scheduleId;
    }

    private long 등록한다(String weekday, String departTime, Boolean active) {
        return scheduleCommandService.register(admin, new ScheduleRegisterRequest(BUS_A_ID, weekday, "to_academy",
                departTime, MARKER + departTime, "바래다학원 A", null, active)).id();
    }

    private ScheduleUpdateRequest 수정(String weekday, String departTime, String originName, Boolean active) {
        return new ScheduleUpdateRequest(null, sent(weekday), null, sent(departTime), sent(originName), null, null,
                sent(active));
    }

    /** 이 시험에서 {@code null} 은 "키를 보내지 않음" 이다 — 보낸 값만 {@link Patch} 로 감싼다. */
    private static <T> Patch<T> sent(T value) {
        return value == null ? null : Patch.of(value);
    }

    private long 회차를_넣는다(Long scheduleId, LocalDate date, String departTime, String status) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO run (academy_id, bus_id, schedule_id, service_date, direction, depart_time, confirm_at,
                                 status, origin_name, destination_name)
                VALUES (?, ?, ?, ?, 'to_academy', ?::timestamptz, ?::timestamptz - interval '30 minutes', ?, ?, '바래다학원 A')
                RETURNING id""", Long.class, ACADEMY_A_ID, BUS_A_ID, scheduleId, date,
                date + "T" + departTime + ":00+09:00", date + "T" + departTime + ":00+09:00", status,
                MARKER + "회차");
    }

    private int 회차_수(long scheduleId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run WHERE schedule_id = ?", Integer.class, scheduleId);
    }

    private int 내일_회차_수(long scheduleId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run WHERE schedule_id = ? AND service_date = ?",
                Integer.class, scheduleId, TOMORROW);
    }

    private OffsetDateTime 내일_회차_시각(long scheduleId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM run WHERE schedule_id = ? AND service_date = ?", OffsetDateTime.class,
                scheduleId, TOMORROW);
    }

    private OffsetDateTime 회차_시각(long runId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM run WHERE id = ?", OffsetDateTime.class, runId);
    }

    private boolean 내일_회차_취소됨(long scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT bool_and(canceled_at IS NOT NULL) FROM run WHERE schedule_id = ? AND service_date = ?"
                        + " AND direction = 'to_academy'", Boolean.class, scheduleId, TOMORROW);
    }

    private boolean 취소됨(long runId) {
        return jdbcTemplate.queryForObject("SELECT canceled_at IS NOT NULL FROM run WHERE id = ?", Boolean.class,
                runId);
    }
}
