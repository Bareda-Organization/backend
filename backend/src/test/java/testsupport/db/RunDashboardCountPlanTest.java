package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import src.backend.BackendApplication;
import src.backend.run.repository.RunDashboardRepository;

/**
 * 전체 관제의 "학원·상태별 그날 회차 수" 집계가 인덱스로 풀리는지 본다. 이 집계는 끝난 회차까지 센다 — 끝나지 않은 회차만 담은 부분 인덱스
 * ({@code ix_run_open_service_date})로는 풀리지 않고, 학원이 앞에 오는 인덱스는 날짜 조건만으로는 쓸 수 없다. 그래서 운행일을 앞세운 인덱스가
 * 있어야 하루 분량만 읽는다. 저장소 메서드가 내는 SQL 을 그대로 잡아 일반(generic) 계획으로 {@code EXPLAIN} 한다 —
 * 순차 스캔은 끄고, 운영 분포(끝난 회차가 대부분)의 합성 행을 이 트랜잭션에서만 넣는다.
 */
@SpringBootTest(classes = BackendApplication.class)
class RunDashboardCountPlanTest {

    private static final Pattern RUN_SELECT = Pattern.compile("(?is)^\\s*select\\b.*\\bfrom run\\b.*");

    @Autowired
    private RunDashboardRepository runDashboardRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void 학원_상태별_그날_회차_수는_운행일을_앞세운_인덱스로_풀린다() throws Exception {
        String sql = 실행된_SQL(() -> runDashboardRepository.countByAcademyAndStatus(LocalDate.of(2030, 1, 7)));

        String plan = 일반_계획을_본다(sql);

        assertThat(plan).as("%s%n%s", sql, plan).contains("ix_run_service_date").doesNotContain("Seq Scan")
                .doesNotContain("ix_run_academy_date_depart");
    }

    private static String 실행된_SQL(Runnable repositoryCall) {
        Logger logger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            repositoryCall.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
        return appender.list.stream().map(event -> event.getFormattedMessage().replaceAll("\\s+", " ").trim())
                .filter(message -> RUN_SELECT.matcher(message).matches()).findFirst()
                .orElseThrow(() -> new AssertionError("저장소 호출이 기대한 SQL 을 내지 않았다: " + appender.list));
    }

    /** 운영 분포 — 끝난 회차가 대부분이다. 회차는 시드의 첫 회차를 운행일만 하루씩 앞당겨 복제한다(유일 제약을 피한다). */
    private static final String 운영_분포_SQL = """
            INSERT INTO run (academy_id, bus_id, service_date, direction, depart_time, confirm_at, status, origin_name,
                             destination_name, started_at, finished_at)
            SELECT r.academy_id, r.bus_id, r.service_date - g, r.direction, r.depart_time, r.confirm_at, 'finished',
                   r.origin_name, r.destination_name, r.depart_time, r.depart_time
            FROM (SELECT * FROM run ORDER BY id LIMIT 1) r, generate_series(1, 20000) g
            """;

    private String 일반_계획을_본다(String sql) throws SQLException {
        StringBuilder prepared = new StringBuilder();
        int index = 0;
        for (char c : sql.toCharArray()) {
            prepared.append(c == '?' ? "$" + (++index) : String.valueOf(c));
        }
        String nulls = index == 0 ? "" : "(" + String.join(", ", java.util.Collections.nCopies(index, "NULL")) + ")";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "SET LOCAL enable_seqscan = off");
                execute(connection, "SET LOCAL plan_cache_mode = force_generic_plan");
                execute(connection, 운영_분포_SQL);
                execute(connection, "ANALYZE run");
                execute(connection, "PREPARE dashboard_count_plan AS " + prepared);
                return 계획(connection, "EXPLAIN EXECUTE dashboard_count_plan" + nulls);
            } finally {
                connection.rollback();
            }
        }
    }

    private static String 계획(Connection connection, String explain) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(explain)) {
            while (rows.next()) {
                lines.add(rows.getString(1));
            }
        }
        return String.join("\n", lines);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
