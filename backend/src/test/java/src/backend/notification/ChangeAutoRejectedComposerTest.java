package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import src.backend.notification.domain.impl.ChangeAutoRejectedComposer;
import src.backend.notification.domain.impl.ChangeAutoRejectedSubject;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 변경 요청 자동 거절 알림 문구(API_SPEC §1.6·§9.7 {@code change_decided}) — 자녀 이름을 싣는지 본다
 * (ATT-03, R17-T3 목표 1). {@link RiderStatusChangedComposerTest} 와 같은 근거로 Spring 컨텍스트를
 * 띄우지 않는다.
 */
class ChangeAutoRejectedComposerTest {

    private final ChangeAutoRejectedComposer composer = new ChangeAutoRejectedComposer();

    @Test
    void 자동_거절_문구에는_자녀_이름이_실린다() {
        NotificationMessage message = composer.compose(new ChangeAutoRejectedSubject("김민준"));

        assertThat(message.body())
                .as("이름이 빠지면 다자녀 가정이 어느 자녀 알림인지 분간할 수단이 없다(ATT-03)")
                .contains("김민준");
    }
}
