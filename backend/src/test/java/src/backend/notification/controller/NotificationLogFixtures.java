package src.backend.notification.controller;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.entity.NotificationType;

/**
 * 알림 목록·읽음 처리 시험(Phase 12 T2, goal 1~6)이 쓰는 실제 행 — 학원·계정은
 * {@code ExceptionReportFixtures} 와 같은 축(정상 경로 팩토리)이지만, {@code notification_log} 는
 * {@link JdbcTemplate} 로 직접 심는다. {@link src.backend.notification.entity.NotificationLog#forOutbox}
 * 가 {@code student_id}·{@code student_name}·{@code popup}·{@code created_at}·{@code sent_at}·
 * {@code read_at} 을 인자로 받지 않아(아웃박스 적재 시점엔 알 수 없거나 이후 절차가 채우는 값들이라)
 * 시험이 이 필드들을 직접 통제하려면 이 경로가 유일하다({@code SignupDecidedFixture} 뒷정리 절의 같은
 * 근거 — 알림 행은 계정을 FK 로 참조하지 않는다, ERD §4.2).
 */
public class NotificationLogFixtures {

    private static final String ACADEMY_NAME = "알림목록시험학원";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

    private final AccountRepository accountRepository;

    private final JdbcTemplate jdbcTemplate;

    public NotificationLogFixtures(AcademyRepository academyRepository, AccountRepository accountRepository,
            JdbcTemplate jdbcTemplate) {
        this.academyRepository = academyRepository;
        this.accountRepository = accountRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public long academy() {
        String code = "P12T2" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, ACADEMY_NAME, "서울", null, null)).getId();
    }

    /** 알림 수신 계정 1명 — 역할은 시험 목적상 무엇이든 상관없다(§3.12 는 전 역할 공통). */
    public long account(long academyId, String name, Role role) {
        Account account = accountRepository.save(Account.forSignup(academyId, "알림수신" + SEQUENCE.incrementAndGet(),
                "x", name, "010-0000-0000", null, role));
        return account.getId();
    }

    /**
     * 알림 로그 1건을 직접 심는다 — {@code readAt} 이 {@code null} 이면 미읽음이다. {@code studentId}·
     * {@code studentName} 은 둘 다 {@code null} 을 허용한다(§4.13 예외 보고 등 학생과 무관한 알림).
     *
     * @return 심은 행의 id
     */
    public long notification(long academyId, long recipientAccountId, String recipientName, Role recipientRole,
            Long studentId, String studentName, NotificationType type, String title, String body, boolean popup,
            OffsetDateTime createdAt, OffsetDateTime sentAt, OffsetDateTime readAt) {
        String dedupKey = "p12t2-" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name, recipient_role,
                        student_id, student_name, type, title, body, popup, push_state, dedup_key, created_at,
                        sent_at, read_at)
                VALUES (?, ?, ?, CAST(? AS varchar), ?, ?, CAST(? AS varchar), ?, ?, ?, 'sent', ?, ?, ?, ?)
                """, academyId, recipientAccountId, recipientName, recipientRole.name(), studentId, studentName,
                type.name().toLowerCase(java.util.Locale.ROOT), title, body, popup, dedupKey, createdAt, sentAt,
                readAt);
        return jdbcTemplate.queryForObject("SELECT id FROM notification_log WHERE dedup_key = ?", Long.class,
                dedupKey);
    }
}
