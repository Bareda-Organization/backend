package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import src.backend.notification.entity.NotificationSetting;
import src.backend.notification.entity.NotificationType;

/**
 * {@link NotificationSetting} 의 <b>판정 성질</b>을 고정한다(Phase 12 목표 8·9) — DB·Spring 컨텍스트
 * 없이 순수 엔티티만으로 갈리는 것은 여기서 보고, 발송 경로 전체(끄면 로그는 남고 푸시만 막히는지)는
 * {@link NotificationDispatchGateTest} 가 본다. {@link NotificationRetryPolicyTest} 와 같은 축
 * 분리다.
 */
class NotificationSettingTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-02T09:00:00+09:00");

    @Test
    void forAccount_은_3종_토글을_전부_켠_채로_만든다() {
        NotificationSetting setting = NotificationSetting.forAccount(1L, NOW);

        assertThat(setting.isArrive())
                .as("설정 행이 아직 없는 계정에 기본값 off 를 심으면 기존 알림이 전부 죽는다")
                .isTrue();
        assertThat(setting.isBoarding()).isTrue();
        assertThat(setting.isNoShow()).isTrue();
    }

    @Test
    void arrive_토글은_ARRIVE_종류에만_적용된다() {
        NotificationSetting setting = NotificationSetting.forAccount(1L, NOW);
        setting.changeSettings(false, true, true, NOW);

        assertThat(setting.isEnabledFor(NotificationType.ARRIVE))
                .as("도착 토글을 껐으면 도착 알림은 판정에서 막혀야 한다")
                .isFalse();
        assertThat(setting.isEnabledFor(NotificationType.BOARDING))
                .as("도착 토글은 승차 알림에 영향을 주면 안 된다 — 서로 다른 축이다")
                .isTrue();
    }

    /**
     * {@code boarding} 하나가 승차·하차·운행 시작 3종을 함께 묶는다(API_SPEC §3.14
     * "등하원(승차·하차·운행 시작) 알림"). 3종 전부를 각각 대조해야 "이 중 하나가 그룹에서
     * 빠졌다" 는 사고를 이 자리에서 잡는다.
     */
    @Test
    void boarding_토글은_승차_하차_운행시작_3종을_함께_묶는다() {
        NotificationSetting setting = NotificationSetting.forAccount(1L, NOW);
        setting.changeSettings(true, false, true, NOW);

        assertThat(setting.isEnabledFor(NotificationType.BOARDING)).isFalse();
        assertThat(setting.isEnabledFor(NotificationType.ALIGHTING)).isFalse();
        assertThat(setting.isEnabledFor(NotificationType.RUN_STARTED)).isFalse();
    }

    @Test
    void no_show_토글은_NO_SHOW_종류에만_적용된다() {
        NotificationSetting setting = NotificationSetting.forAccount(1L, NOW);
        setting.changeSettings(true, true, false, NOW);

        assertThat(setting.isEnabledFor(NotificationType.NO_SHOW)).isFalse();
        assertThat(setting.isEnabledFor(NotificationType.ARRIVE))
                .as("미승차 토글은 도착 알림에 영향을 주면 안 된다")
                .isTrue();
    }

    /**
     * 목표 9 의 핵심 — 지연·비상 2종은 <b>설정 항목 자체가 없다</b>(NTF-07). 3종 토글을 전부 꺼도
     * 이 종류들은 항상 켜진 것으로 판정돼야, {@code NotificationDispatcher} 가 이 값만 보고도
     * 절대 막지 않는다.
     */
    @Test
    void 지연_비상_알림은_3종_토글을_전부_꺼도_항상_발송_판정이다() {
        NotificationSetting setting = NotificationSetting.forAccount(1L, NOW);
        setting.changeSettings(false, false, false, NOW);

        assertThat(setting.isEnabledFor(NotificationType.DELAY))
                .as("지연 알림은 설정 항목 자체가 부재 — 항상 발송(NTF-07)")
                .isTrue();
        assertThat(setting.isEnabledFor(NotificationType.EMERGENCY)).isTrue();
        assertThat(setting.isEnabledFor(NotificationType.EMERGENCY_CANCELED)).isTrue();
    }

    /**
     * 설정 대상 3종 밖의 나머지(가입 승인 결과 등)도 같은 이유(토글 자체가 미정의)로 항상
     * 발송이어야 한다 — {@code isEnabledFor} 가 두 그룹(지연·비상 vs 나머지)을 구분하지 않는다는
     * 판단 근거를 그대로 고정한다.
     */
    @Test
    void 설정_대상_밖의_나머지_알림도_토글과_무관하게_항상_발송_판정이다() {
        NotificationSetting setting = NotificationSetting.forAccount(1L, NOW);
        setting.changeSettings(false, false, false, NOW);

        assertThat(setting.isEnabledFor(NotificationType.SIGNUP_DECIDED)).isTrue();
        assertThat(setting.isEnabledFor(NotificationType.CHANGE_DECIDED)).isTrue();
        assertThat(setting.isEnabledFor(NotificationType.NO_SHOW_ESCALATED)).isTrue();
    }
}
