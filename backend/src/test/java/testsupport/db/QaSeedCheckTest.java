package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * QA Mock 데이터({@code db/qa-seed}) 확인 — 시나리오 문서(workspace 저장소 {@code docs/qa/QA_SCENARIOS.md})가 약속하는
 * 모양을 데이터가 지키는가(2026-10-03 사용자 요구: 개인정보 빈칸 없음 · 노선당 승하차지 10곳 · 한 승하차지에 학생 여럿).
 *
 * <p>자동 시험은 QA 데이터를 쓰지 않는다(시험은 {@code db/fixture}). 그래서 스키마가 바뀌어 이 데이터가 깔리지 않게
 * 돼도 다른 시험은 전부 초록이고, 로컬 재기동이 Flyway 오류로 멈춰서야 드러난다 — 그 구멍을 이 시험이 막는다.
 */
class QaSeedCheckTest extends MigratedPostgresTestBase {

    private static final String DUMMY_HASH = "$2a$10$Noeszx0nzJUfNo4ubCD03eNfMVfMD9feMo04y/8DHiFLUBF.JZ/Fq";

    @BeforeAll
    static void 스키마와_QA_시드를_적용한다() {
        migrate(Map.of("seedPasswordHash", DUMMY_HASH), SCHEMA_LOCATION, "classpath:db/qa-seed");
    }

    @Test
    void 학원_경계를_넘는_행이_없다() throws Exception {
        try (Connection connection = connection()) {
            Map<String, Long> counts = SeedBoundaryCheckTest.점검한다(connection);

            assertThat(counts.values()).as("항목별 불일치 건수 %s", counts).containsOnly(0L);
            assertThat(count(connection, "SELECT count(*) FROM run_rider")).as("탑승자 행이 있다").isPositive();
        }
    }

    @Test
    void 학원_계정_학생_보호자_매니저의_개인정보_칸이_비어_있지_않다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT (SELECT count(*) FROM academy WHERE coalesce(address, '') = '' OR coalesce(contact, '') = ''
                                                            OR coalesce(memo, '') = '' OR lat IS NULL)
                         + (SELECT count(*) FROM account WHERE coalesce(phone, '') = '' OR coalesce(email, '') = '')
                         + (SELECT count(*) FROM student WHERE coalesce(student_phone, '') = '' OR gender IS NULL
                                OR birth_date IS NULL OR coalesce(grade, '') = '' OR coalesce(class_name, '') = ''
                                OR coalesce(note, '') = '')
                         + (SELECT count(*) FROM guardian WHERE coalesce(phone, '') = '')
                         + (SELECT count(*) FROM manager WHERE work_hours IS NULL OR coalesce(phone, '') = '')
                         + (SELECT count(*) FROM weekly_address WHERE coalesce(address_detail, '') = '' OR stop_id IS NULL)
                    """)).isZero();
            assertThat(count(connection, "SELECT count(*) FROM student")).isPositive();
        }
    }

    @Test
    void 노선마다_승하차지가_10곳이다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT count(*) FROM route")).isPositive();
            assertThat(count(connection, """
                    SELECT count(*) FROM route r
                    WHERE (SELECT count(*) FROM route_stop rs WHERE rs.route_id = r.id) <> 10""")).isZero();
        }
    }

    @Test
    void 월요일부터_토요일까지_승하차지마다_학생이_2명_이상_탄다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT count(*) FROM weekly_address")).as("요일별 주소가 있다").isPositive();
            assertThat(count(connection, """
                    SELECT count(*) FROM route r JOIN route_stop rs ON rs.route_id = r.id
                    WHERE r.weekday <> 'sun'
                      AND (SELECT count(DISTINCT wa.student_id) FROM weekly_address wa
                           JOIN student s ON s.id = wa.student_id AND s.deleted_at IS NULL
                           WHERE wa.stop_id = rs.stop_id AND wa.weekday = r.weekday AND wa.direction = 'to_academy') < 2
                    """)).as("평일·토요일 등원에 학생이 2명 미만인 승하차지 칸").isZero();
        }
    }

    @Test
    void 차량의_요일별_탑승_인원이_학생석을_넘지_않는다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT count(*) FROM weekly_address")).as("요일별 주소가 있다").isPositive();
            assertThat(count(connection, """
                    SELECT count(*) FROM route r JOIN bus b ON b.id = r.bus_id
                    WHERE (SELECT count(DISTINCT wa.student_id) FROM weekly_address wa
                           JOIN route_stop rs ON rs.route_id = r.id AND rs.stop_id = wa.stop_id
                           JOIN student s ON s.id = wa.student_id AND s.deleted_at IS NULL
                           WHERE wa.weekday = r.weekday AND wa.direction = r.direction) > b.student_capacity
                    """)).isZero();
        }
    }

    @Test
    void 초기화_시각_기준으로_오늘_회차가_상태별로_있고_끝나지_않은_옛_회차가_1건_있다() throws Exception {
        try (Connection connection = connection()) {
            for (String status : new String[] {"finished", "moving", "confirmed", "idle"}) {
                assertThat(count(connection, "SELECT count(*) FROM run WHERE status = '" + status
                        + "' AND canceled_at IS NULL AND depart_time BETWEEN now() - interval '2 hours' AND now() + interval '3 hours'"))
                        .as("초기화 시각 앞뒤의 %s 회차", status).isPositive();
            }
            assertThat(count(connection, """
                    SELECT count(*) FROM run WHERE status = 'moving'
                      AND service_date < (now() AT TIME ZONE 'Asia/Seoul')::date - 1""")).isEqualTo(1);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
