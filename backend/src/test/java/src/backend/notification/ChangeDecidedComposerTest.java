package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import src.backend.notification.domain.impl.ChangeDecidedComposer;
import src.backend.notification.domain.impl.ChangeDecidedSubject;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * ②구간 변경 요청 관리자 결정 알림 문구(API_SPEC §5.6·§9.7 {@code change_decided}) — 자녀 이름을
 * 싣는지 본다(ATT-03, R17-T3 목표 1). {@link RiderStatusChangedComposerTest} 와 같은 근거로 Spring
 * 컨텍스트를 띄우지 않는다.
 */
class ChangeDecidedComposerTest {

    private final ChangeDecidedComposer composer = new ChangeDecidedComposer();

    @Test
    void 승인_문구에는_자녀_이름이_실린다() {
        NotificationMessage message = composer.compose(new ChangeDecidedSubject(true, null, "김민준"));

        assertThat(message.body())
                .as("이름이 빠지면 다자녀 가정이 어느 자녀 알림인지 분간할 수단이 없다(ATT-03)")
                .contains("김민준");
    }

    @Test
    void 거절_문구에는_자녀_이름과_사유가_함께_실린다() {
        NotificationMessage message = composer.compose(new ChangeDecidedSubject(false, "정원 초과", "김민준"));

        assertThat(message.body()).contains("김민준").contains("정원 초과");
    }
}
