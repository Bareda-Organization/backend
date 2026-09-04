package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.account.repository.RefreshTokenRepository;
import src.backend.location.repository.RunPositionRepository;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.student.repository.LinkCodeRepository;
import src.backend.student.repository.LinkRequestRepository;

/**
 * 보존 정리 배치({@link RetentionCleanupScheduler#cleanUp}) 수준의 검증(Phase 14 T2 목표 6·7) —
 * {@code NoShowEscalationSchedulerTest} 와 같은 구조({@code JdbcTemplate} 직접 삽입 · id 목록 기반
 * 뒷정리)이나 <b>시계는 앱의 {@link Clock}(실제 시각)을 그대로 쓴다.</b>
 *
 * <p>⚠ 고정 시계를 쓰지 않는 이유(Ruling 248, 2026-09-04) — 이 시험은 실제로 행을 지우는 배치를
 * 돌린다. 시계를 미래(2032년)로 고정하면 컷오프가 그만큼 미래로 밀려 <b>공유 DB 의 시드 행
 * 전부</b>({@code notification_log} 10건 · {@code run_position} 3건 · {@code link_code}·{@code link_request})가
 * 삭제되고, 같은 DB 에서 뒤에 도는 {@code NotificationOutboxWorkerTest} 가 시드를 못 찾아 실패했다
 * (Phase 14 최종 실측에서 3건). 심는 행은 전부 {@code now} 기준 상대 시각이라 실제 시각으로도
 * 판정이 결정적이다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — {@code deleteAllByIdInBatch} 가 리포지토리 메서드별
 * 독립 트랜잭션으로 커밋하므로, 테스트 스레드의 롤백은 그 커밋을 되돌리지 못한다
 * ({@code RetentionCleanupScheduler} 자바독 "테이블마다 개별 트랜잭션" 참고). 뒷정리는 삽입 시
 * 기록해 둔 id 로 직접 지운다.
 */
@SpringBootTest
class RetentionCleanupSchedulerTest {

    @Autowired
    private RetentionCleanupScheduler scheduler;

    @Autowired
    private RetentionPolicy retentionPolicy;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Autowired
    private RunPositionRepository runPositionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private OffsetDateTime now;

    private final List<Long> notificationLogIds = new ArrayList<>();
    private final List<Long> runPositionIds = new ArrayList<>();
    private final List<Long> refreshTokenIds = new ArrayList<>();
    private final List<Long> linkCodeIds = new ArrayList<>();
    private final List<Long> linkRequestIds = new ArrayList<>();
    private final List<Long> guardianIds = new ArrayList<>();
    private final List<Long> studentIds = new ArrayList<>();
    private final List<Long> accountIds = new ArrayList<>();
    private final List<Long> academyIds = new ArrayList<>();
    private final List<Long> auditLogIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        now = OffsetDateTime.now(clock);
    }

    @AfterEach
    void tearDown() {
        deleteByIds("link_code", linkCodeIds);
        deleteByIds("link_request", linkRequestIds);
        deleteByIds("guardian", guardianIds);
        deleteByIds("student", studentIds);
        deleteByIds("refresh_token", refreshTokenIds);
        deleteByIds("account", accountIds);
        deleteByIds("academy", academyIds);
        deleteByIds("notification_log", notificationLogIds);
        deleteByIds("run_position", runPositionIds);
        deleteByIds("audit_log", auditLogIds);
    }

    @Test
    @DisplayName("목표6 — notification_log 는 14일 초과 행만 지워지고 기한 안 행은 남는다")
    void 알림_로그는_14일_초과_행만_지워진다() {
        long inWindow = insertNotificationLog(now.minusDays(13));
        long outOfWindow = insertNotificationLog(now.minusDays(15));

        scheduler.cleanUp();

        assertThat(existsNotificationLog(inWindow)).as("기한 안 행은 남는다").isTrue();
        assertThat(existsNotificationLog(outOfWindow)).as("14일 초과 행은 지워진다").isFalse();
    }

    @Test
    @DisplayName("목표6 — run_position 은 90일 초과 행만 지워지고 기한 안 행은 남는다")
    void 위치_이력은_90일_초과_행만_지워진다() {
        long inWindow = insertRunPosition(now.minusDays(89));
        long outOfWindow = insertRunPosition(now.minusDays(91));

        scheduler.cleanUp();

        assertThat(existsRunPosition(inWindow)).as("기한 안 행은 남는다").isTrue();
        assertThat(existsRunPosition(outOfWindow)).as("90일 초과 행은 지워진다").isFalse();
    }

    @Test
    @DisplayName("목표6 — refresh_token 은 만료·폐기 후 30일이 지난 행만 지워진다")
    void 재발급_토큰은_만료_또는_폐기_후_30일이_지난_행만_지워진다() {
        long accountId = insertSystemAdminAccount();

        long revokedInWindow = insertRefreshToken(accountId, now.plusDays(10), now.minusDays(29));
        long revokedOutOfWindow = insertRefreshToken(accountId, now.plusDays(10), now.minusDays(31));
        long expiredInWindow = insertRefreshToken(accountId, now.minusDays(29), null);
        long expiredOutOfWindow = insertRefreshToken(accountId, now.minusDays(31), null);
        long stillValid = insertRefreshToken(accountId, now.plusDays(10), null);

        scheduler.cleanUp();

        assertThat(existsRefreshToken(revokedInWindow)).as("폐기 29일째는 남는다").isTrue();
        assertThat(existsRefreshToken(revokedOutOfWindow)).as("폐기 31일째는 지워진다").isFalse();
        assertThat(existsRefreshToken(expiredInWindow)).as("만료 29일째는 남는다").isTrue();
        assertThat(existsRefreshToken(expiredOutOfWindow)).as("만료 31일째는 지워진다").isFalse();
        assertThat(existsRefreshToken(stillValid)).as("아직 유효한 토큰은 대상이 아니다").isTrue();
    }

    @Test
    @DisplayName("목표6 — link_code·link_request 는 만료되면 다음 틱에 즉시 지워진다")
    void 연결_코드와_연결_요청은_만료되면_즉시_지워진다() {
        long academyId = insertAcademy();
        long guardianAccountId = insertParentAccount(academyId);
        long guardianId = insertGuardian(academyId, guardianAccountId);
        long studentId = insertStudent(academyId);

        long expiredRequest = insertLinkRequest(guardianId, studentId, now.minusMinutes(1));
        long validRequest = insertLinkRequest(guardianId, studentId, now.plusMinutes(30));
        long expiredCode = insertLinkCode(expiredRequest, now.minusMinutes(1));
        long validCode = insertLinkCode(validRequest, now.plusMinutes(30));

        scheduler.cleanUp();

        assertThat(existsLinkRequest(expiredRequest)).as("만료된 연결 요청은 지워진다").isFalse();
        assertThat(existsLinkRequest(validRequest)).as("유효한 연결 요청은 남는다").isTrue();
        assertThat(existsLinkCode(expiredCode)).as("만료된 연결 코드는 지워진다").isFalse();
        assertThat(existsLinkCode(validCode)).as("유효한 연결 코드는 남는다").isTrue();
    }

    @Test
    @DisplayName("목표3.3 — 무기한 보존 테이블(audit_log)은 정리 대상이 아니다")
    void 무기한_보존_테이블은_건드리지_않는다() {
        long veryOld = insertAuditLog(now.minusDays(400));

        scheduler.cleanUp();

        assertThat(existsAuditLog(veryOld)).as("audit_log 는 보존 정리와 무관하게 남아야 한다").isTrue();
    }

    @Test
    @DisplayName("목표7 — 한 번의 조회는 배치 상한을 넘지 않는다")
    void 보존_정리_후보_조회는_배치_상한을_넘지_않는다() {
        int total = RetentionPolicy.BATCH_SIZE + 1;
        List<Object[]> rows = new ArrayList<>(total);
        OffsetDateTime createdAt = now.minusDays(20);
        for (int i = 0; i < total; i++) {
            rows.add(new Object[] { createdAt });
        }
        jdbcTemplate.batchUpdate(insertNotificationLogSql(), rows.stream()
                .map(r -> bindNotificationLog((OffsetDateTime) r[0]))
                .toList());
        collectMarkedNotificationLogIds(createdAt);

        List<Long> firstRound = notificationLogRepository.findIdsForRetentionCleanup(
                retentionPolicy.notificationLogCutoff(now), Limit.of(RetentionPolicy.BATCH_SIZE));
        assertThat(firstRound).as("한 번의 조회는 상한(%s)을 넘지 않는다", RetentionPolicy.BATCH_SIZE)
                .hasSize(RetentionPolicy.BATCH_SIZE);

        scheduler.cleanUp();

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE id = ANY(?)",
                Integer.class, notificationLogIds.stream().mapToLong(Long::longValue).toArray());
        assertThat(remaining).as("상한보다 많이 심었어도 여러 회차에 걸쳐 전부 지워져야 한다").isZero();
    }

    // ---- notification_log ----

    private long insertNotificationLog(OffsetDateTime createdAt) {
        long id = jdbcTemplate.queryForObject(insertNotificationLogSql(), Long.class, bindNotificationLog(createdAt));
        notificationLogIds.add(id);
        return id;
    }

    private String insertNotificationLogSql() {
        return """
                INSERT INTO notification_log
                    (academy_id, recipient_account_id, recipient_name, recipient_role, type, title, body,
                     dedup_key, created_at)
                VALUES (-1, -1, 'P14T2', 'staff', 'boarding', 't', 'b', ?, ?)
                RETURNING id
                """;
    }

    private Object[] bindNotificationLog(OffsetDateTime createdAt) {
        return new Object[] { "p14t2-retention-" + java.util.UUID.randomUUID(), createdAt };
    }

    /** 배치 삽입은 생성된 id 를 돌려주지 않으므로, 마킹용 조건으로 방금 심은 id 를 다시 모은다. */
    private void collectMarkedNotificationLogIds(OffsetDateTime createdAt) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM notification_log WHERE academy_id = -1 AND created_at = ?", Long.class, createdAt);
        notificationLogIds.addAll(ids);
    }

    private boolean existsNotificationLog(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    // ---- run_position ----

    private long insertRunPosition(OffsetDateTime recordedAt) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at)
                VALUES (-1, 37.5, 127.0, ?, ?)
                RETURNING id
                """, Long.class, recordedAt, recordedAt);
        runPositionIds.add(id);
        return id;
    }

    private boolean existsRunPosition(long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM run_position WHERE id = ?", Integer.class,
                id);
        return count != null && count > 0;
    }

    // ---- refresh_token ----

    private long insertSystemAdminAccount() {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
                VALUES (NULL, ?, 'x', 'P14T2', '010-0000-0000', 'system_admin', 'active')
                RETURNING id
                """, Long.class, java.util.UUID.randomUUID().toString());
        accountIds.add(id);
        return id;
    }

    /** {@code revokedAt} 이 null 이면 만료 기준으로만 판정되는 토큰을 만든다. */
    private long insertRefreshToken(long accountId, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO refresh_token (account_id, token_hash, issued_at, expires_at, revoked_at)
                VALUES (?, ?, now(), ?, ?)
                RETURNING id
                """, Long.class, accountId, "p14t2-retention-" + java.util.UUID.randomUUID(), expiresAt, revokedAt);
        refreshTokenIds.add(id);
        return id;
    }

    private boolean existsRefreshToken(long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_token WHERE id = ?", Integer.class,
                id);
        return count != null && count > 0;
    }

    // ---- link_code / link_request ----

    private long insertAcademy() {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO academy (code, name, region, status)
                VALUES (?, 'P14T2보존정리시험학원', '서울', 'active')
                RETURNING id
                """, Long.class, shortUniqueCode("aca"));
        academyIds.add(id);
        return id;
    }

    private long insertParentAccount(long academyId) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
                VALUES (?, ?, 'x', 'P14T2보호자', '010-0000-0001', 'parent', 'active')
                RETURNING id
                """, Long.class, academyId, java.util.UUID.randomUUID().toString());
        accountIds.add(id);
        return id;
    }

    private long insertGuardian(long academyId, long accountId) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO guardian (academy_id, account_id, name, phone)
                VALUES (?, ?, 'P14T2보호자', '010-0000-0001')
                RETURNING id
                """, Long.class, academyId, accountId);
        guardianIds.add(id);
        return id;
    }

    private long insertStudent(long academyId) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO student (academy_id, name)
                VALUES (?, 'P14T2학생')
                RETURNING id
                """, Long.class, academyId);
        studentIds.add(id);
        return id;
    }

    private long insertLinkRequest(long guardianId, long studentId, OffsetDateTime expiresAt) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO link_request (guardian_id, student_id, requested_at, expires_at, status)
                VALUES (?, ?, now(), ?, 'pending')
                RETURNING id
                """, Long.class, guardianId, studentId, expiresAt);
        linkRequestIds.add(id);
        return id;
    }

    private long insertLinkCode(long linkRequestId, OffsetDateTime expiresAt) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO link_code (link_request_id, code, expires_at)
                VALUES (?, '123456', ?)
                RETURNING id
                """, Long.class, linkRequestId, expiresAt);
        linkCodeIds.add(id);
        return id;
    }

    private boolean existsLinkRequest(long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM link_request WHERE id = ?", Integer.class,
                id);
        return count != null && count > 0;
    }

    private boolean existsLinkCode(long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM link_code WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    // ---- audit_log ----

    private long insertAuditLog(OffsetDateTime occurredAt) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO audit_log (category, action, occurred_at)
                VALUES ('login', 'login_success', ?)
                RETURNING id
                """, Long.class, occurredAt);
        auditLogIds.add(id);
        return id;
    }

    private boolean existsAuditLog(long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** {@code academy.code} 는 varchar(32) 라 UUID 전체를 그대로 못 쓴다 — 접두사 + 앞 8자리로 줄인다. */
    private String shortUniqueCode(String prefix) {
        return "p14t2-" + prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private void deleteByIds(String table, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM " + table + " WHERE id = ANY(?)",
                (Object) ids.stream().mapToLong(Long::longValue).toArray());
    }
}
