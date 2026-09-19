package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import src.backend.notification.domain.impl.RiderStatusRevertedComposer;
import src.backend.notification.domain.impl.RiderStatusRevertedSubject;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 승하차 되돌리기 정정 알림 문구(BRD-05, API_SPEC §4.7, Ruling 219) — 자녀 이름을 싣는지 본다
 * (ATT-03, Ruling 225).
 */
class RiderStatusRevertedComposerTest {

    private final RiderStatusRevertedComposer composer = new RiderStatusRevertedComposer();

    @Test
    void 승차_취소_문구에는_자녀_이름이_실린다() {
        NotificationMessage message = composer.compose(new RiderStatusRevertedSubject("boarded", "이서연"));

        assertThat(message.body()).contains("이서연");
    }

    @Test
    void 승차_취소와_하차_취소는_문구가_갈린다() {
        NotificationMessage boardingCanceled = composer.compose(new RiderStatusRevertedSubject("boarded", "이서연"));
        NotificationMessage alightingCanceled = composer.compose(new RiderStatusRevertedSubject("alighted", "이서연"));

        assertThat(boardingCanceled.body()).isNotEqualTo(alightingCanceled.body());
    }
}
