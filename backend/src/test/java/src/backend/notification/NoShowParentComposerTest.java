package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.Direction;
import src.backend.notification.domain.impl.NoShowParentComposer;
import src.backend.notification.domain.spec.NoShowParentSubject;
import src.backend.notification.domain.spec.NotificationMessage;

/** 미승차 학부모 알림 문구(BRD-04) — 자녀 이름을 싣는지 본다(ATT-03, Ruling 225). */
class NoShowParentComposerTest {

    private final NoShowParentComposer composer = new NoShowParentComposer();

    @Test
    void 문구에_자녀_이름이_실린다() {
        NotificationMessage message = composer.compose(new NoShowParentSubject("박도윤", Direction.TO_ACADEMY));

        assertThat(message.body()).contains("박도윤");
    }

    @Test
    void 등원_문구는_탑승하지_않았다고_알린다() {
        NotificationMessage message = composer.compose(new NoShowParentSubject("박도윤", Direction.TO_ACADEMY));

        assertThat(message.body()).isEqualTo("박도윤 학생이 아직 버스에 탑승하지 않았습니다. 확인해 주세요.");
    }

    /** Ruling 881 — 하원의 미승차는 버스에 탔던 학생이라 "탑승하지 않았다" 가 아니라 하차 미확인으로 알린다. */
    @Test
    void 하원_문구는_하차가_확인되지_않았다고_알린다() {
        NotificationMessage message = composer.compose(new NoShowParentSubject("박도윤", Direction.FROM_ACADEMY));

        assertThat(message.body()).isEqualTo("박도윤 학생의 하차가 확인되지 않았습니다. 학원에 확인해 주세요.");
    }
}
