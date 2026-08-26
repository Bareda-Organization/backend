package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.notification.command.NotificationDispatcher;
import src.backend.notification.command.NotificationDraft;
import src.backend.notification.command.NotificationOutbox;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.push.spec.PushSender;

/**
 * Phase 4 목표 4 — 중복을 <b>두 축에서 따로</b> 막는지 본다.
 *
 * <p>두 축이 갈리는 것이 이 클래스의 이유다. {@code dedup_key} UNIQUE 는 <b>같은 알림이 두 행이
 * 되는 것</b>만 막는다 — 이미 적재된 <b>한 행</b>을 즉시 발송과 워커가 함께 집는 것은 INSERT 경합이
 * 아니라 선점 경합이라 UNIQUE 가 관여하지 않는다. 적재 축만 단언하면 학부모가 같은 알림을 두 번
 * 받는 경로가 그대로 열려 있고, 그 상태로도 행은 1건이라 통과한다.
 *
 * <p>발송 호출 수를 세려고 {@code app.push.sender} 로 구현체를 갈아끼운다 — 행의 {@code push_attempts}
 * 만 보면 "두 번 보내고 카운터는 한 번만 올린" 구현이 통과한다.
 */
@SpringBootTest(properties = "app.push.sender=counting")
class NotificationOutboxConcurrencyTest {

    private static final String DEDUP_KEY_PREFIX = "p4t1conc:";

    private static final long TIMEOUT_SECONDS = 30;

    /** 발송 호출을 세는 구현 — 두 실행이 같은 행을 집었는지는 이 값으로만 드러난다. */
    static final AtomicInteger 발송_횟수 = new AtomicInteger();

    @TestConfiguration
    static class 세는_발송 {

        @Bean
        PushSender countingPushSender() {
            return (PushMessage message) -> 발송_횟수.incrementAndGet();
        }
    }

    @Autowired
    private NotificationOutbox notificationOutbox;

    @Autowired
    private NotificationDispatcher notificationDispatcher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE ?", DEDUP_KEY_PREFIX + "%");
        발송_횟수.set(0);
    }

    /**
     * 적재 축 — UNIQUE 가 두 번째 INSERT 를 막고, 그 거부가 {@code 500} 이 아니라 업무 예외로 옮겨진다.
     *
     * <p>거부를 옮기지 않으면 사용자에게 "서버가 고장났다" 와 "이미 통지했다" 가 구별되지 않는다
     * ({@code AcademyStaffQuota} 와 같은 형태의 사고다).
     */
    @Test
    void 같은_dedup_key_로_두_스레드가_적재하면_행이_1건이다() throws Exception {
        String dedupKey = DEDUP_KEY_PREFIX + "append";
        CountDownLatch 출발 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> 첫째 = pool.submit(적재_호출(dedupKey, 출발));
            Future<Throwable> 둘째 = pool.submit(적재_호출(dedupKey, 출발));
            출발.countDown();

            // Arrays.asList 인 이유는 성공한 쪽의 결과가 null 이라 List.of 가 거부하기 때문이다.
            List<Throwable> 결과 = java.util.Arrays.asList(첫째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    둘째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertThat(적재된_행_수(dedupKey))
                    .as("두 행이 남으면 같은 통지가 두 번 나간다. 실제 결과=%s", 결과)
                    .isEqualTo(1);
            assertThat(결과.stream().filter(java.util.Objects::isNull).count())
                    .as("성공은 정확히 1건이어야 한다. 실제 결과=%s", 결과)
                    .isEqualTo(1);
            assertThat(진_쪽의_응답_상태(결과))
                    .as("DataIntegrityViolationException 이 그대로 올라오면 사용자에게 500 이 나간다")
                    .isEqualTo(HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 발송 축 — 같은 {@code pending} 행을 두 실행이 집어도 채널 호출은 1회다.
     *
     * <p>선점이 조건부 UPDATE 가 아니면 두 실행이 서로의 갱신을 보지 못한 채 <b>둘 다</b> 통과하고,
     * 학부모는 같은 알림을 두 번 받는다. 그때도 행은 여전히 1건이라 위 단언은 통과한다.
     */
    @Test
    void 같은_pending_행을_워커_둘이_집어도_발송은_1회다() throws Exception {
        long notificationId = transactionTemplate.execute(
                status -> notificationOutbox.append(초안(DEDUP_KEY_PREFIX + "dispatch")));
        발송_횟수.set(0);
        커밋_직후_발송분을_되돌린다(notificationId);

        CountDownLatch 출발 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> 첫째 = pool.submit(발송_호출(notificationId, 출발));
            Future<?> 둘째 = pool.submit(발송_호출(notificationId, 출발));
            출발.countDown();
            첫째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            둘째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(발송_횟수.get())
                    .as("두 실행이 같은 행을 집으면 수신자는 같은 알림을 두 번 받는다")
                    .isEqualTo(1);
            assertThat(시도_횟수(notificationId))
                    .as("시도 횟수가 2 면 선점이 아니라 둘 다 통과한 것이다")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 이미 발송이 끝난 행에 발송이 다시 걸려도 아무 일도 일어나지 않는다 — 선점 UPDATE 의
     * {@code push_state='pending'} 조건이 유일한 방어인 자리다.
     *
     * <p>워커 경로는 회수 질의가 {@code pending} 으로 좁혀 주지만, <b>즉시 발송은 행 식별자를 받아
     * 곧장 부른다</b> — 워커가 먼저 보낸 뒤 즉시 발송이 뒤늦게 도착하는 순서에서는 이 조건이
     * 없으면 그대로 두 번 나간다. 음성 대조에서 이 조건만 지운 변형이 다른 단언을 전부 통과했다.
     */
    @Test
    void 이미_sent_인_행은_다시_발송되지_않는다() {
        long notificationId = 발송완료_행을_심는다(DEDUP_KEY_PREFIX + "already-sent");
        발송_횟수.set(0);

        notificationDispatcher.dispatch(notificationId);

        assertThat(발송_횟수.get())
                .as("이미 보낸 알림이 다시 나가면 수신자는 같은 통지를 두 번 받는다")
                .isZero();
        assertThat(시도_횟수(notificationId))
                .as("시도 횟수가 늘면 선점이 성립한 것이고, 그러면 발송도 뒤따랐어야 한다")
                .isEqualTo(1);
    }

    /** 발송이 끝난 행 하나를 직접 심는다 — 아웃박스를 거치면 즉시 발송이 상태를 다시 건드린다. */
    private long 발송완료_행을_심는다(String dedupKey) {
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name,
                        recipient_role, type, title, body, push_state, push_attempts, sent_at, dedup_key,
                        created_at)
                VALUES (1, 8, '조대기', 'parent', 'signup_decided', '가입 심사 안내',
                        '가입 심사 결과가 나왔습니다.', 'sent', 1, now(), ?, now())
                """, dedupKey);
        return jdbcTemplate.queryForObject("SELECT id FROM notification_log WHERE dedup_key = ?", Long.class,
                dedupKey);
    }

    /**
     * 적재 직후 {@code AFTER_COMMIT} 이 이미 한 번 발송한 상태를 <b>적재 직후</b>로 되돌린다.
     *
     * <p>되돌리지 않으면 이 테스트가 보려는 "두 실행이 아직 아무도 집지 않은 행을 동시에 집는" 상황
     * 자체가 만들어지지 않는다 — 즉시 발송이 이미 {@code sent} 로 옮겨 둔 행이라 둘 다 선점에 실패한다.
     */
    private void 커밋_직후_발송분을_되돌린다(long notificationId) {
        jdbcTemplate.update("""
                UPDATE notification_log
                   SET push_state = 'pending', push_attempts = 0, last_attempt_at = NULL, sent_at = NULL
                 WHERE id = ?
                """, notificationId);
    }

    /** 적재를 한 번 시도하고 <b>실패 원인</b>을 돌려준다 — 성공이면 {@code null} 이다. */
    private Callable<Throwable> 적재_호출(String dedupKey, CountDownLatch 출발) {
        return () -> {
            출발.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                transactionTemplate.execute(status -> notificationOutbox.append(초안(dedupKey)));
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        };
    }

    private Runnable 발송_호출(long notificationId, CountDownLatch 출발) {
        return () -> {
            try {
                출발.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            notificationDispatcher.dispatch(notificationId);
        };
    }

    /** 진 쪽이 사용자에게 어떤 상태로 나가는지 — 업무 예외가 아니면 전역 핸들러가 500 으로 답한다. */
    private HttpStatus 진_쪽의_응답_상태(List<Throwable> 결과) {
        Throwable 진_쪽 = 결과.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (진_쪽 instanceof BusinessException business) {
            return business.getErrorCode().getStatus();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private NotificationDraft 초안(String dedupKey) {
        return new NotificationDraft(1L, 8L, "조대기", Role.PARENT, NotificationType.SIGNUP_DECIDED,
                "가입 심사 안내", "가입 심사 결과가 나왔습니다.", dedupKey);
    }

    private int 적재된_행_수(String dedupKey) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM notification_log WHERE dedup_key = ?",
                Integer.class, dedupKey);
    }

    private int 시도_횟수(long notificationId) {
        return jdbcTemplate.queryForObject("SELECT push_attempts FROM notification_log WHERE id = ?",
                Integer.class, notificationId);
    }
}
