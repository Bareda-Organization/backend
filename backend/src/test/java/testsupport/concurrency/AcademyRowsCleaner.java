package testsupport.concurrency;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 커밋을 실제로 하는 동시성 시험이 남긴 학원 단위 행을 지운다 — 시험 학원 이름으로 학원·회차·탑승자·보고·알림을 거슬러 지운다.
 */
public final class AcademyRowsCleaner {

    private AcademyRowsCleaner() {
    }

    /** 이름이 {@code academyName} 인 학원이 가진 행을 외래 키 순서대로 모두 지운다. */
    public static void delete(JdbcTemplate jdbcTemplate, String academyName) {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + academyName + "')";
        String runIds = "(SELECT id FROM run WHERE academy_id IN " + academyIds + ")";
        String riderIds = "(SELECT id FROM run_rider WHERE run_id IN " + runIds + ")";
        String accountIds = "(SELECT id FROM account WHERE academy_id IN " + academyIds + ")";
        jdbcTemplate.update("DELETE FROM emergency_alert WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM exception_report WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM notification_log WHERE recipient_account_id IN " + accountIds);
        jdbcTemplate.update("DELETE FROM change_request WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM boarding_intent WHERE run_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM no_show_contact WHERE no_show_case_id IN "
                + "(SELECT id FROM no_show_case WHERE run_rider_id IN " + riderIds + ")");
        jdbcTemplate.update("DELETE FROM no_show_case WHERE run_rider_id IN " + riderIds);
        jdbcTemplate.update("DELETE FROM rider_status_history WHERE run_rider_id IN " + riderIds);
        jdbcTemplate.update("DELETE FROM run_rider WHERE run_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM assignment WHERE run_id IN " + runIds);
        jdbcTemplate.update("UPDATE confirmed_route SET current_version_id = NULL WHERE run_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM run_stop WHERE route_version_id IN "
                + "(SELECT id FROM route_version WHERE confirmed_route_id IN " + runIds + ")");
        jdbcTemplate.update("DELETE FROM route_version WHERE confirmed_route_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM confirmed_route WHERE run_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy_setting WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM guardian_student WHERE student_id IN "
                + "(SELECT id FROM student WHERE academy_id IN " + academyIds + ")");
        jdbcTemplate.update("DELETE FROM guardian WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + academyName + "'");
    }
}
