package src.backend.student.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.student.query.StudentPhotoQueryService;

/**
 * 사진 파일 이름으로 학생을 찾는 조회가 사진 전용 인덱스를 탄다 — 끝 조각 LIKE 는 앞이 와일드카드라 어떤 B-tree 도 못 타서 학생 수만큼
 * 훑는다. 저장소 메서드를 부를 때 Hibernate 가 내는 SQL 을 그대로 잡아 {@code EXPLAIN} 한다(순차 스캔을 꺼 인덱스를 쓸 수 있는지만 본다).
 * 학원 이름 인덱스가 같은 조건(학원)을 받아 우연히 이기지 않도록 이 트랜잭션 안에서만 지운다.
 */
@SpringBootTest
class StudentPhotoLookupPlanTest {

    private static final Pattern STUDENT_PHOTO_SELECT = Pattern.compile("(?is)^\\s*select\\b.*\\bfrom student\\b.*photo_url.*");

    @Autowired
    private StudentPhotoQueryService photoQueryService;

    @Autowired
    private DataSource dataSource;

    @Test
    void 학원_범위_사진_조회는_사진_인덱스를_탄다() throws Exception {
        AuthUser staff = new AuthUser(2L, 1L, Role.STAFF, AccountStatus.ACTIVE, false);

        assertThat(계획(실행된_SQL(() -> 찾아본다(staff)))).containsPattern("Index Scan (using|on) ix_student_photo");
    }

    @Test
    void 전_학원_사진_조회도_사진_인덱스를_탄다() throws Exception {
        AuthUser admin = new AuthUser(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE, false);

        assertThat(계획(실행된_SQL(() -> 찾아본다(admin)))).containsPattern("Index Scan (using|on) ix_student_photo");
    }

    private void 찾아본다(AuthUser requester) {
        try {
            photoQueryService.read(requester, "no-such-photo.jpg");
        } catch (RuntimeException expectedNotFound) {
            // 없는 파일이라 404 로 끝나는 것이 정상이다 — 이 시험은 그 조회가 낸 SQL 만 본다
        }
    }

    private static String 실행된_SQL(Runnable call) {
        Logger logger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            call.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
        return appender.list.stream().map(event -> event.getFormattedMessage().replaceAll("\\s+", " ").trim())
                .filter(message -> STUDENT_PHOTO_SELECT.matcher(message).matches())
                .findFirst()
                .orElseThrow(() -> new AssertionError("사진 조회가 기대한 SQL 을 내지 않았다: " + appender.list));
    }

    /** SQL 의 {@code ?} 를 앞 글자로 갈라 채운다 — 학원 번호와 행 수 제한 자리는 숫자, 나머지(사진 파일 이름)는 문자열이다. */
    private String 계획(String sql) throws SQLException {
        StringBuilder filled = new StringBuilder();
        int from = 0;
        for (int mark = sql.indexOf('?'); mark >= 0; mark = sql.indexOf('?', from)) {
            String before = sql.substring(Math.max(0, mark - 60), mark);
            boolean number = before.matches("(?s).*(academy_id\\s*=\\s*|fetch first |limit )$");
            filled.append(sql, from, mark).append(number ? "1" : "'no-such-photo.jpg'");
            from = mark + 1;
        }
        filled.append(sql.substring(from));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "SET LOCAL enable_seqscan = off");
                execute(connection, "DROP INDEX IF EXISTS ix_student_academy_name");
                List<String> lines = new ArrayList<>();
                try (Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery("EXPLAIN " + filled)) {
                    while (rows.next()) {
                        lines.add(rows.getString(1));
                    }
                }
                return String.join("\n", lines);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
