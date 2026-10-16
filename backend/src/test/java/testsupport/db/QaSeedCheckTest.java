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

    @Test
    void 행위자_열이_앱과_같은_종류의_id_를_가리킨다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT count(*) FROM emergency_alert")).isPositive();
            // 비상 신고자는 매니저 id(EmergencyStaffQueryService 가 매니저로 찾는다) — 계정 id 를 넣으면 남의 이름이 뜬다
            assertThat(count(connection, """
                    SELECT count(*) FROM emergency_alert e
                    WHERE NOT EXISTS (SELECT 1 FROM assignment a WHERE a.run_id = e.run_id AND a.manager_id = e.raised_by
                                                                 AND a.role = e.raised_by_role)""")).isZero();
            // 예외 보고자 · 승하차 처리자 · 미승차 연락자 · 비상 확인자는 계정 id
            assertThat(count(connection, """
                    SELECT (SELECT count(*) FROM exception_report x WHERE NOT EXISTS (
                                SELECT 1 FROM manager m WHERE m.account_id = x.reported_by))
                         + (SELECT count(*) FROM rider_status_history h WHERE h.changed_by IS NOT NULL AND NOT EXISTS (
                                SELECT 1 FROM manager m WHERE m.account_id = h.changed_by AND m.role = 'escort'))
                         + (SELECT count(*) FROM no_show_contact c WHERE NOT EXISTS (
                                SELECT 1 FROM manager m WHERE m.account_id = c.attempted_by))
                         + (SELECT count(*) FROM emergency_alert e WHERE e.acked_by IS NOT NULL AND NOT EXISTS (
                                SELECT 1 FROM academy_staff s WHERE s.account_id = e.acked_by))
                    """)).isZero();
        }
    }

    /** 시드가 런타임이 만들지 않는 이력·팝업 값을 심지 않는다(R53 t2 A3·A4) — 화면·알림이 실제와 다른 모양을 보이지 않게. */
    @Test
    void 이력과_팝업_값이_런타임이_만드는_모양이다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT count(*) FROM run_rider WHERE status = 'absent'"))
                    .as("미등원 탑승자가 있다").isPositive();
            assertThat(count(connection, """
                    SELECT count(*) FROM rider_status_history h JOIN run_rider r ON r.id = h.run_rider_id
                    WHERE r.status = 'absent' AND h.to_status = 'absent'""")).as("확정 전 미탑승 신청은 이력을 남기지 않는다").isZero();
            assertThat(count(connection, """
                    SELECT count(*) FROM rider_status_history h
                    JOIN run_rider r ON r.id = h.run_rider_id JOIN run u ON u.id = r.run_id
                    WHERE u.direction = 'from_academy' AND h.to_status = 'boarded' AND h.actor_type = 'escort'"""))
                    .as("하원 승차는 시스템 자동뿐이다").isZero();
            assertThat(count(connection, """
                    SELECT count(*) FROM notification_log
                    WHERE popup <> (type IN ('emergency', 'emergency_canceled'))""")).as("팝업은 비상 2종만 true").isZero();
        }
    }

    /**
     * Ruling 887 ④ — 등원 하차는 최종 도착의 자동 처리({@code system})뿐이다. 동승자가 하차를 누르는 경로는 등원에 없어,
     * 시드가 동승자 행위로 심으면 화면 이력이 런타임과 다른 모양이 된다. 하원 하차는 동승자 행위로 둔다.
     */
    @Test
    void 등원_하차_이력은_system_뿐이다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM rider_status_history h JOIN run_rider r ON r.id = h.run_rider_id
                    JOIN run u ON u.id = r.run_id
                    WHERE u.direction = 'to_academy' AND h.to_status = 'alighted'""")).as("등원 하차 이력이 실제로 있다").isPositive();
            assertThat(count(connection, """
                    SELECT count(*) FROM rider_status_history h JOIN run_rider r ON r.id = h.run_rider_id
                    JOIN run u ON u.id = r.run_id
                    WHERE u.direction = 'to_academy' AND h.to_status = 'alighted' AND h.actor_type <> 'system'""")).as("등원 하차의 행위자는 system 뿐").isZero();
        }
    }

    /** 변경 요청의 신청자는 보호자·학생 계정이다 — 앱 미가입 학생에게 관계자 계정을 신청자로 두면 알림이 '학부모' 역할로 관계자에게 간다. */
    @Test
    void 변경_요청_신청자와_알림_수신자의_역할이_계정과_같다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT count(*) FROM change_request")).isPositive();
            assertThat(count(connection, """
                    SELECT count(*) FROM change_request c JOIN account a ON a.id = c.requested_by
                    WHERE a.role NOT IN ('parent', 'student')""")).as("보호자·학생이 아닌 신청자").isZero();
            assertThat(count(connection, """
                    SELECT count(*) FROM notification_log n JOIN account a ON a.id = n.recipient_account_id
                    WHERE n.recipient_role <> a.role""")).as("수신 역할이 계정 역할과 다른 알림").isZero();
            assertThat(count(connection, "SELECT count(*) FROM notification_log WHERE type IN ('boarding_canceled', 'alighting_canceled')"))
                    .as("더 이상 만들지 않는 알림 종류").isZero();
        }
    }

    /** ②구간 한도(C-04) — 승인·거절·대기는 1회 소진, 자동 거절은 되돌려 0(ChangeRequestAutoRejectionPersistence). */
    @Test
    void 변경_한도_카운터가_요청_상태와_맞다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT count(*) FROM change_request WHERE status = 'auto_rejected'")).isPositive();
            assertThat(count(connection, """
                    SELECT count(*) FROM change_request c JOIN boarding_intent b ON b.run_id = c.run_id AND b.student_id = c.student_id
                    WHERE c.window_segment = 2 AND b.change_used_count <> CASE WHEN c.status = 'auto_rejected' THEN 0 ELSE 1 END"""))
                    .isZero();
        }
    }

    @Test
    void 오늘_임시_취소된_회차가_1건_있다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM run WHERE canceled_at BETWEEN now() - interval '1 hour' AND now()
                      AND cancel_source = 'staff' AND status = 'idle' AND depart_time > now()""")).isEqualTo(1);
        }
    }

    /** C-15 — 하원은 마지막 승하차지 도착만 기록되고 종료는 보류, 남은 탑승자가 하차해야 서버가 종료한다. */
    @Test
    void 하원_종료_대기_회차는_마지막_승하차지에_도착했고_탑승자가_남아_있다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT count(*) FROM run WHERE finish_pending AND status <> 'moving'")).isZero();
            assertThat(count(connection, """
                    SELECT count(*) FROM run r JOIN confirmed_route cr ON cr.run_id = r.id
                    JOIN run_stop x ON x.route_version_id = cr.current_version_id
                    WHERE r.status = 'moving' AND r.direction = 'from_academy' AND r.finish_pending AND r.finished_at IS NULL
                      AND r.depart_time > now() - interval '3 hours'
                      AND x.seq = (SELECT max(seq) FROM run_stop WHERE route_version_id = cr.current_version_id)
                      AND x.arrived_at IS NOT NULL AND x.departed_at IS NULL
                      AND EXISTS (SELECT 1 FROM run_rider rr WHERE rr.run_id = r.id AND rr.status = 'boarded')""")).isEqualTo(1);
        }
    }

    /** 메인 관리자 '회차 강제 확정' 대상 — 확정 시각이 지난 idle 회차, 실패 횟수가 쌓였고 다음 재시도는 아직 멀다. */
    @Test
    void 확정이_연속_실패한_회차가_강제_확정_대상으로_1건_있다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM run
                    WHERE status = 'idle' AND canceled_at IS NULL AND confirm_at < now() AND consecutive_failures >= 3
                      AND confirm_retry_at > now() + interval '1 hour'
                      AND service_date >= (now() AT TIME ZONE 'Asia/Seoul')::date""")).isEqualTo(1);
        }
    }

    /** Ruling 893 — 배포된 경유 지점은 waypoint(applied) + 현재 판본(source = waypoint)의 정차 항목으로 읽힌다. */
    @Test
    void 경유_지점이_배포된_확정_회차가_1건_있고_정차_순번이_이어진다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM run r JOIN confirmed_route cr ON cr.run_id = r.id
                    JOIN route_version v ON v.id = cr.current_version_id AND v.source = 'waypoint'
                    JOIN waypoint w ON w.run_id = r.id AND w.applied AND w.removed_at IS NULL
                    WHERE r.status = 'confirmed' AND r.canceled_at IS NULL
                      AND EXISTS (SELECT 1 FROM run_stop x WHERE x.route_version_id = v.id AND x.waypoint_id = w.id)
                      AND (SELECT count(*) FROM run_stop WHERE route_version_id = v.id)
                          = (SELECT max(seq) FROM run_stop WHERE route_version_id = v.id)""")).isEqualTo(1);
        }
    }

    /** M-04 · RUN-07 — 확정 노선의 현재 판본을 배치된 매니저가 아직 확인하지 않은 확정 회차. */
    @Test
    void 노선_확정을_매니저가_아직_확인하지_않은_확정_회차가_1건_있다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(DISTINCT r.id) FROM run r JOIN confirmed_route cr ON cr.run_id = r.id
                    JOIN assignment a ON a.run_id = r.id
                    WHERE r.status = 'confirmed' AND r.canceled_at IS NULL
                      AND a.acked_route_version_id IS DISTINCT FROM cr.current_version_id""")).isEqualTo(1);
        }
    }

    @Test
    void 미확인_비상이_운행_중인_학원_A_회차에_정확히_1건_있다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, "SELECT count(*) FROM emergency_alert WHERE acked_at IS NULL AND canceled_at IS NULL"))
                    .as("전체 미확인 비상").isEqualTo(1);
            assertThat(count(connection, """
                    SELECT count(*) FROM emergency_alert e JOIN run r ON r.id = e.run_id
                    WHERE e.academy_id = 1 AND r.status = 'moving' AND e.acked_at IS NULL AND e.canceled_at IS NULL""")).isEqualTo(1);
        }
    }

    /** ACAD-06 — 퇴사 관계자는 학원당 재직 1명 정원 밖의 inactive 행이고 계정은 살아 있다(로그인하면 403 AUTH_STAFF_INACTIVE). */
    @Test
    void 퇴사한_관계자가_재직_관계자와_함께_있고_학원당_재직은_1명이다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM academy_staff s JOIN account a ON a.id = s.account_id
                    WHERE s.status = 'inactive' AND a.status = 'active' AND a.role = 'staff'
                      AND EXISTS (SELECT 1 FROM academy_staff t WHERE t.academy_id = s.academy_id AND t.status = 'active')"""))
                    .isEqualTo(1);
            assertThat(count(connection, """
                    SELECT count(*) FROM (SELECT academy_id FROM academy_staff WHERE status = 'active'
                                          GROUP BY academy_id HAVING count(*) > 1) x""")).isZero();
        }
    }

    /** Ruling 891 — 삭제된 매니저는 매니저 행의 deleted_at 으로 나타내고 계정은 활성이다. 배치된 회차는 없다. */
    @Test
    void 삭제된_매니저의_계정은_활성이고_배치된_회차가_없다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM manager m JOIN account a ON a.id = m.account_id
                    WHERE m.deleted_at IS NOT NULL AND a.status = 'active'
                      AND NOT EXISTS (SELECT 1 FROM assignment x WHERE x.manager_id = m.id)""")).isEqualTo(1);
        }
    }

    /** Ruling 540 — 관리자가 초기화한 임시 비밀번호 계정은 must_change_password 가 켜져 있고 보호자에 자녀가 연결돼 있다. */
    @Test
    void 임시_비밀번호를_가진_활성_계정이_1건_있다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM account a JOIN guardian g ON g.account_id = a.id
                    JOIN guardian_student gs ON gs.guardian_id = g.id AND gs.unlinked_at IS NULL
                    WHERE a.must_change_password AND a.status = 'active'""")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM account WHERE must_change_password")).isEqualTo(1);
        }
    }

    /** 메인 관리자가 다른 학원에 손대지 않고 승인까지 해 볼 신규 학원 — 활성 · 정보 기재 · 재직 관계자 없음 · 관계자 가입 대기 1건. */
    @Test
    void 재직_관계자가_없는_활성_학원에_관계자_가입_대기가_1건_있다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM academy ac
                    WHERE ac.status = 'active' AND NOT EXISTS (SELECT 1 FROM academy_staff s WHERE s.academy_id = ac.id)
                      AND (SELECT count(*) FROM signup_request r JOIN account a ON a.id = r.account_id
                           WHERE r.academy_id = ac.id AND r.requested_role = 'staff' AND r.approver_type = 'system_admin'
                             AND r.status = 'pending' AND a.status = 'pending') = 1""")).isEqualTo(1);
        }
    }

    /** C-04 — ②구간 한도를 쓴 학부모: 오늘 확정 회차에서 한도가 소진된 채 계속 타는 학생이 있고, 같은 회차의 대기 요청 3건은 그대로. */
    @Test
    void 변경_한도를_이미_쓴_학생이_오늘_확정_회차에_있고_대기_요청_3건은_그대로다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM boarding_intent b JOIN run r ON r.id = b.run_id
                    JOIN run_rider rr ON rr.run_id = r.id AND rr.student_id = b.student_id AND rr.status = 'waiting'
                    JOIN guardian_student gs ON gs.student_id = b.student_id AND gs.unlinked_at IS NULL
                    JOIN guardian g ON g.id = gs.guardian_id JOIN account a ON a.id = g.account_id
                    WHERE r.status = 'confirmed' AND r.canceled_at IS NULL AND b.change_used_count = 1 AND b.applied_segment = 2 AND b.riding
                      AND NOT EXISTS (SELECT 1 FROM change_request c WHERE c.run_id = b.run_id AND c.student_id = b.student_id
                                      AND c.status = 'pending')
                      AND a.login_id = 'parentQuotaA'""")).isEqualTo(1);
            assertThat(count(connection, """
                    SELECT count(*) FROM change_request c JOIN run r ON r.id = c.run_id
                    WHERE c.status = 'pending' AND r.status = 'confirmed' AND r.canceled_at IS NULL""")).isEqualTo(3);
        }
    }

    /** 관계자 학생 목록 탭 — 주소 미등록(요일별 주소 0건)과 일부 등록(한 방향만 있는 요일이 있음, WeeklyAddressStatus.PARTIAL). */
    @Test
    void 요일별_주소가_없는_학생과_일부만_등록한_학생이_재원생에_있다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM student s WHERE s.deleted_at IS NULL AND s.academy_id = 1
                      AND NOT EXISTS (SELECT 1 FROM weekly_address w WHERE w.student_id = s.id)""")).isEqualTo(2);
            assertThat(count(connection, """
                    SELECT count(*) FROM student s WHERE s.deleted_at IS NULL AND s.academy_id = 1
                      AND EXISTS (SELECT 1 FROM weekly_address w WHERE w.student_id = s.id)
                      AND EXISTS (SELECT 1 FROM weekly_address w WHERE w.student_id = s.id GROUP BY w.weekday HAVING count(*) = 1)"""))
                    .isEqualTo(2);
        }
    }

    /** BUS-04 — 월~금 등·하원 열 노선 모두 학생석이 가득 찬 차량이 있어 강제 추가·이동이 첫 시도에 CAPACITY_EXCEEDED 로 막힌다. */
    @Test
    void 월요일부터_금요일까지_학생석이_가득_찬_차량이_2대_있다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM bus b WHERE b.student_capacity > 0 AND (
                        SELECT count(*) FROM route r WHERE r.bus_id = b.id AND r.weekday IN ('mon', 'tue', 'wed', 'thu', 'fri')
                          AND (SELECT count(DISTINCT wa.student_id) FROM weekly_address wa
                               JOIN route_stop rs ON rs.route_id = r.id AND rs.stop_id = wa.stop_id
                               JOIN student s ON s.id = wa.student_id AND s.deleted_at IS NULL
                               WHERE wa.weekday = r.weekday AND wa.direction = r.direction) = b.student_capacity) = 10""")).isEqualTo(2);
        }
    }

    /** QA-PAR-19 — 보호자 계정 하나에 쌍둥이 둘이 매일 같은 승하차지·같은 회차를 탄다(요일 7 × 방향 2 = 14칸이 모두 겹친다). */
    @Test
    void 보호자_한_명의_쌍둥이가_모든_요일_같은_승하차지를_탄다() throws Exception {
        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    SELECT count(*) FROM (
                        SELECT w.weekday, w.direction, w.stop_id FROM weekly_address w
                        JOIN guardian_student gs ON gs.student_id = w.student_id AND gs.unlinked_at IS NULL
                        JOIN guardian g ON g.id = gs.guardian_id JOIN account a ON a.id = g.account_id
                        WHERE a.login_id = 'parentTwinsA' GROUP BY 1, 2, 3 HAVING count(DISTINCT w.student_id) = 2) x""")).isEqualTo(14);
            assertThat(count(connection, """
                    SELECT count(*) FROM (
                        SELECT rr.run_id FROM run_rider rr JOIN guardian_student gs ON gs.student_id = rr.student_id AND gs.unlinked_at IS NULL
                        JOIN guardian g ON g.id = gs.guardian_id JOIN account a ON a.id = g.account_id
                        WHERE a.login_id = 'parentTwinsA' GROUP BY rr.run_id HAVING count(DISTINCT rr.student_id) = 2) x"""))
                    .as("쌍둥이 둘이 함께 탄 오늘·지난 회차").isPositive();
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
