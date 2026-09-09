package src.backend.schedule.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 일일 회차 생성 배치(SCH-02, API_SPEC §5.10) — <b>멱등</b>과 <b>비활성 제외</b>가 판정 대상이다.
 *
 * <p>배치가 여러 트랜잭션을 실제로 커밋하므로 {@code @Transactional} 을 붙이지 않는다 — 붙이면
 * 두 번째 실행이 첫 번째의 커밋을 보지 못해 "늘지 않는다" 가 검사되지 않는다. 대신 만든 행을
 * {@link #뒷정리한다()} 가 직접 지운다({@code BusRegistrationConcurrencyTest} 와 같은 형태).
 *
 * <p><b>시계를 고정한다</b>(횡단 규칙 1) — 확정 시각 단언이 "출발 30분 전" 을 재는 것이지 실행 시각을
 * 재는 것이 아니기 때문이다. 고정하지 않으면 배치가 언제 돌든 통과하는 단언이 되고, 그것은 두 시계를
 * 섞은 구현(확정 시각을 실행 시각 기준으로 잡는 구현)과 구별되지 않는다.
 */
@SpringBootTest
class RunGenerationServiceTest {

    /** 시드 학원 A. */
    private static final long ACADEMY_A_ID = 1L;

    /** 시드 학원 A 의 1호차 — 이 테스트가 만드는 스케줄이 전부 이 차량을 쓴다. */
    private static final long BUS_A_ID = 1L;

    /** 날짜 후보의 시작점 — 여기서부터 하루씩 밀어 시드가 쓰지 않는 요일을 찾는다. */
    private static final LocalDate FIRST_CANDIDATE_DATE = LocalDate.of(2030, 3, 4);

    /** 이 테스트가 만드는 스케줄만 가려내는 표시 — 뒷정리가 이 값으로 자기 행만 지운다. */
    private static final String MARKER = "P5T5생성배치";

    @Autowired
    private RunGenerationService runGenerationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    /**
     * 판정 대상 날짜 — <b>시드 스케줄이 쓰지 않는 요일</b>의 먼 미래 날짜다.
     *
     * <p>고정 날짜를 쓸 수 없다. 시드 스케줄의 요일이 {@code extract(dow from now())} 로 <b>DB 를
     * 만든 날</b>에 정해지므로(V2), 고정하면 그 요일과 맞아떨어지는 날에만 시드 스케줄까지 함께
     * 대상이 되어 "이 배치가 몇 건 만들었나" 가 실행 날짜에 따라 갈린다. 실제로 그 형태로 깨졌다.
     *
     * <p>먼 미래인 것은 시드 회차({@code CURRENT_DATE})와 겹치지 않게 하기 위함이다.
     */
    private LocalDate serviceDate;

    /**
     * 시각을 고정한다 — {@code Clock} 을 주입받는 구현과 시스템 시계를 직접 부르는 구현이 이 설정
     * 아래에서 <b>다른 값</b>을 내놓는다.
     *
     * <p>{@code ClockConfig} 의 빈을 덮어쓰지 않고 {@link Primary} 로 하나 더 둔다 — 덮으려면 빈 정의
     * 덮어쓰기를 열어야 하고, 그것을 열면 이 클래스 밖의 사고까지 조용히 통과한다.
     */
    @TestConfiguration
    static class FixedClockConfig {

        /** 값 자체에 의미는 없다 — 고정돼 있다는 사실만이 검사 대상이다. */
        private static final Instant FIXED = Instant.parse("2026-08-26T01:00:00Z");

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    /**
     * 앞선 실행이 남긴 행을 지우고, <b>그러고 나서</b> 판정 날짜를 고른다.
     *
     * <p>순서가 중요하다 — 지우기 전에 고르면 이 테스트가 지난번에 남긴 스케줄의 요일까지 "시드가
     * 쓰는 요일" 로 세어, 후보가 매 실행 하루씩 밀린다.
     */
    @BeforeEach
    void 판정_날짜를_고른다() {
        뒷정리한다();
        Set<String> 이미_쓰는_요일 = new HashSet<>(
                jdbcTemplate.queryForList("SELECT DISTINCT weekday FROM schedule", String.class));
        LocalDate 후보 = FIRST_CANDIDATE_DATE;
        while (이미_쓰는_요일.contains(요일명(후보))) {
            후보 = 후보.plusDays(1);
        }
        serviceDate = 후보;
    }

    /** {@code schedule.weekday} 의 값 공간({@code mon}~{@code sun}) 표기. */
    private static String 요일명(LocalDate date) {
        return date.getDayOfWeek().name().substring(0, 3).toLowerCase(Locale.ROOT);
    }

    /** 이 클래스는 실제 커밋을 남기므로 지우는 것도 직접 한다 — 자기 표시가 붙은 행만 지운다. */
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM run WHERE origin_name LIKE ?", MARKER + "%");
        jdbcTemplate.update("DELETE FROM schedule WHERE origin_name LIKE ?", MARKER + "%");
    }

    /**
     * 배치를 두 번 돌려도 회차가 늘지 않는다(목표 7).
     *
     * <p>두 번째 실행이 <b>0 건 생성</b>을 보고하는 것까지 함께 본다 — 행 수만 보면 "만들고 지우는"
     * 구현과 구별되지 않고, 생성 건수가 곧 운영에서 배치가 무엇을 했는지 읽는 유일한 값이다.
     */
    @Test
    void 회차_생성_배치를_두_번_돌려도_회차가_늘지_않는다() {
        스케줄을_넣는다("08:00", true);
        스케줄을_넣는다("08:30", true);

        int 첫_실행 = runGenerationService.generate(serviceDate);
        int 회차_수 = 회차_수();
        int 두번째_실행 = runGenerationService.generate(serviceDate);

        assertThat(첫_실행).as("활성 스케줄 2개에서 회차 2개가 나와야 한다").isEqualTo(2);
        assertThat(회차_수).isEqualTo(2);
        assertThat(두번째_실행).as("이미 있는 회차는 조용히 건너뛰고 0 건을 보고한다 — 중복 실행은 오류가 아니다")
                .isZero();
        assertThat(회차_수()).as("두 번째 실행이 행을 늘리면 확정 배치가 같은 운행을 두 번 잡는다").isEqualTo(2);
    }

    /**
     * {@code active=false} 스케줄에서는 회차가 나오지 않는다(목표 7).
     *
     * <p>같은 실행에 <b>활성 스케줄이 함께 있는 것</b>이 요점이다 — 비활성만 두면 "아무것도 안 만드는
     * 구현" 도 통과한다.
     */
    @Test
    void 비활성_스케줄에서는_회차가_생성되지_않는다() {
        long 활성 = 스케줄을_넣는다("09:00", true);
        long 비활성 = 스케줄을_넣는다("09:30", false);

        int 생성 = runGenerationService.generate(serviceDate);

        assertThat(생성).isEqualTo(1);
        assertThat(회차_수(활성)).as("활성 스케줄에서는 회차가 나와야 한다").isEqualTo(1);
        assertThat(회차_수(비활성))
                .as("쉬는 스케줄에서 회차가 나오면 운행하지 않는 회차가 확정 배치(Phase 7)의 대상이 된다")
                .isZero();
    }

    /**
     * 생성된 회차의 {@code confirm_at} 은 출발 30분 전이다(C-03 · {@code ck_run_confirm_at}).
     *
     * <p>출발 시각 자체도 함께 대조한다 — {@code confirm_at} 만 보면 두 값을 <b>함께</b> 실행 시각
     * 기준으로 잡은 구현(두 시계를 섞은 구현)이 통과한다. 기준 시간대는 주입된 {@code Clock} 의
     * zone 이다(Ruling 165 ①).
     */
    @Test
    void 생성된_회차의_confirm_at_은_출발_30분_전이다() {
        long scheduleId = 스케줄을_넣는다("10:15", true);

        runGenerationService.generate(serviceDate);

        OffsetDateTime 출발 = 회차_시각(scheduleId, "depart_time");
        OffsetDateTime 확정 = 회차_시각(scheduleId, "confirm_at");
        assertThat(출발)
                .as("날짜 없는 시각을 service_date 와 합칠 때 시간대는 주입된 Clock 의 zone 이다")
                .isEqualTo(serviceDate.atTime(10, 15).atZone(clock.getZone()).toOffsetDateTime());
        assertThat(확정).as("확정 시각은 파생이 아니라 저장된 컬럼이고 값은 출발 30분 전이다")
                .isEqualTo(출발.minusMinutes(30));
    }

    /** 생성된 회차는 자기를 만든 스케줄을 가리킨다 — 임시 추가 회차와 가르는 유일한 표시다(§5.10). */
    @Test
    void 배치가_만든_회차는_schedule_id_가_채워진다() {
        long scheduleId = 스케줄을_넣는다("11:00", true);

        runGenerationService.generate(serviceDate);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT schedule_id FROM run WHERE schedule_id = ?", Long.class, scheduleId))
                .isEqualTo(scheduleId);
    }

    /** 다른 요일의 활성 스케줄은 그날 회차를 만들지 않는다 — 요일 조건이 빠지면 하루에 일주일치가 생긴다. */
    @Test
    void 다른_요일의_스케줄에서는_회차가_생성되지_않는다() {
        long 그날 = 스케줄을_넣는다("12:00", true);
        long 다른날 = 스케줄을_넣는다("12:30", true, 요일명(serviceDate.plusDays(1)));

        runGenerationService.generate(serviceDate);

        assertThat(회차_수(그날)).isEqualTo(1);
        assertThat(회차_수(다른날)).as("판정 날짜의 다음 날 요일 스케줄은 대상 밖이다").isZero();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private long 스케줄을_넣는다(String departTime, boolean active) {
        return 스케줄을_넣는다(departTime, active, 요일명(serviceDate));
    }

    private long 스케줄을_넣는다(String departTime, boolean active, String weekday) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO schedule (academy_id, bus_id, weekday, direction, depart_time, origin_name,
                                      destination_name, active)
                VALUES (?, ?, ?, 'to_academy', CAST(? AS time), ?, '바래다학원 A', ?)
                RETURNING id""",
                Long.class, ACADEMY_A_ID, BUS_A_ID, weekday, departTime, MARKER + departTime, active);
    }

    private int 회차_수() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run WHERE origin_name LIKE ?", Integer.class,
                MARKER + "%");
    }

    private int 회차_수(long scheduleId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run WHERE schedule_id = ?", Integer.class,
                scheduleId);
    }

    private OffsetDateTime 회차_시각(long scheduleId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM run WHERE schedule_id = ?",
                OffsetDateTime.class, scheduleId);
    }
}
