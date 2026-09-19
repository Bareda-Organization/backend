package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import src.backend.notification.domain.impl.RiderStatusChangedComposer;
import src.backend.notification.domain.impl.RiderStatusChangedSubject;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 승차·하차 학부모 알림 문구(BRD-01·02, API_SPEC §4.6) — 자녀 이름을 싣는지 본다(ATT-03, Ruling 225).
 *
 * <p>{@link SignupDecidedComposerTest} 와 같은 근거로 Spring 컨텍스트를 띄우지 않는다.
 */
class RiderStatusChangedComposerTest {

    private final RiderStatusChangedComposer composer = new RiderStatusChangedComposer();

    @Test
    void 승차_문구에는_자녀_이름이_실린다() {
        NotificationMessage message = composer.compose(new RiderStatusChangedSubject("boarded", "김민준"));

        assertThat(message.body())
                .as("이름이 빠지면 다자녀 가정이 어느 자녀 알림인지 분간할 수단이 없다(ATT-03)")
                .contains("김민준");
    }

    @Test
    void 하차_문구에는_자녀_이름이_실린다() {
        NotificationMessage message = composer.compose(new RiderStatusChangedSubject("alighted", "김민준"));

        assertThat(message.body()).contains("김민준");
    }

    @Test
    void 승차와_하차는_문구가_갈린다() {
        NotificationMessage boarded = composer.compose(new RiderStatusChangedSubject("boarded", "김민준"));
        NotificationMessage alighted = composer.compose(new RiderStatusChangedSubject("alighted", "김민준"));

        assertThat(boarded.body()).isNotEqualTo(alighted.body());
    }
}
