package src.backend.routing.pipeline;

import java.util.List;
import java.util.Objects;

import src.backend.routing.engine.spec.OrderableStop;

/**
 * 좌표 해석(①단계)의 결과 — 재배열 대상 정차지와, 좌표를 얻지 못해 분리된 학생이 함께 나온다.
 *
 * <p>둘을 한 값으로 묶는 이유는 <b>분리가 정상 결과</b>이기 때문이다. 좌표 미확보를 예외로 던지면
 * 한 명의 주소 오류가 회차 전체를 무르고, 그러면 버스 한 대가 선다. 값으로 돌려주면 나머지 학생의
 * 노선은 계산되고 분리된 학생은 관계자 화면에서 손으로 처리할 수 있다.
 *
 * @param stops                승하차지 단위로 묶인 정차지 — 같은 승하차지의 학생은 한 자리로 합쳐진다
 * @param unresolvedStudentIds 그날의 승하차지를 얻지 못한 학생
 */
public record DailyStopResolution(List<OrderableStop> stops, List<Long> unresolvedStudentIds) {

    public DailyStopResolution {
        stops = List.copyOf(Objects.requireNonNull(stops, "정차지 목록이 없다"));
        unresolvedStudentIds = List.copyOf(
                Objects.requireNonNull(unresolvedStudentIds, "좌표 미확보 학생 목록이 없다"));
    }
}
