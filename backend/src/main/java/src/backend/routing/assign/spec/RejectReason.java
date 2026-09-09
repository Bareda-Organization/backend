package src.backend.routing.assign.spec;

/**
 * 동승자 자동 배정에서 후보가 걸러지는 사유 3종(목표 9, Ruling 186).
 *
 * <p>우선순위는 {@code WORK_HOURS_NOT_SET} → {@code OUT_OF_WORK_HOURS} → {@code ALREADY_ASSIGNED}
 * 순이다 — 판정 근거가 아예 없는 상태(미등록)를 판정 결과가 있는 상태(구간 밖)보다 먼저 본다.
 */
public enum RejectReason {

    /** 근무 시간이 등록돼 있지 않아 판정할 수 없음 — {@code AssignmentWarningCode.WORK_HOURS_NOT_SET} 과 같은 축(Ruling 165 ④). */
    WORK_HOURS_NOT_SET,
    /** 회차 시간대(departAt ~ departAt+estDurationMin)가 등록된 근무 시간 밖. */
    OUT_OF_WORK_HOURS,
    /** 같은 시간대에 다른 회차에 이미 배정됨(MGR-06 중복 배치). */
    ALREADY_ASSIGNED
}
