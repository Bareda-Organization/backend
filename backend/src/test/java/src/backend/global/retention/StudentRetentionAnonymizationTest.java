package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.photo.spec.PhotoStorage;
import src.backend.student.photo.spec.StudentPhoto;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 퇴원 학생 개인정보 파기(R46 privacy · Ruling 480 ②·520) — {@link RetentionCleanupScheduler#cleanUp} 이 퇴원 90일을
 * 넘긴 학생의 개인 필드만 익명값으로 바꾸고, 승하차 이력 행은 그대로 두는지 본다.
 *
 * <p>시계는 앱의 {@link Clock}(실제 시각) 그대로이고 심는 행은 전부 {@code now} 기준 상대 시각이다 —
 * {@code RetentionCleanupSchedulerTest} 가 고정 시계를 쓰지 않는 이유(공유 DB 시드 행 삭제, Ruling 248)와 같다.
 * {@code @Transactional} 이라 심은 행·파기 결과가 시험이 끝나면 되돌아간다. 사진 <b>파일</b>은 트랜잭션 밖이라 되돌아가지
 * 않으므로 시험이 직접 지운다.
 */
@SpringBootTest
@Transactional
class StudentRetentionAnonymizationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @Autowired
    private RetentionCleanupScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PhotoStorage photoStorage;

    @Autowired
    private Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private RunRepository runRepository;

    private long academyId;

    private long runId;

    private long stopId;

    @Test
    @DisplayName("목표1·2 — 퇴원 91일은 익명화·89일과 재학생은 한 필드도 안 바뀜·승하차 이력 행 수 불변·사진 파일 삭제")
    void 퇴원_91일은_익명화되고_89일_재학생은_그대로다() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        준비한다();
        long expired = 학생을_심는다("만료학생", now.minusDays(91));
        long recent = 학생을_심는다("최근학생", now.minusDays(89));
        long enrolled = 학생을_심는다("재학학생", null);
        long expiredAccount = 학생_계정을_심는다(expired);
        String expiredPhotoName = 사진_파일명(expired);
        String recentPhotoName = 사진_파일명(recent);
        Map<String, Object> recentBefore = 행을_읽는다(recent);
        Map<String, Object> enrolledBefore = 행을_읽는다(enrolled);
        long riderRowsBefore = 행_수("run_rider", "student_id", expired);
        long changeRequestRowsBefore = 행_수("change_request", "student_id", expired);

        try {
            scheduler.cleanUp();
            entityManager.flush();
            entityManager.clear();

            Map<String, Object> after = 행을_읽는다(expired);
            assertThat(after.get("name")).as("이름은 익명값").isEqualTo(Student.ANONYMIZED_NAME);
            assertThat(after.get("student_phone")).as("학생 연락처").isNull();
            assertThat(after.get("photo_url")).as("사진 참조").isNull();
            assertThat(after.get("gender")).isNull();
            assertThat(after.get("birth_date")).isNull();
            assertThat(after.get("grade")).isNull();
            assertThat(after.get("class_name")).isNull();
            assertThat(after.get("note")).as("특이사항").isNull();
            assertThat(after.get("account_id")).as("학생 계정 연결").isNull();
            assertThat(after.get("anonymized_at")).as("익명화 시각이 남는다").isNotNull();
            assertThat(after.get("deleted_at")).as("퇴원 시각은 그대로").isNotNull();
            assertThat(photoStorage.read(expiredPhotoName)).as("사진 파일").isEmpty();
            assertThat(행_수("weekly_address", "student_id", expired)).as("요일별 주소·좌표").isZero();
            assertThat(행_수("run_rider", "student_id", expired)).as("승하차 이력 행 수 불변").isEqualTo(riderRowsBefore);
            assertThat(행_수("change_request", "student_id", expired)).isEqualTo(changeRequestRowsBefore);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT new_address || '|' || coalesce(new_lat::text, 'null') || '|' || coalesce(reason, 'null') "
                            + "FROM change_request WHERE student_id = ?", String.class, expired))
                    .as("변경 요청에 남은 주소·사유").isEqualTo(Student.ANONYMIZED_ADDRESS + "|null|null");
            assertThat(행_수("link_code", "student_id", expired)).isZero();

            Map<String, Object> account = jdbcTemplate.queryForMap("SELECT * FROM account WHERE id = ?", expiredAccount);
            assertThat(account.get("login_id")).as("로그인 아이디").isNotEqualTo("student-" + expired);
            assertThat(account.get("name")).isEqualTo(Student.ANONYMIZED_NAME);
            assertThat(account.get("email")).isNull();
            assertThat(account.get("status")).as("로그인 불가").isEqualTo("blocked");
            assertThat(행_수("refresh_token", "account_id", expiredAccount)).as("재발급 토큰").isZero();
            assertThat(행_수("device_token", "account_id", expiredAccount)).as("푸시 토큰").isZero();

            assertThat(행을_읽는다(recent)).as("89일 퇴원생은 한 필드도 안 바뀐다").isEqualTo(recentBefore);
            assertThat(행을_읽는다(enrolled)).as("재학생은 한 필드도 안 바뀐다").isEqualTo(enrolledBefore);
            assertThat(photoStorage.read(recentPhotoName)).as("89일 퇴원생의 사진 파일은 남는다").isPresent();
            assertThat(행_수("weekly_address", "student_id", recent)).isEqualTo(1);
        } finally {
            photoStorage.delete("/api/v1/files/photos/" + recentPhotoName);
            photoStorage.delete("/api/v1/files/photos/" + expiredPhotoName);
        }
    }

    @Test
    @DisplayName("목표1 — 다시 실행해도 같은 결과(멱등) · 파기 실행은 시스템 행위로 감사 기록에 남고 건수가 정확하다")
    void 재실행은_같은_결과이고_파기는_감사_기록에_남는다() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        준비한다();
        long expired = 학생을_심는다("만료학생", now.minusDays(91));
        String photoName = 사진_파일명(expired);
        long anonymizedBefore = 익명화된_학생_수();
        long auditBefore = 파기_감사_행_수();

        try {
            scheduler.cleanUp();
            entityManager.flush();
            entityManager.clear();
            Map<String, Object> first = 행을_읽는다(expired);
            long purged = 익명화된_학생_수() - anonymizedBefore;
            assertThat(purged).as("이번 실행이 익명화한 학생 수").isGreaterThanOrEqualTo(1);
            assertThat(파기_감사_행_수() - auditBefore).as("파기 감사 행").isEqualTo(1);
            Map<String, Object> audit = jdbcTemplate.queryForMap("SELECT * FROM audit_log WHERE category = 'data_access' "
                    + "AND action = 'delete' AND target_type = 'student' AND target_id IS NULL AND actor_account_id IS NULL "
                    + "ORDER BY id DESC LIMIT 1");
            assertThat(audit.get("detail").toString()).as("detail 에 파기 건수").contains("\"purged_students\": " + purged);

            scheduler.cleanUp();
            entityManager.flush();
            entityManager.clear();

            assertThat(행을_읽는다(expired)).as("재실행해도 같은 행").isEqualTo(first);
            assertThat(익명화된_학생_수() - anonymizedBefore).isEqualTo(purged);
            assertThat(파기_감사_행_수() - auditBefore).as("파기할 학생이 없으면 감사 행을 더 쓰지 않는다").isEqualTo(1);
        } finally {
            photoStorage.delete("/api/v1/files/photos/" + photoName);
        }
    }

    private void 준비한다() {
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);
        academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        OffsetDateTime depart = OffsetDateTime.now(clock).plusDays(1);
        runId = fixtures.idleRun(academyId, busId, LocalDate.now(clock).plusDays(1), Direction.TO_ACADEMY, depart,
                depart.minusMinutes(30));
    }

    /** 개인 필드를 전부 채운 학생 — 사진 파일·요일별 주소·승하차 행·변경 요청·연결 코드를 함께 심는다. */
    private long 학생을_심는다(String name, OffsetDateTime deletedAt) {
        String photoUrl = photoStorage.store(StudentPhoto.of(PNG));
        long studentId = jdbcTemplate.queryForObject("""
                INSERT INTO student (academy_id, name, student_phone, photo_url, gender, birth_date, grade, class_name,
                                     note, deleted_at)
                VALUES (?, ?, '010-1111-2222', ?, 'male', DATE '2012-03-04', '중1', '3반', '알레르기 있음', ?)
                RETURNING id
                """, Long.class, academyId, name, photoUrl, deletedAt);
        jdbcTemplate.update("""
                INSERT INTO weekly_address (student_id, weekday, direction, address, address_detail, lat, lng, verified,
                                            updated_at)
                VALUES (?, 'mon', 'to_academy', '서울시 어딘가 1', '101호', 37.5, 127.0, true, now())
                """, studentId);
        jdbcTemplate.update("INSERT INTO run_rider (run_id, student_id, stop_id, status) VALUES (?, ?, ?, 'waiting')",
                runId, studentId, stopId);
        jdbcTemplate.update("INSERT INTO link_code (student_id, code, expires_at) VALUES (?, '123456', now())",
                studentId);
        return studentId;
    }

    private long 학생_계정을_심는다(long studentId) {
        long accountId = jdbcTemplate.queryForObject("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, email, role, status)
                VALUES (?, ?, 'hash', '만료학생', '010-3333-4444', 'kid@example.com', 'student', 'active')
                RETURNING id
                """, Long.class, academyId, "student-" + studentId);
        jdbcTemplate.update("UPDATE student SET account_id = ? WHERE id = ?", accountId, studentId);
        jdbcTemplate.update("INSERT INTO refresh_token (account_id, token_hash, issued_at, expires_at) "
                + "VALUES (?, ?, now(), now() + interval '30 days')", accountId, "retention-" + studentId);
        jdbcTemplate.update("INSERT INTO device_token (account_id, device_id, token, platform) "
                + "VALUES (?, 'dev-1', 'fcm-token', 'android')", accountId);
        jdbcTemplate.update("""
                INSERT INTO change_request (academy_id, run_id, student_id, source, type, status, window_segment,
                                            new_address, new_lat, new_lng, reason, requested_by, requested_at)
                VALUES (?, ?, ?, 'change_request', 'relocate', 'pending', 1, '서울시 새 주소 9', 37.6, 127.1, '이사',
                        ?, now())
                """, academyId, runId, studentId, accountId);
        return accountId;
    }

    private String 사진_파일명(long studentId) {
        String url = jdbcTemplate.queryForObject("SELECT photo_url FROM student WHERE id = ?", String.class, studentId);
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private Map<String, Object> 행을_읽는다(long studentId) {
        return jdbcTemplate.queryForMap("SELECT * FROM student WHERE id = ?", studentId);
    }

    private long 행_수(String table, String column, long id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class,
                id);
    }

    private long 익명화된_학생_수() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM student WHERE anonymized_at IS NOT NULL", Long.class);
    }

    private long 파기_감사_행_수() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE category = 'data_access' "
                + "AND action = 'delete' AND target_type = 'student' AND target_id IS NULL AND actor_account_id IS NULL",
                Long.class);
    }
}
