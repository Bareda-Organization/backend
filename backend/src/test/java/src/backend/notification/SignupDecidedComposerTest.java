package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import src.backend.account.event.SignupDecidedEvent;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.impl.SignupDecidedComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 가입 결정 알림의 <b>문구</b>(API_SPEC §9.7 {@code signup_decided}) — 수락과 거절이 갈리고, 거절은
 * 사유를 싣는다.
 *
 * <p>적재·발송 경로의 단언들은 문구를 보지 않는다({@code push_state}·{@code dedup_key}·행 개수만
 * 본다). 그래서 문구가 통째로 뒤바뀌어도 그쪽은 전부 초록이다 — 거절된 사람에게 "승인되었습니다"
 * 가 나가는 사고가 그 형태다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다 — 문구 조립은 저장소도 시계도 쓰지 않아 순수 함수이고,
 * 컨텍스트를 띄우면 그만큼 커넥션 풀을 붙든 채 아무것도 더 검증하지 못한다.
 */
class SignupDecidedComposerTest {

    private static final String 거절_사유 = "제출 서류가 부족합니다";

    private final SignupDecidedComposer composer = new SignupDecidedComposer();

    @Test
    void 가입_거절_문구에는_거절_사유가_그대로_실린다() {
        NotificationMessage message = composer.compose(결정(false, 거절_사유));

        assertThat(message.body())
                .as("사유가 빠지면 거절된 사람은 무엇을 고쳐 재신청해야 하는지 알 수단이 부재하다")
                .contains(거절_사유);
    }

    @Test
    void 가입_수락과_거절은_제목도_본문도_갈린다() {
        NotificationMessage 수락 = composer.compose(결정(true, null));
        NotificationMessage 거절 = composer.compose(결정(false, 거절_사유));

        assertThat(수락.title()).isNotEqualTo(거절.title());
        assertThat(수락.body())
                .as("결과가 갈리는데 문구가 같으면 수신자는 승인인지 거절인지 알림만으로 판단 불가하다")
                .isNotEqualTo(거절.body());
        assertThat(수락.body())
                .as("수락 문구에 거절 사유가 섞이면 승인된 사람에게 거절 사유가 노출된다")
                .doesNotContain(거절_사유);
    }

    private SignupDecidedEvent 결정(boolean accepted, String rejectReason) {
        return new SignupDecidedEvent(1L, 8L, "조대기", Role.PARENT, accepted, rejectReason,
                OffsetDateTime.parse("2026-08-26T10:00:00+09:00"));
    }
}
