package src.backend.routing.engine.spec;

import java.util.List;
import java.util.Objects;

/**
 * 산출 순서 — {@code seq} 는 1부터 빈틈 없이 이어진다.
 *
 * <p>빈틈을 여기서 막는 이유는 {@code uk_run_stop_version_seq}·{@code uk_route_stop_route_seq} 가
 * 순번의 <b>중복</b>만 막고 <b>건너뜀</b>은 통과시키기 때문이다. 순번이 2, 4, 5 로 나가도 저장은
 * 성공하고, 기사 화면에서 3번이 사라진 뒤에야 드러난다.
 */
public record StopOrder(List<OrderedStop> sequence) {

    public StopOrder {
        sequence = List.copyOf(Objects.requireNonNull(sequence, "산출 순서가 없다"));
        for (int index = 0; index < sequence.size(); index++) {
            int expected = index + 1;
            if (sequence.get(index).seq() != expected) {
                throw new IllegalArgumentException(
                        "순번은 1부터 빈틈 없이 이어져야 한다: " + expected + " 자리에 "
                                + sequence.get(index).seq());
            }
        }
    }

    /** 정차 수 — 품질 회귀 판정의 지표 3종 중 하나다(TECH_DECISIONS §8.5.2). */
    public int stopCount() {
        return sequence.size();
    }
}
