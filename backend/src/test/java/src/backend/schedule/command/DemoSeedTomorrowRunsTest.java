package src.backend.schedule.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 데모 규모 시드({@code db/migration-demo/V14}) 위에서 일일 회차 생성이 <b>데모 학원 30대의 내일 회차</b>를 만드는가
 * (Ruling 367 ④) — 학부모 앱에서 '내일' 변경·탑승 끄기를 스테이징에서 시험하려면 내일 회차가 있어야 한다.
 *
 * <p>데모 시드는 다른 시험이 전 학원 행을 세다 깨지지 않도록 테스트 DB 에서 뺀다(build.gradle). 그래서 이 시험은
 * <b>전용 스키마</b>({@value #SCHEMA})에 데모 시드까지 깔아 돌리고, 시험 시작 때마다 그 스키마를 지우고 새로 깐다 —
 * 시드의 요일이 적용 시각의 요일이라 지난 실행의 스키마를 재사용하면 내일의 요일이 어긋난다.
 */
@SpringBootTest
class DemoSeedTomorrowRunsTest {

    private static final String SCHEMA = "demo_seed_check";

    /** 학원 10곳 × 버스 3대. */
    private static final int DEMO_BUSES = 30;

    private static final String DEMO_ACADEMIES = "academy_id BETWEEN 11 AND 20";

    @DynamicPropertySource
    static void 전용_스키마에_데모_시드까지_깐다(DynamicPropertyRegistry registry) throws SQLException {
        String baseUrl = System.getProperty("spring.datasource.url");
        try (Connection connection = DriverManager.getConnection(baseUrl, "schoolbus", "schoolbus")) {
            connection.createStatement().execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
        registry.add("spring.datasource.url", () -> baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration,classpath:db/migration-local,classpath:db/migration-demo");
    }

    @Autowired
    private RunGenerationService runGenerationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Test
    void 데모_학원_30대의_내일_회차가_생기고_오늘_회차는_그대로다() {
        LocalDate today = LocalDate.now(clock);
        int todayBefore = 데모_회차_수(today);

        runGenerationService.generate(today.plusDays(1));

        assertThat(데모_회차_수(today.plusDays(1)))
                .as("버스마다 등원·하원 1건씩 — 내일 요일 스케줄이 없으면 0건이다")
                .isGreaterThanOrEqualTo(DEMO_BUSES)
                .isEqualTo(DEMO_BUSES * 2);
        assertThat(데모_회차_수(today)).as("기존 오늘 요일 스케줄·회차는 그대로").isEqualTo(todayBefore).isEqualTo(DEMO_BUSES * 2);
    }

    @Test
    void 내일_회차마다_그_요일의_고정_노선과_정차지가_있다() {
        runGenerationService.generate(LocalDate.now(clock).plusDays(1));
        int withoutStops = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM run r
                WHERE r.academy_id BETWEEN 11 AND 20 AND r.service_date = ?
                  AND NOT EXISTS (SELECT 1 FROM route ro JOIN route_stop rs ON rs.route_id = ro.id
                                  WHERE ro.bus_id = r.bus_id AND ro.direction = r.direction
                                    AND ro.weekday = lower(to_char(r.service_date, 'dy')))""", Integer.class,
                LocalDate.now(clock).plusDays(1));

        assertThat(withoutStops).as("노선이 없으면 명단 미리보기·확정·변경 승인이 그 회차의 정차지를 못 찾는다").isZero();
    }

    private int 데모_회차_수(LocalDate serviceDate) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run WHERE " + DEMO_ACADEMIES + " AND service_date = ?",
                Integer.class, serviceDate);
    }
}
