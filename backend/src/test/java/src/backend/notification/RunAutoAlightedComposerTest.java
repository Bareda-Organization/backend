package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import src.backend.notification.domain.impl.RunAutoAlightedComposer;
import src.backend.notification.domain.impl.RunAutoAlightedSubject;
import src.backend.notification.domain.spec.NotificationMessage;

/** 등원 최종 도착 자동 하차 알림 문구(API_SPEC §9.7 {@code alighting}) — 자녀 이름을 싣는지 본다(ATT-03, Ruling 225). */
class RunAutoAlightedComposerTest {

    private final RunAutoAlightedComposer composer = new RunAutoAlightedComposer();

    @Test
    void 문구에_자녀_이름이_실린다() {
        NotificationMessage message = composer.compose(new RunAutoAlightedSubject("최지호"));

        assertThat(message.body()).contains("최지호");
    }
}
