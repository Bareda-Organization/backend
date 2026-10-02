package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

import src.backend.global.common.enums.Role;
import src.backend.notification.command.NotificationDraft;
import src.backend.notification.command.NotificationOutbox;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.event.NotificationAppendedEvent;
import src.backend.notification.repository.NotificationLogRepository;

/**
 * 아웃박스 묶음 적재(R46 T-6)와 같은 {@code dedup_key} 건너뜀(Ruling 622).
 *
 * <p>네이티브 {@code INSERT} 는 엔티티 매핑을 거치지 않아 {@code notification_log} 에 컬럼이 늘어도 문장이 따라가지 않는다
 * ({@code ddl-auto: validate} 는 INSERT 문 누락을 못 잡는다) — 그래서 엔티티로 저장한 행과 <b>모든 필드가 같은지</b>
 * 비교한다. {@code @Transactional} 을 쓰지 않는 이유는 실제 커밋 경로(아웃박스 MANDATORY · 커밋 뒤 이벤트)를 타기 위해서다.
 */
@SpringBootTest
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class NotificationOutboxAppendAllTest {

    private static final String KEY_PREFIX = "r46appendall:";

    private static final long ACADEMY_ID = 777_001L;

    @Autowired
    private NotificationOutbox notificationOutbox;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationEvents applicationEvents;

    @AfterEach
    void 적재한_행을_지운다() {
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE ?", KEY_PREFIX + "%");
    }

    @Test
    @DisplayName("묶음 적재한 행은 엔티티로 저장한 행과 모든 필드가 같다(popup · 기본 상태 포함)")
    void 묶음_적재한_행은_엔티티로_저장한_행과_같다() {
        NotificationDraft ordinary = draft("ordinary", NotificationType.RUN_STARTED, 11L, "학생", "3호차", 99L);
        NotificationDraft emergency = draft("emergency", NotificationType.EMERGENCY, null, null, null, null);

        커밋한다(() -> {
            notificationOutbox.appendAll(List.of(ordinary, emergency));
            assertThat(jdbcTemplate.queryForList("SELECT push_state || ':' || push_attempts || ':' || acked FROM "
                    + "notification_log WHERE dedup_key LIKE ?", String.class, KEY_PREFIX + "%"))
                    .as("커밋 전 초기 상태 — 발송 대기 · 시도 0 · 미확인").containsExactly("pending:0:false", "pending:0:false");
            return List.of();
        });

        for (NotificationDraft draft : List.of(ordinary, emergency)) {
            NotificationLog appended = 행(draft.dedupKey());
            NotificationLog expected = notificationLogRepository.saveAndFlush(NotificationLog.forOutbox(
                    draft.academyId(), draft.recipientAccountId(), draft.recipientName(), draft.recipientRole(),
                    draft.type(), draft.title(), draft.body(), draft.dedupKey() + ":entity", appended.getCreatedAt(),
                    draft.studentId(), draft.studentName(), draft.busNo(), draft.runId()));
            // 발송 상태 필드는 커밋 뒤 비동기 발송이 곧 바꿔 비교에서 뺀다 — 초기 상태는 아래 트랜잭션 안에서 따로 본다
            assertThat(appended).usingRecursiveComparison()
                    .ignoringFields("id", "dedupKey", "pushState", "pushAttempts", "lastAttemptAt", "sentAt", "failReason")
                    .as("엔티티로 저장한 행과 달라지면 INSERT 문이 엔티티 컬럼을 놓친 것이다 — %s", draft.type())
                    .isEqualTo(expected);
        }
        assertThat(행(emergency.dedupKey()).isPopup()).as("비상 종류는 팝업").isTrue();
    }

    @Test
    @DisplayName("같은 dedup_key 는 예외 없이 건너뛴다 — 묶음 안 중복도, 이미 있는 행도, 새 행에만 발송 이벤트가 나간다")
    void 같은_dedup_key_는_건너뛴다() {
        NotificationDraft first = draft("dup", NotificationType.RUN_STARTED, null, null, null, null);
        NotificationDraft other = draft("other", NotificationType.RUN_STARTED, null, null, null, null);

        List<Long> firstRound = 커밋한다(() -> notificationOutbox.appendAll(List.of(first, first, other)));
        List<Long> secondRound = 커밋한다(() -> notificationOutbox.appendAll(List.of(first, other)));

        assertThat(firstRound).as("묶음 안 같은 키는 한 행만 새로 생긴다").hasSize(2);
        assertThat(secondRound).as("이미 있는 키는 새 행이 없다").isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_log WHERE dedup_key LIKE ?",
                Integer.class, KEY_PREFIX + "%")).isEqualTo(2);
        assertThat(applicationEvents.stream(NotificationAppendedEvent.class).count())
                .as("발송 이벤트는 새로 생긴 행(2건)에만 나간다 — 건너뛴 행은 이미 발송 절차에 올라 있다").isEqualTo(2);
    }

    @Test
    @DisplayName("이미 있는 키가 섞여 있어도 같은 트랜잭션의 나머지 적재와 상태 변경은 커밋된다(예전에는 통째로 롤백)")
    void 이미_있는_키가_섞여도_같은_트랜잭션은_커밋된다() {
        NotificationDraft existing = draft("exists", NotificationType.RUN_STARTED, null, null, null, null);
        커밋한다(() -> notificationOutbox.appendAll(List.of(existing)));
        NotificationDraft fresh = draft("fresh", NotificationType.RUN_STARTED, null, null, null, null);

        // 같은 트랜잭션의 "상태 변경" 자리 — 예전에는 UNIQUE 위반이 트랜잭션을 중단 상태로 만들어 이것까지 되돌렸다
        커밋한다(() -> {
            jdbcTemplate.update("UPDATE notification_log SET title = '변경됨' WHERE dedup_key = ?", existing.dedupKey());
            notificationOutbox.appendAll(List.of(existing, fresh));
            return List.of();
        });

        assertThat(행(existing.dedupKey()).getTitle()).as("같은 트랜잭션의 상태 변경이 유지된다").isEqualTo("변경됨");
        assertThat(행(fresh.dedupKey())).as("섞여 있던 새 행도 적재된다").isNotNull();
    }

    /**
     * BR-355 — 키 충돌이 조용한 알림 소실이 된 뒤(Ruling 622) 유일한 진단 단서가 경고 한 줄이다. 그 줄은 묶음의 첫 초안이 아니라
     * <b>실제로 건너뛴 키</b>를 적어야 한다(묶음 안 같은 키는 둘째 이후가 건너뛴 쪽이다).
     */
    @Test
    @DisplayName("건너뜀 경고는 묶음 첫 초안의 키가 아니라 실제로 건너뛴 키를 적는다")
    void 건너뜀_경고는_건너뛴_키를_적는다(CapturedOutput output) {
        NotificationDraft existing = draft("skipped-existing", NotificationType.RUN_STARTED, null, null, null, null);
        NotificationDraft fresh = draft("fresh-first", NotificationType.RUN_STARTED, null, null, null, null);
        NotificationDraft twice = draft("skipped-twice", NotificationType.RUN_STARTED, null, null, null, null);
        커밋한다(() -> notificationOutbox.appendAll(List.of(existing)));

        커밋한다(() -> notificationOutbox.appendAll(List.of(fresh, existing, twice, twice)));

        List<String> warnings = output.getAll().lines().filter(line -> line.contains("[outbox] 같은 dedup_key")).toList();
        assertThat(warnings).as("건너뜀 경고는 한 줄").hasSize(1);
        assertThat(warnings.get(0)).as("이미 있던 키와 묶음 안 중복 키 둘 다 건너뛴 쪽으로 적힌다")
                .contains("2건").contains(existing.dedupKey()).contains(twice.dedupKey())
                .as("새로 적재된 첫 초안의 키는 건너뛴 키가 아니다").doesNotContain(fresh.dedupKey());
    }

    @Test
    @DisplayName("묶음 적재의 SQL 문장 수는 행 수와 무관하게 1이다")
    void 묶음_적재는_문장_1개다() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        long[] counted = new long[2];
        for (int round = 0; round < 2; round++) {
            int rows = round == 0 ? 3 : 30;
            List<NotificationDraft> drafts = new java.util.ArrayList<>();
            for (int i = 0; i < rows; i++) {
                drafts.add(draft("count" + round + "-" + i, NotificationType.RUN_STARTED, null, null, null, null));
            }
            int slot = round;
            await().atMost(Duration.ofSeconds(10)).until(() -> 발송_대기_행_수() == 0); // 앞 회차의 비동기 발송이 통계에 얹히지 않게 한다
            커밋한다(() -> {
                long before = statistics.getPrepareStatementCount();
                notificationOutbox.appendAll(drafts);
                counted[slot] = statistics.getPrepareStatementCount() - before;
                return List.of();
            });
        }

        assertThat(counted).as("3행 · 30행 모두 한 문장").containsExactly(1, 1);
    }

    private int 발송_대기_행_수() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM notification_log WHERE dedup_key LIKE ? "
                + "AND push_state = 'pending'", Integer.class, KEY_PREFIX + "%");
    }

    private NotificationDraft draft(String suffix, NotificationType type, Long studentId, String studentName,
            String busNo, Long runId) {
        return new NotificationDraft(ACADEMY_ID, 7_001L, "수신자", Role.PARENT, type, "제목", "본문",
                KEY_PREFIX + suffix, studentId, studentName, busNo, runId);
    }

    private NotificationLog 행(String dedupKey) {
        return notificationLogRepository.findAll().stream()
                .filter(row -> dedupKey.equals(row.getDedupKey())).findFirst().orElseThrow();
    }

    private <T> T 커밋한다(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
