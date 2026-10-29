package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.exception.event.ExceptionReportedEvent;
import src.backend.location.event.RunApproachingStopEvent;
import src.backend.request.event.ChangeRequestAutoRejectedEvent;
import src.backend.request.event.ChangeRequestDecidedEvent;
import src.backend.request.event.IntentChangedEvent;
import testsupport.db.NotificationInsertFailureInjector;

/**
 * 학부모 · 학생에게 알리는 리스너들의 수신자 집합과 중복 방지 키, 그리고 알림 적재가 실패하면 업무 트랜잭션도 함께 되돌려지는 것.
 *
 * <p>곧 도착 알림은 그 학생에 <b>지금 연결된</b> 보호자(연결 해제된 보호자 제외)와 학생 본인 계정에만 간다. 변경 신청 결과는 신청한 계정 한 명에게만
 * 간다. 실제 커밋을 남기지 않는 부분은 시드 학원 A 의 읽기 전용 사용이고, 적재 실패 시험만 실제 트랜잭션을 연다(트리거는 끝나면 지운다).
 */
@SpringBootTest
class FamilyRecipientListenersTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");

    private static final long ACADEMY_A = 1L;

    private static final long STUDENT_1 = 1L;

    private static final long REQUESTER_PARENT = 5L;

    private static final String MARK_PREFIX = "family-listener-";

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void 뒷정리한다() {
        NotificationInsertFailureInjector.clear(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE 'approaching:991:%' OR dedup_key LIKE 'change_decided:991:%'");
    }

    @Test
    void 곧_도착_알림은_지금_연결된_보호자와_학생_본인에게_수신자별_키로_적재된다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                publisher.publishEvent(new RunApproachingStopEvent(991L, ACADEMY_A, STUDENT_1, 992L, AT)));

        Map<Long, String> rows = rows("arrive", "approaching:991:%");
        Map<Long, String> expected = new HashMap<>();
        jdbcTemplate.query("""
                SELECT g.account_id FROM guardian_student gs JOIN guardian g ON g.id = gs.guardian_id
                 WHERE gs.student_id = ? AND gs.unlinked_at IS NULL AND g.academy_id = ?
                """, rs -> {
                    expected.put(rs.getLong(1), "approaching:991:992:" + STUDENT_1 + ":parent:" + rs.getLong(1));
                }, STUDENT_1, ACADEMY_A);
        jdbcTemplate.query("SELECT account_id FROM student WHERE id = ? AND account_id IS NOT NULL", rs -> {
            expected.put(rs.getLong(1), "approaching:991:992:" + STUDENT_1 + ":student");
        }, STUDENT_1);
        assertThat(expected).as("시드 학생 1 에게 연결된 보호자가 있어야 이 시험이 의미가 있다").isNotEmpty();
        assertThat(rows).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void 변경_신청_결과는_신청한_계정_한_명에게_적재된다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            publisher.publishEvent(new ChangeRequestDecidedEvent(ACADEMY_A, 77L, 991L, STUDENT_1, REQUESTER_PARENT, true,
                    null, AT));
        });

        assertThat(rows("change_decided", "change_decided:991:%")).containsExactlyEntriesOf(
                Map.of(REQUESTER_PARENT, "change_decided:991:77:" + AT));
    }

    @Test
    void 변경_신청_자동_거절도_신청한_계정_한_명에게_같은_키_형식으로_적재된다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                publisher.publishEvent(new ChangeRequestAutoRejectedEvent(ACADEMY_A, 78L, 991L, STUDENT_1,
                        REQUESTER_PARENT, AT)));

        assertThat(rows("change_decided", "change_decided:991:%")).containsExactlyEntriesOf(
                Map.of(REQUESTER_PARENT, "change_decided:991:78:" + AT));
    }

    @Test
    void 신청한_계정이_없으면_아무것도_적재하지_않는다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                publisher.publishEvent(new ChangeRequestDecidedEvent(ACADEMY_A, 79L, 991L, STUDENT_1, 99_999_999L, false,
                        "사유", AT)));

        assertThat(rows("change_decided", "change_decided:991:%")).isEmpty();
    }

    @Test
    void 관계자_알림_적재가_실패하면_같은_트랜잭션의_업무_변경도_되돌려진다() {
        String before = jdbcTemplate.queryForObject("SELECT name FROM account WHERE id = 2", String.class);
        NotificationInsertFailureInjector.failFor(jdbcTemplate, ACADEMY_A);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbcTemplate.update("UPDATE account SET name = ? WHERE id = 2", MARK_PREFIX + "표식");
            publisher.publishEvent(new ExceptionReportedEvent(66L, ACADEMY_A, 991L, AT));
        })).hasMessageContaining("injected notification failure");

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM account WHERE id = 2", String.class))
                .as("알림 적재 실패가 같은 트랜잭션의 상태 변경을 되돌린다").isEqualTo(before);
    }

    @Test
    void 탑승_의사_변경_적재가_실패해도_같은_트랜잭션의_업무_변경이_함께_되돌려진다() {
        String before = jdbcTemplate.queryForObject("SELECT name FROM account WHERE id = 2", String.class);
        NotificationInsertFailureInjector.failFor(jdbcTemplate, ACADEMY_A);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbcTemplate.update("UPDATE account SET name = ? WHERE id = 2", MARK_PREFIX + "표식");
            publisher.publishEvent(new IntentChangedEvent(991L, ACADEMY_A, STUDENT_1, false, AT));
        })).hasMessageContaining("injected notification failure");

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM account WHERE id = 2", String.class)).isEqualTo(before);
    }

    private Map<Long, String> rows(String type, String keyPattern) {
        Map<Long, String> byRecipient = new HashMap<>();
        jdbcTemplate.query("SELECT recipient_account_id, dedup_key FROM notification_log WHERE type = ? AND dedup_key LIKE ?",
                rs -> {
                    byRecipient.put(rs.getLong(1), rs.getString(2));
                }, type, keyPattern);
        return byRecipient;
    }
}
