package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import src.backend.notification.domain.impl.RunApproachingStopComposer;
import src.backend.notification.domain.impl.RunApproachingStopSubject;
import src.backend.notification.domain.spec.NotificationMessage;

/** 근접(도착) 알림 문구(API_SPEC §9.7 {@code arrive}, NTF-04) — 자녀 이름을 싣는지 본다(ATT-03, Ruling 225). */
class RunApproachingStopComposerTest {

    private final RunApproachingStopComposer composer = new RunApproachingStopComposer();

    @Test
    void 문구에_자녀_이름이_실린다() {
        NotificationMessage message = composer.compose(new RunApproachingStopSubject("정하윤"));

        assertThat(message.body()).contains("정하윤");
    }
}
