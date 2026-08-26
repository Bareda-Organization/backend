package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import src.backend.notification.command.NotificationDispatcher;
import src.backend.notification.domain.NotificationRetryPolicy;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.PushState;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.notification.scheduler.NotificationOutboxWorker;

/**
 * Phase 4 목표 3 — 시드의 미발송 행을 워커가 회수해 재발송하고, <b>이미 끝난 행은 건드리지 않는지</b>
 * 본다(ERD §5 의 부분 인덱스 {@code WHERE push_state='pending'} 가 가리키는 술어).
 *
 * <p>두 단언 중 뒤쪽이 이 클래스의 이유다. 회수 조건에서 {@code push_state='pending'} 이 빠진 워커도
 * "미발송분이 sent 로 바뀐다" 는 그대로 통과하는데, 그 워커는 <b>이미 보낸 알림 8건과 포기한 1건을
 * 함께 재발송</b>한다. 학부모는 어제 받은 하차 알림을 오늘 다시 받는다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 발송 상태 전이가 {@code REQUIRES_NEW} 라 테스트 트랜잭션
 * 안에서는 관측되지 않는다. 대신 시드 행을 {@link #시드_알림을_되돌린다()} 가 앞뒤로 되돌린다.
 */
@SpringBootTest
class NotificationOutboxWorkerTest {

    /** 시드의 유일한 미발송 행({@code V2__seed_data.sql} — {@code no_show}, 학부모 계정 6). */
    private static final long 시드_미발송_행 = 4L;

    /** 시드의 재시도 상한 초과 행 — 워커의 회수 대상 밖이다. */
    private static final long 시드_포기_행 = 9L;

    /** 이 클래스가 직접 심는 행의 {@code dedup_key} 접두 — 뒷정리가 이 값 하나로 자기 행만 지운다. */
    private static final String 픽스처_키_접두 = "p4t1worker:";

    /** 픽스처 행의 수신 계정 — 시드 {@code notification_log} 에 없는 값이다(이 테이블은 FK 부재). */
    private static final long 픽스처_계정 = 4005L;

    /** 시드에서 이미 발송이 끝난 행 8건 — 회수 대상 밖이다. */
    private static final List<Long> 시드_발송완료_행 = List.of(1L, 2L, 3L, 5L, 6L, 7L, 8L, 10L);

    @Autowired
    private NotificationOutboxWorker notificationOutboxWorker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledPostProcessor;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Autowired
    private NotificationRetryPolicy retryPolicy;

    @Autowired
    private NotificationDispatcher notificationDispatcher;

    @Autowired
    private Clock clock;

    /**
     * 시드 알림 10건의 <b>발송 상태를 전부</b> 시드 값으로 되돌린다.
     *
     * <p>미발송 행 하나만 되돌리면 부족하다. 같은 클래스의 다른 테스트가 {@code sweep()} 을 이미
     * 돌린 뒤라면 발송 완료 행의 {@code push_attempts} 가 그 실행으로 이미 올라가 있고, 그 값을
     * "이전" 으로 찍는 순간 <b>회수 조건이 빠진 워커도 통과한다</b> — 두 번째 실행은 백오프에 막혀
     * 더 올리지 못하기 때문이다. 음성 대조에서 실제로 그 형태로 변형이 살아남아 이 되돌리기를 넓혔다.
     *
     * <p>{@code last_attempt_at} 을 함께 비우는 것이 요점이다 — 그것이 남아 있으면 백오프가 워커를
     * 막아, 회수 조건을 검사하는 자리에 도달하지 못한다.
     */
    @BeforeEach
    @AfterEach
    void 시드_알림을_되돌린다() {
        jdbcTemplate.update("""
                UPDATE notification_log
                   SET push_state = 'sent', push_attempts = 1, last_attempt_at = NULL, fail_reason = NULL
                 WHERE id IN (1, 2, 3, 5, 6, 7, 8, 10)
                """);
        jdbcTemplate.update("""
                UPDATE notification_log
                   SET push_state = 'failed', push_attempts = 3, last_attempt_at = NULL, sent_at = NULL,
                       fail_reason = 'FCM 토큰 만료'
                 WHERE id = ?
                """, 시드_포기_행);
        jdbcTemplate.update("""
                UPDATE notification_log
                   SET push_state = 'pending', push_attempts = 0, last_attempt_at = NULL,
                       sent_at = NULL, fail_reason = NULL
                 WHERE id = ?
                """, 시드_미발송_행);
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE ?", 픽스처_키_접두 + "%");
    }

    /**
     * {@code @Scheduled} 가 실제로 <b>등록</b>됐는지 본다 — 애너테이션을 지워도 위 두 테스트는
     * {@code sweep()} 을 직접 부르므로 그대로 통과하고, 그때 안전망은 운영에서 영영 돌지 않는다.
     *
     * <p>실행 시각까지 기다리지 않는 이유는 테스트 실행 동안 최초 지연이 하루로 미뤄져 있기
     * 때문이다({@code build.gradle}) — 기다리면 배경 워커가 다른 테스트의 행을 집는다.
     */
    @Test
    void 아웃박스_워커가_스케줄러에_등록된다() {
        assertThat(scheduledPostProcessor.getScheduledTasks())
                .as("@Scheduled 가 등록되지 않으면 즉시 발송이 실패한 알림을 아무도 회수하지 않는다")
                .anyMatch(task -> task.getTask().toString().contains("NotificationOutboxWorker"));
    }

    @Test
    void 워커가_pending_행을_회수해_sent_로_전이시킨다() {
        notificationOutboxWorker.sweep();

        Map<String, Object> row = 알림_행(시드_미발송_행);
        assertThat(row.get("push_state"))
                .as("회수하지 못하면 즉시 발송이 실패한 알림은 영영 발송되지 않는다")
                .isEqualTo("sent");
        assertThat(row.get("sent_at"))
                .as("sent 인데 sent_at 이 비면 재발송 시점을 되짚을 수단이 부재하다")
                .isNotNull();
        assertThat((Integer) row.get("push_attempts"))
                .as("시도 횟수가 늘지 않으면 재시도 상한 판정이 영영 성립하지 않아 포기할 수단이 부재하다")
                .isEqualTo(1);
    }

    /**
     * 방금 시도한 행은 최소 간격이 지나기 전에는 <b>다시 집히지 않는다</b> — 선점 조건의
     * {@code last_attempt_at} 술어가 지키는 성질이고, {@code MIN_RETRY_INTERVAL} 이 0 이면 무너진다.
     *
     * <p><b>경합으로 재지 않는다.</b> 스레드 둘로 같은 행을 겨냥하는 단언
     * ({@code 같은_pending_행을_워커_둘이_집어도_발송은_1회다})도 이 상수가 0 이면 실패하지만
     * <b>매번은 아니다</b> — 두 스레드가 각자 자기 시계를 읽는 순서가 결과를 가르기 때문이다. 진 쪽이
     * 이긴 쪽보다 <b>먼저</b> 시계를 읽었으면 0 간격에서도 선점이 막혀 변형이 살아남는다(리뷰가 3회
     * 실행에 1회 생존을 실측). 여기서는 시도 시각을 픽스처가 못박고 배달을 <b>한 번만</b> 걸어,
     * 그 순서 자체를 없앤다.
     *
     * <p>P5-T2 의 {@code pg_stat_activity} 대기 기법은 이 자리에 맞지 않는다. 그쪽 비결정성은
     * "어느 층이 잡는가"(선검사 vs DB 제약)였고 상대가 잠금을 기다리는 것을 확인하면 층이 고정된다.
     * 여기 비결정성은 <b>두 스레드의 시계 읽기 순서</b>라 잠금 대기 상태가 그것을 말해 주지 않는다.
     * 게다가 선점 UPDATE 는 저장소 안에서 {@code REQUIRES_NEW} 로 열고 닫혀, 테스트가 그 트랜잭션을
     * 열어 둔 채 상대를 기다리게 할 지점 자체가 부재하다.
     */
    @Test
    void 방금_시도한_행은_최소_간격_전에는_다시_집히지_않는다() {
        long 대상 = 픽스처_행을_심는다("recent-attempt", "pending", 1, OffsetDateTime.now(clock));

        notificationDispatcher.dispatch(대상);

        Map<String, Object> row = 알림_행(대상);
        assertThat((Integer) row.get("push_attempts"))
                .as("최소 간격이 0 이면 방금 시도한 행을 곧바로 다시 집어, 죽은 단말 하나가 워커의 매 틱을 잡아먹는다")
                .isEqualTo(1);
        assertThat(row.get("push_state"))
                .as("다시 집혔다면 발송까지 뒤따라 상태가 옮겨졌을 것이다")
                .isEqualTo("pending");
    }

    /**
     * 회수 <b>질의</b>도 백오프 술어를 독자적으로 갖는지 본다 — 방금 시도한 행이 후보에 실리면 안 된다.
     *
     * <p>바로 위 단언과 겹쳐 보이지만 <b>보는 층이 다르다.</b> 선점 UPDATE 가 같은 조건을 독립으로
     * 갖고 있어, 질의에서만 조건을 지워도 선점이 대신 막아 위 단언은 통과한다(리뷰가 축 g 로 실측).
     * 그러면 워커는 매 틱마다 아직 기다려야 할 행 전부를 읽어 선점을 시도하고, 나중에 선점 쪽 조건을
     * 지우는 사람은 <b>남은 방어가 부재하다는 것을 모른 채</b> 지운다.
     */
    @Test
    void 방금_시도한_행은_회수_후보에서_빠진다() {
        long 대상 = 픽스처_행을_심는다("recent-candidate", "pending", 1, OffsetDateTime.now(clock));

        assertThat(회수_후보())
                .as("아직 기다려야 할 행이 후보에 실리면 백오프가 질의 단계에서 통째로 사라진 것이다")
                .doesNotContain(대상);
    }

    /**
     * {@code failed} 행은 <b>재시도 상한에 닿지 않았어도</b> 회수 대상 밖이다 — 지키는 것은
     * {@code push_state} 술어다.
     *
     * <p>시드의 {@code failed} 행(id=9)으로는 이것이 갈리지 않는다. 그 행은
     * {@code push_attempts=3} 으로 상한과 같아 {@code push_state} 조건을 지워도 <b>시도 횟수
     * 술어</b>에서 걸린다. 그래서 상한 미만인 {@code failed} 행을 직접 심어 두 술어를 가른다 —
     * 시드는 고치지 않는다.
     *
     * <p>상한 미만의 {@code failed} 는 지금 코드가 만들지 않지만, 운영자가 수동으로 포기시키거나
     * 상한을 낮추는 순간 생긴다. 그때 이 술어가 없으면 <b>포기한 알림이 되살아난다.</b>
     */
    @Test
    void 상한_미만이어도_failed_행은_회수_후보에서_빠진다() {
        long 대상 = 픽스처_행을_심는다("failed-below-limit", "failed", 1, null);

        assertThat(회수_후보())
                .as("포기한 알림이 다시 나가면 수신자는 실패로 끝난 통지를 뒤늦게 받는다")
                .doesNotContain(대상);
    }

    /**
     * 회수 <b>질의 자체</b>가 {@code pending} 행만 돌려주는지 본다 — ERD §5 의 부분 인덱스
     * ({@code WHERE push_state='pending'})가 가리키는 술어를 그대로 고정한다.
     *
     * <p>아래 {@code push_attempts 불변} 단언과 겹쳐 보이지만 <b>보는 자리가 다르다.</b> 발송 직전의
     * 선점 UPDATE 도 같은 조건을 갖고 있어, 회수 질의에서 조건을 지워도 선점이 대신 막아 그 단언은
     * 통과한다(음성 대조 실측). 그러면 워커는 매 틱마다 이미 끝난 행 전부를 읽어 선점을 시도하고,
     * 나중에 선점 조건까지 지우는 사람은 <b>남은 방어가 하나도 없다는 것을 모른 채</b> 지운다.
     */
    @Test
    void 회수_후보_질의는_pending_행만_돌려준다() {
        List<Long> candidates = 회수_후보();

        assertThat(candidates)
                .as("시드의 미발송 행이 후보에 없으면 아래 두 단언은 빈 결과 위에서 통과한다")
                .contains(시드_미발송_행)
                .doesNotContainAnyElementsOf(시드_발송완료_행)
                .doesNotContain(시드_포기_행);
    }

    @Test
    void 워커는_sent_와_failed_행의_push_attempts_를_바꾸지_않는다() {
        Map<Long, Map<String, Object>> 이전 = 끝난_행들();

        notificationOutboxWorker.sweep();

        Map<Long, Map<String, Object>> 이후 = 끝난_행들();
        assertThat(이후)
                .as("회수 조건에서 push_state='pending' 이 빠지면 이미 보낸 알림 8건과 포기한 1건이 다시 나간다")
                .isEqualTo(이전);
    }

    /** 발송이 끝난 시드 행 9건의 시도 횟수·발송 시각 — 워커 전후로 같아야 한다. */
    private Map<Long, Map<String, Object>> 끝난_행들() {
        java.util.LinkedHashMap<Long, Map<String, Object>> snapshot = new java.util.LinkedHashMap<>();
        java.util.stream.Stream.concat(시드_발송완료_행.stream(), java.util.stream.Stream.of(시드_포기_행))
                .forEach(id -> snapshot.put(id, jdbcTemplate.queryForMap("""
                        SELECT push_state, push_attempts, sent_at FROM notification_log WHERE id = ?
                        """, id)));
        return snapshot;
    }

    /** 지금 시각 기준으로 워커가 집을 후보의 식별자 목록 — 워커가 쓰는 인자를 그대로 넘긴다. */
    private List<Long> 회수_후보() {
        return notificationLogRepository.findRetryCandidates(PushState.PENDING,
                        NotificationRetryPolicy.MAX_ATTEMPTS,
                        retryPolicy.attemptedBefore(OffsetDateTime.now(clock)), PageRequest.of(0, 100))
                .stream()
                .map(NotificationLog::getId)
                .toList();
    }

    /**
     * 이 클래스 전용 알림 행 하나를 심는다 — 시드를 고치지 않고 술어를 가르기 위한 재료다.
     *
     * @param lastAttemptAt 마지막 시도 시각. {@code null} 이면 아직 시도한 적이 없는 행이다
     */
    private long 픽스처_행을_심는다(String 키, String pushState, int pushAttempts,
            OffsetDateTime lastAttemptAt) {
        String dedupKey = 픽스처_키_접두 + 키;
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name,
                        recipient_role, type, title, body, push_state, push_attempts, last_attempt_at,
                        dedup_key, created_at)
                VALUES (1, ?, '조대기', 'parent', 'signup_decided', '가입 심사 안내',
                        '가입 심사 결과가 나왔습니다.', CAST(? AS varchar), ?, ?, ?, now())
                """, 픽스처_계정, pushState, pushAttempts, lastAttemptAt, dedupKey);
        return jdbcTemplate.queryForObject("SELECT id FROM notification_log WHERE dedup_key = ?",
                Long.class, dedupKey);
    }

    private Map<String, Object> 알림_행(long id) {
        return jdbcTemplate.queryForMap("""
                SELECT push_state, push_attempts, sent_at FROM notification_log WHERE id = ?
                """, id);
    }
}
