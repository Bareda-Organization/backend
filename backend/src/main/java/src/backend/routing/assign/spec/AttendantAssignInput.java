package src.backend.routing.assign.spec;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 동승자 자동 배정 1회의 입력 — 회차 1건에 대해 근무 시간·중복 배치 충돌을 판정할 재료다.
 *
 * @param runId          배정 대상 회차
 * @param academyId      소속 학원 — 후보는 호출부가 이미 이 학원으로 좁혀 꺼낸 것이라 여기서 다시
 *                       판정하지 않는다
 * @param departAt       회차 출발 시각
 * @param estDurationMin ④ ETA 산출의 결과. 이것이 없으면 회차 시간대(departAt ~ departAt+estDurationMin)
 *                       를 정할 수 없어 충돌 판정 자체가 성립하지 않는다(ARCHITECTURE §8.2 ⑤)
 * @param candidates     배정 후보
 */
public record AttendantAssignInput(
        long runId,
        long academyId,
        OffsetDateTime departAt,
        int estDurationMin,
        List<AttendantCandidate> candidates) {

    public AttendantAssignInput {
        Objects.requireNonNull(departAt, "출발 시각이 없으면 회차 시간대를 정할 수 없다");
        if (estDurationMin < 0) {
            throw new IllegalArgumentException("소요 시간은 음수일 수 없다: " + estDurationMin);
        }
        candidates = List.copyOf(Objects.requireNonNull(candidates, "후보 목록이 없다"));
    }
}
