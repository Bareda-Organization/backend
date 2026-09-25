package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import src.backend.notification.domain.impl.NoShowParentComposer;
import src.backend.notification.domain.spec.NoShowParentSubject;
import src.backend.notification.domain.spec.NotificationMessage;

/** 미승차 학부모 알림 문구(BRD-04) — 자녀 이름을 싣는지 본다(ATT-03, Ruling 225). */
class NoShowParentComposerTest {

    private final NoShowParentComposer composer = new NoShowParentComposer();

    @Test
    void 문구에_자녀_이름이_실린다() {
        NotificationMessage message = composer.compose(new NoShowParentSubject("박도윤"));

        assertThat(message.body()).contains("박도윤");
    }
}
