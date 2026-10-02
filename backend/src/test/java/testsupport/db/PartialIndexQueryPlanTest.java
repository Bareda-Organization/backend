package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import src.backend.BackendApplication;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 부분 인덱스에 기대는 <b>실제 저장소 쿼리</b>가 그 인덱스를 타는지 본다(BR-348 · BR-326) — 사람이 옮겨 쓴 SQL 이 아니라, 저장소 메서드를 부를 때
 * Hibernate 가 내는 SQL({@code org.hibernate.SQL} 로그)을 그대로 잡아 {@code EXPLAIN} 한다. JPQL 이 바뀌면 이 SQL 이 바뀌어 시험이 따라 움직인다.
 *
 * <p>두 갈래다.
 * <ul>
 *   <li><b>리터럴 쿼리</b> — 상태를 enum 리터럴로 쓰는 쿼리({@code ix_run_moving} · {@code ix_run_open_service_date} · 확정 실패 집계 ·
 *       {@code ix_student_retention_cutoff}). 부분 인덱스는 조건이 리터럴일 때만 일반(generic) 계획에서도 쓰이므로(14.6만 행 실측
 *       20.7ms ↔ 0.033ms, Ruling 673·701) {@code plan_cache_mode = force_generic_plan} 으로 계획하고, SQL 에 상태 리터럴이 있는지를
 *       따로 본다 — 누가 {@code :status} 파라미터로 되돌리면 둘 다 실패한다.</li>
 *   <li><b>파라미터 쿼리</b> — 상태를 바인딩 파라미터로 받는 {@code findDueForConfirmation} · {@code countOverdueUnconfirmed}. ERD §5.3 Ruling 631 이
 *       "맞춤(custom) 계획이 유지돼 부분 인덱스를 쓴다" 고 받아들인 쿼리라 실제 값을 넣은 맞춤 계획으로 본다. 쿼리가 인덱스 조건
 *       (status · canceled_at)을 빠뜨리면 이 인덱스를 못 쓴다.</li>
 * </ul>
 * 순차 스캔은 끈다({@code enable_seqscan = off}) — 시험 DB 는 표가 작아 순차 스캔이 늘 이기므로, 인덱스가 조건을 <b>함의로 받아들이는지</b>만 본다.
 * 같은 조건을 받을 수 있는 다른 인덱스는 이 트랜잭션 안에서만 지우고 되돌려({@code ROLLBACK}) 두 후보의 비용이 같은 우연을 없앤다.
 */
@SpringBootTest(classes = BackendApplication.class)
class PartialIndexQueryPlanTest {

    private static final String SQL_LOGGER = "org.hibernate.SQL";

    private static final Pattern RUN_STATEMENT = Pattern.compile("(?is)^\\s*(select\\b.*\\bfrom run\\b|update run\\b).*");

    private static final Pattern STUDENT_STATEMENT = Pattern.compile("(?is)^\\s*select\\b.*\\bfrom student\\b.*");

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private DataSource dataSource;

    /** 확정 배치가 매 틱 읽는 조회 — 상태는 파라미터다(Ruling 631). */
    @Test
    void 확정_배치_조회는_ix_run_status_confirm_at_으로_풀린다() throws Exception {
        String sql = 실행된_SQL(RUN_STATEMENT, () -> runRepository.findDueForConfirmation(OffsetDateTime.now(),
                LocalDate.now(), RunStatus.IDLE, PageRequest.of(0, 50)));

        String plan = 맞춤_계획을_본다(sql, List.of("'idle'", "now()", "current_date", "now()", "50"),
                List.of("ix_run_open_service_date"));

        assertThat(plan).as("%s%n%s", sql, plan).contains("ix_run_status_confirm_at").doesNotContain("Seq Scan");
    }

    /** 30초마다 도는 확정 지연 게이지 — 상태는 파라미터다(Ruling 631). */
    @Test
    void 확정_지연_집계는_ix_run_status_confirm_at_으로_풀린다() throws Exception {
        String sql = 실행된_SQL(RUN_STATEMENT, () -> runRepository.countOverdueUnconfirmed(RunStatus.IDLE,
                OffsetDateTime.now(), LocalDate.now()));

        String plan = 맞춤_계획을_본다(sql, List.of("'idle'", "now()", "current_date"), List.of("ix_run_open_service_date"));

        assertThat(plan).as("%s%n%s", sql, plan).contains("ix_run_status_confirm_at").doesNotContain("Seq Scan");
    }

    @Test
    void 확정_실패_집계는_idle_리터럴로_ix_run_status_confirm_at_을_일반_계획에서도_탄다() throws Exception {
        String sql = 실행된_SQL(RUN_STATEMENT, () -> runRepository.countConfirmFailedByAcademy(LocalDate.now()));

        String plan = 일반_계획을_본다(sql, List.of("ix_run_open_service_date"));

        assertThat(sql).as("상태가 바인딩 파라미터면 부분 인덱스를 못 쓴다").contains("'idle'");
        assertThat(plan).as("%s%n%s", sql, plan).contains("ix_run_status_confirm_at").doesNotContain("Seq Scan");
    }

    @Test
    void 주의_회차_지연_집계는_finished_리터럴로_ix_run_open_service_date_를_일반_계획에서도_탄다() throws Exception {
        String sql = 실행된_SQL(RUN_STATEMENT, () -> runRepository.countDelayedByAcademy(LocalDate.now()));

        String plan = 일반_계획을_본다(sql, List.of());

        assertThat(sql).as("상태가 바인딩 파라미터면 부분 인덱스를 못 쓴다").contains("'finished'");
        assertThat(plan).as("%s%n%s", sql, plan).contains("ix_run_open_service_date")
                .doesNotContain("ix_run_academy_date_depart");
    }

    /** 이동 중 회차를 읽는 네 쿼리 — 근접 판정 · 끝나지 않은 회차 게이지 · 관리자 목록 · 강제 종료. */
    @Test
    void 이동_중_회차를_읽는_쿼리는_moving_리터럴로_ix_run_moving_을_일반_계획에서도_탄다() throws Exception {
        LocalDate before = LocalDate.now();
        List<String> names = List.of("findMovingFromServiceDate", "countStaleMoving", "findStaleMoving",
                "finishIfStaleMoving");
        List<Runnable> calls = List.of(
                () -> runRepository.findMovingFromServiceDate(before, 0L, PageRequest.of(0, 50)),
                () -> runRepository.countStaleMoving(before),
                () -> runRepository.findStaleMoving(before, Limit.of(10)),
                () -> runRepository.finishIfStaleMoving(-1L, before, OffsetDateTime.now()));

        SoftAssertions softly = new SoftAssertions();
        for (int i = 0; i < calls.size(); i++) {
            String sql = 실행된_SQL(RUN_STATEMENT, calls.get(i));
            String plan = 일반_계획을_본다(sql, List.of("ix_run_open_service_date"));
            softly.assertThat(sql).as("%s — 상태가 바인딩 파라미터면 부분 인덱스를 못 쓴다", names.get(i)).contains("'moving'");
            softly.assertThat(plan).as("%s%n%s%n%s", names.get(i), sql, plan).contains("ix_run_moving");
        }
        softly.assertAll();
    }

    @Test
    void 퇴원_90일_경과_학생_조회는_ix_student_retention_cutoff_로_풀린다() throws Exception {
        String sql = 실행된_SQL(STUDENT_STATEMENT,
                () -> studentRepository.findIdsForAnonymization(OffsetDateTime.now(), Limit.of(10)));

        String plan = 일반_계획을_본다(sql, List.of());

        assertThat(plan).as("%s%n%s", sql, plan).contains("ix_student_retention_cutoff");
    }

    /** 저장소 메서드를 부르는 동안 Hibernate 가 낸 SQL 중 패턴에 맞는 첫 문장 — 없으면 시험이 아니라 쿼리 경로의 문제다. */
    private static String 실행된_SQL(Pattern statement, Runnable repositoryCall) {
        Logger logger = (Logger) LoggerFactory.getLogger(SQL_LOGGER);
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
                .filter(message -> statement.matcher(message).matches())
                .findFirst()
                .orElseThrow(() -> new AssertionError("저장소 호출이 기대한 SQL 을 내지 않았다: " + appender.list));
    }

    /** 바인딩 값을 SQL 리터럴로 채운 맞춤 계획 — 파라미터 쿼리가 실제 값으로 계획될 때 인덱스를 받는지 본다. */
    private String 맞춤_계획을_본다(String sql, List<String> bindLiterals, List<String> 가려둘_인덱스) throws SQLException {
        String filled = sql;
        for (String literal : bindLiterals) {
            filled = filled.replaceFirst("\\?", java.util.regex.Matcher.quoteReplacement(literal));
        }
        assertThat(filled).as("채우지 못한 바인딩이 남았다 — 값 목록을 SQL 의 ? 순서와 맞춘다").doesNotContain("?");
        String explained = filled;
        return 계획을_본다(connection -> 계획(connection, "EXPLAIN " + explained), 가려둘_인덱스);
    }

    /** 일반(generic) 계획 — 바인딩 값을 모르는 채 계획한다. 상태가 리터럴이어야만 부분 인덱스가 선택된다. */
    private String 일반_계획을_본다(String sql, List<String> 가려둘_인덱스) throws SQLException {
        StringBuilder prepared = new StringBuilder();
        int index = 0;
        for (char c : sql.toCharArray()) {
            prepared.append(c == '?' ? "$" + (++index) : String.valueOf(c));
        }
        String nulls = index == 0 ? "" : "(" + String.join(", ", java.util.Collections.nCopies(index, "NULL")) + ")";
        return 계획을_본다(connection -> {
            execute(connection, "SET LOCAL plan_cache_mode = force_generic_plan");
            execute(connection, "PREPARE partial_index_plan AS " + prepared);
            try {
                return 계획(connection, "EXPLAIN EXECUTE partial_index_plan" + nulls);
            } finally {
                execute(connection, "DEALLOCATE partial_index_plan");
            }
        }, 가려둘_인덱스);
    }

    private interface PlanReader {
        String read(Connection connection) throws SQLException;
    }

    /** 순차 스캔을 끄고(필요하면 경쟁 인덱스를 이 트랜잭션에서만 지우고) 계획을 읽은 뒤 되돌린다. */
    private String 계획을_본다(PlanReader reader, List<String> 가려둘_인덱스) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "SET LOCAL enable_seqscan = off");
                for (String index : 가려둘_인덱스) {
                    execute(connection, "DROP INDEX IF EXISTS " + index);
                }
                return reader.read(connection);
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
