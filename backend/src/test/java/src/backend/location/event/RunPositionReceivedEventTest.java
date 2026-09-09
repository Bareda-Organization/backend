package src.backend.location.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

/**
 * {@link RunPositionReceivedEvent} 생성자 인자 순서 회귀 시험 — Phase 8 에서 같은 타입 인자 2개가
 * 뒤바뀌어 컴파일은 되고 수신자 조회가 0건이 된 사고와 같은 함정을 이 이벤트도 갖는다
 * ({@code recordedAt} · {@code receivedAt} 이 둘 다 {@code OffsetDateTime}).
 *
 * <p>{@code recordedAt} 과 {@code receivedAt} 을 서로 다른 값으로 넣어, 어느 접근자가 어느 값을
 * 돌려주는지로 순서를 검사한다 — 두 값이 같으면 뒤바뀌어도 이 시험이 통과해 무의미해진다.
 */
class RunPositionReceivedEventTest {

    @Test
    void 생성자_인자_순서가_기록시각과_수신시각을_뒤바꾸지_않는다() {
        Long runId = 1L;
        BigDecimal lat = new BigDecimal("37.500000");
        BigDecimal lng = new BigDecimal("127.000000");
        OffsetDateTime recordedAt = OffsetDateTime.parse("2026-09-01T08:00:00+09:00");
        OffsetDateTime receivedAt = recordedAt.plusSeconds(3);

        RunPositionReceivedEvent event = new RunPositionReceivedEvent(runId, lat, lng, recordedAt, receivedAt);

        assertThat(event.runId()).isEqualTo(runId);
        assertThat(event.lat()).isEqualTo(lat);
        assertThat(event.lng()).isEqualTo(lng);
        assertThat(event.recordedAt())
                .as("recordedAt 은 단말이 찍은 시각 — receivedAt 과 뒤바뀌면 안 된다")
                .isEqualTo(recordedAt);
        assertThat(event.receivedAt())
                .as("receivedAt 은 서버가 받은 시각 — recordedAt 과 뒤바뀌면 안 된다")
                .isEqualTo(receivedAt);
    }
}
