package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.student.photo.spec.PhotoStorage;
import src.backend.student.photo.spec.StudentPhoto;

/**
 * 퇴원 학생 파기가 <b>실패했을 때</b>의 동작(R02-01 · R02-02) — 사진 파일을 지우지 못한 학생은 파기 완료로 기록하지 않고,
 * 묶음 안의 한 학생이 실패해도 나머지는 파기한다. 정상 경로는 {@code StudentRetentionAnonymizationTest} 가 본다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 묶음 트랜잭션이 롤백된 뒤 학생별 재시도가 따로 커밋되는지가 이 시험의 대상이라
 * 시험 전체를 한 트랜잭션에 묶으면 롤백 범위가 보이지 않는다. 심는 행은 {@link #tearDown} 이 지운다.
 */
@SpringBootTest
class StudentAnonymizationFailureTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    private static final String ACADEMY_NAME = "파기실패시험학원";

    @Autowired
    private StudentAnonymizationService studentAnonymizationService;

    @Autowired
    private PhotoStorage photoStorage;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Value("${app.photo.local.root}")
    private String photoRoot;

    private Long academyId;

    private long auditMark;

    @AfterEach
    void tearDown() {
        if (academyId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        // 이 시험이 쓴 파기 감사 행(행위자 없는 시스템 행) — 다른 시험이 건수를 세므로 남기지 않는다
        jdbcTemplate.update("DELETE FROM audit_log WHERE id > ? AND category = 'data_access' AND action = 'delete' "
                + "AND target_type = 'student' AND actor_account_id IS NULL", auditMark);
    }

    @Test
    @DisplayName("BR-310 — 사진 파일을 지우지 못한 학생은 파기하지 않고 사진 참조를 그대로 둬 다음 틱에 다시 잡힌다")
    void 사진_삭제에_실패한_학생은_익명화되지_않고_다시_시도하면_파기된다() throws IOException {
        준비한다();
        long studentId = 학생을_심는다("사진삭제실패", 사진을_저장한다());
        String fileName = 사진_파일명(studentId);
        Path photoFile = Path.of(photoRoot).resolve(fileName);
        // 파일 자리를 비어 있지 않은 디렉터리로 바꿔 deleteIfExists 가 IOException 을 내게 한다(권한·읽기 전용 마운트와 같은 형태)
        Files.delete(photoFile);
        Files.createDirectory(photoFile);
        Files.writeString(photoFile.resolve("block"), "x");
        try {
            studentAnonymizationService.anonymize(List.of(studentId));

            Map<String, Object> failed = 학생을_읽는다(studentId);
            assertThat(failed.get("anonymized_at")).as("파일을 못 지웠으면 파기 완료로 기록하지 않는다").isNull();
            assertThat(failed.get("photo_url")).as("사진 참조는 남아 다음 틱에 다시 잡힌다").isNotNull();
        } finally {
            Files.delete(photoFile.resolve("block"));
            Files.delete(photoFile);
        }

        studentAnonymizationService.anonymize(List.of(studentId));

        Map<String, Object> retried = 학생을_읽는다(studentId);
        assertThat(retried.get("anonymized_at")).as("파일이 사라진 뒤 다시 시도하면 파기된다").isNotNull();
        assertThat(retried.get("photo_url")).isNull();
    }

    @Test
    @DisplayName("BR-310 — 사진 주소의 접두사가 지금 설정과 달라도 같은 파일명이면 파일을 지우고 파기한다")
    void 접두사가_다른_사진_주소도_파일명으로_지운다() {
        준비한다();
        String currentUrl = 사진을_저장한다();
        String legacyUrl = "/uploads/legacy/" + currentUrl.substring(currentUrl.lastIndexOf('/') + 1);
        long studentId = 학생을_심는다("옛접두사", legacyUrl);
        String fileName = 사진_파일명(studentId);
        assertThat(photoStorage.read(fileName)).as("파기 전에는 파일이 있다").isPresent();

        studentAnonymizationService.anonymize(List.of(studentId));

        assertThat(photoStorage.read(fileName)).as("접두사가 달라 건너뛰면 파일이 영구히 남는다").isEmpty();
        assertThat(학생을_읽는다(studentId).get("anonymized_at")).isNotNull();
    }

    /**
     * 묶음 안의 한 학생이 결정적으로 실패해도 나머지는 파기된다(BR-311) — 묶음이 한 트랜잭션이라 한 명의 실패가 전체를 되돌리고,
     * 다음 틱의 조회가 같은 id 오름차순 묶음을 다시 주면 그 학생보다 뒤의 학생은 영영 파기되지 못했다. 실패는 학생의 계정이
     * 파기 때 받을 아이디({@code withdrawn-<계정 id>})를 다른 계정이 이미 쓰고 있는 것으로 만든다({@code uk_account_login_id}).
     */
    @Test
    @DisplayName("BR-311 — 묶음 안 한 학생이 실패해도 나머지 학생은 파기되고 실패한 학생만 남는다")
    void 묶음_안_한_학생의_실패가_나머지_파기를_막지_않는다() {
        준비한다();
        long first = 학생을_심는다("앞학생", null);
        long failing = 학생을_심는다("실패학생", null);
        long last = 학생을_심는다("뒤학생", null);
        long failingAccount = 계정을_심는다("br311-student", "student");
        jdbcTemplate.update("UPDATE student SET account_id = ? WHERE id = ?", failingAccount, failing);
        계정을_심는다("withdrawn-" + failingAccount, "parent");

        studentAnonymizationService.anonymize(List.of(first, failing, last));

        assertThat(학생을_읽는다(first).get("anonymized_at")).as("실패한 학생보다 앞").isNotNull();
        assertThat(학생을_읽는다(last).get("anonymized_at")).as("실패한 학생보다 뒤 — 묶음 전체가 롤백되면 파기되지 못한다").isNotNull();
        assertThat(학생을_읽는다(failing).get("anonymized_at")).as("실패한 학생만 다음 틱에 다시 잡힌다").isNull();
    }

    private void 준비한다() {
        auditMark = jdbcTemplate.queryForObject("SELECT coalesce(max(id), 0) FROM audit_log", Long.class);
        academyId = academyRepository.save(Academy.register("R02" + System.nanoTime() % 100_000_000, ACADEMY_NAME,
                "서울", null, null)).getId();
    }

    private long 계정을_심는다(String loginId, String role) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
                VALUES (?, ?, 'hash', '시험계정', '010-3333-4444', ?, 'active') RETURNING id
                """, Long.class, academyId, loginId, role);
    }

    private String 사진을_저장한다() {
        return photoStorage.store(StudentPhoto.of(PNG));
    }

    /** 퇴원 91일 학생 — {@code photoUrl} 이 {@code null} 이면 사진이 없다(실패 격리 시험이 사진과 무관한 실패만 보게 한다). */
    private long 학생을_심는다(String name, String photoUrl) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO student (academy_id, name, student_phone, photo_url, gender, birth_date, grade, class_name,
                                     note, deleted_at)
                VALUES (?, ?, '010-1111-2222', ?, 'male', DATE '2012-03-04', '중1', '3반', '메모', ?)
                RETURNING id
                """, Long.class, academyId, name, photoUrl, OffsetDateTime.now(clock).minusDays(91));
    }

    private String 사진_파일명(long studentId) {
        String url = jdbcTemplate.queryForObject("SELECT photo_url FROM student WHERE id = ?", String.class, studentId);
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private Map<String, Object> 학생을_읽는다(long studentId) {
        return jdbcTemplate.queryForMap("SELECT * FROM student WHERE id = ?", studentId);
    }
}
