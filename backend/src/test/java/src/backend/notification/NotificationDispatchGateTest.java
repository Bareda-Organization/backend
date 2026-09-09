package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.command.NotificationDispatcher;
import src.backend.notification.entity.NotificationSetting;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.push.spec.PushSender;
import src.backend.notification.repository.NotificationSettingRepository;

/**
 * 알림 설정이 발송 경로를 어떻게 가르는지 본다(Phase 12 목표 8·9) — 판정 값 자체({@code isEnabledFor})는
 * {@link NotificationSettingTest} 가 이미 고정했고, 여기서는 그 값이 {@link NotificationDispatcher} 의
 * 실제 발송 경로에서 <b>레코드는 항상 남기고 푸시 채널만 막는지</b>를 본다.
 *
 * <p>{@code @Transactional} 을 붙이지 않는다 — 발송 상태 전이가 전부 {@code REQUIRES_NEW} 라
 * 테스트 트랜잭션 안에 있으면 관측되지 않는다({@link NotificationOutboxWorkerTest} 와 같은 이유).
 * 대신 뒷정리를 {@code dedup_key} 접두와 계정 로그인 접두로 직접 지운다.
 *
 * <p>{@code account_id} 를 임의의 정수로 쓰지 않는다 — {@code notification_setting.account_id} 는
 * {@code account(id)} 에 FK 가 걸려 있어({@code V1__init_schema.sql}), 존재하지 않는 계정으로
 * 설정 행을 만들면 제약 위반으로 즉시 실패한다. {@code notification_log.recipient_account_id} 는
 * FK 가 없지만, 같은 계정을 그대로 재사용해 시나리오를 실제 계정 하나로 일관되게 유지한다.
 */
@SpringBootTest(properties = "app.push.sender=counting")
class NotificationDispatchGateTest {

    private static final String DEDUP_KEY_PREFIX = "p12t1gate:";

    private static final String LOGIN_ID_PREFIX = "p12t1gate";

    /** 발송 호출을 세는 값 — 설정이 막았는지 실제로 채널까지 갔는지는 이 값으로만 갈린다. */
    static final AtomicInteger 발송_횟수 = new AtomicInteger();

    @TestConfiguration
    static class 세는_발송 {

        @Bean
        PushSender countingPushSender() {
            return (PushMessage message) -> 발송_횟수.incrementAndGet();
        }
    }

    @Autowired
    private NotificationDispatcher notificationDispatcher;

    @Autowired
    private NotificationSettingRepository notificationSettingRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE ?", DEDUP_KEY_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM account WHERE login_id LIKE ?", LOGIN_ID_PREFIX + "%");
        발송_횟수.set(0);
    }

    /**
     * 목표 8 의 부정 사례 — 도착 알림 설정을 끄면 그 종류의 알림은 푸시가 나가지 않지만,
     * {@code notification_log} 행은 {@code skipped} 로 여전히 남는다(사라지지 않는다).
     *
     * <p>{@code push_attempts} 가 그대로 0 인지도 본다 — {@code markSkipped} 는 {@code claim} 을
     * 거치지 않는다는 판단 근거를 그대로 고정한다(발송 시도 자체가 없었다).
     */
    @Test
    @DisplayName("목표8 — 도착 알림 설정을 끄면 푸시는 막히지만 로그 행은 skipped 로 남는다")
    void 설정이_꺼져_있으면_푸시는_막히고_로그_행은_남는다() {
        long accountId = 계정을_만든다("off");
        설정을_저장한다(accountId, false, true, true);
        long logId = 알림_행을_심는다(accountId, NotificationType.ARRIVE, "off");

        notificationDispatcher.dispatch(logId);

        Map<String, Object> row = 알림_행(logId);
        assertThat(row)
                .as("설정이 꺼져도 기록은 남아야 한다 — 사라지면 '보냈는지 안 보냈는지' 를 되짚을 수단이 없다")
                .isNotNull();
        assertThat(row.get("push_state"))
                .as("설정이 꺼진 알림은 skipped 로 남아야 발송 시도가 없었음이 구분된다")
                .isEqualTo("skipped");
        assertThat((Integer) row.get("push_attempts"))
                .as("시도 자체가 없었으니 push_attempts 는 그대로 0이어야 한다")
                .isEqualTo(0);
        assertThat(row.get("sent_at")).isNull();
        assertThat(발송_횟수.get())
                .as("설정이 꺼졌는데 채널이 호출되면 수신자가 원치 않는 푸시를 받는다")
                .isZero();
    }

    /**
     * 위 부정 사례의 대응 양성 사례 — 설정을 켜면 같은 종류의 알림이 정상적으로 발송된다.
     * 이 사례가 없으면 위 단언은 "항상 skipped 로 만드는 결함" 이 섞여도 통과한다.
     */
    @Test
    @DisplayName("목표8 — 도착 알림 설정을 켜면 같은 종류의 알림이 정상적으로 발송된다")
    void 설정이_켜져_있으면_같은_종류의_알림은_정상적으로_발송된다() {
        long accountId = 계정을_만든다("on");
        설정을_저장한다(accountId, true, true, true);
        long logId = 알림_행을_심는다(accountId, NotificationType.ARRIVE, "on");

        notificationDispatcher.dispatch(logId);

        Map<String, Object> row = 알림_행(logId);
        assertThat(row.get("push_state")).isEqualTo("sent");
        assertThat((Integer) row.get("push_attempts")).isEqualTo(1);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(발송_횟수.get()).isEqualTo(1);
    }

    /**
     * 설정 행이 아예 없는 계정(자가 치유가 아직 일어나지 않은 상태)도 켜진 것으로 봐야 한다 —
     * {@code NotificationDispatcher.isPushEnabled} 의 {@code .orElse(true)} 분기를 직접 겨냥한다.
     * 이 분기가 없으면(또는 반대로 뒤집히면) 이 테이블에 행이 하나도 없는 지금, 기존 알림이
     * 전부 조용히 막힌다.
     */
    @Test
    @DisplayName("목표8 — 설정 행이 아예 없는 계정도 기본값(on)으로 발송된다")
    void 설정_행이_없는_계정도_기본값_on_으로_발송된다() {
        long accountId = 계정을_만든다("norow");
        assertThat(notificationSettingRepository.findById(accountId)).as("사전 조건 — 설정 행이 없다").isEmpty();
        long logId = 알림_행을_심는다(accountId, NotificationType.NO_SHOW, "norow");

        notificationDispatcher.dispatch(logId);

        assertThat(알림_행(logId).get("push_state")).isEqualTo("sent");
        assertThat(발송_횟수.get()).isEqualTo(1);
    }

    /**
     * 목표 9 — 지연 알림은 설정 항목 자체가 없어(NTF-07) 3종 토글을 전부 꺼도 항상 발송돼야 한다.
     * {@link NotificationSettingTest} 가 판정값을 이미 고정했으니, 여기서는 그 값이 실제 발송
     * 경로에서도 그대로 지켜지는지를 본다.
     */
    @Test
    @DisplayName("목표9 — 지연 알림은 3종 토글을 전부 꺼도 항상 발송된다")
    void 지연_알림은_3종_토글을_전부_꺼도_항상_발송된다() {
        long accountId = 계정을_만든다("delay");
        설정을_저장한다(accountId, false, false, false);
        long logId = 알림_행을_심는다(accountId, NotificationType.DELAY, "delay");

        notificationDispatcher.dispatch(logId);

        assertThat(알림_행(logId).get("push_state"))
                .as("지연 알림은 설정 대상 밖이라 토글이 전부 꺼져도 막히면 안 된다")
                .isEqualTo("sent");
        assertThat(발송_횟수.get()).isEqualTo(1);
    }

    /** 비상 알림도 같은 이유(NTF-07)로 항상 발송돼야 한다 — 지연과 별개 종류라 따로 겨냥한다. */
    @Test
    @DisplayName("목표9 — 비상 알림도 3종 토글을 전부 꺼도 항상 발송된다")
    void 비상_알림도_3종_토글을_전부_꺼도_항상_발송된다() {
        long accountId = 계정을_만든다("emrg");
        설정을_저장한다(accountId, false, false, false);
        long logId = 알림_행을_심는다(accountId, NotificationType.EMERGENCY, "emrg");

        notificationDispatcher.dispatch(logId);

        assertThat(알림_행(logId).get("push_state")).isEqualTo("sent");
        assertThat(발송_횟수.get()).isEqualTo(1);
    }

    private long 계정을_만든다(String suffix) {
        String loginId = LOGIN_ID_PREFIX + suffix + "-" + System.nanoTime();
        return accountRepository.save(Account.forSignup(1L, loginId, "{noop}password", "게이트시험",
                "010-7100-0001", null, Role.PARENT)).getId();
    }

    private void 설정을_저장한다(long accountId, boolean arrive, boolean boarding, boolean noShow) {
        NotificationSetting setting = NotificationSetting.forAccount(accountId, OffsetDateTime.now());
        setting.changeSettings(arrive, boarding, noShow, OffsetDateTime.now());
        notificationSettingRepository.save(setting);
    }

    private long 알림_행을_심는다(long accountId, NotificationType type, String suffix) {
        String dedupKey = DEDUP_KEY_PREFIX + suffix + "-" + System.nanoTime();
        String dbType = type.name().toLowerCase(java.util.Locale.ROOT);
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name,
                        recipient_role, type, title, body, push_state, push_attempts, dedup_key, created_at)
                VALUES (1, ?, '게이트시험', 'parent', CAST(? AS varchar), '시험 제목', '시험 본문',
                        'pending', 0, ?, now())
                """, accountId, dbType, dedupKey);
        return jdbcTemplate.queryForObject("SELECT id FROM notification_log WHERE dedup_key = ?",
                Long.class, dedupKey);
    }

    private Map<String, Object> 알림_행(long id) {
        return jdbcTemplate.queryForMap("""
                SELECT push_state, push_attempts, sent_at FROM notification_log WHERE id = ?
                """, id);
    }
}
