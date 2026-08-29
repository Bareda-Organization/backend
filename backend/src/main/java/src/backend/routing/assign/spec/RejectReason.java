package src.backend.routing.assign.spec;

/** 동승자 자동 배정에서 후보가 걸러지는 사유 2종(목표 9). */
public enum RejectReason {

    /** 회차 시간대(departAt ~ departAt+estDurationMin)가 근무 시간 밖 — 근무 시간 미등록도 이 사유다. */
    OUT_OF_WORK_HOURS,
    /** 같은 시간대에 다른 회차에 이미 배정됨(MGR-06 중복 배치). */
    ALREADY_ASSIGNED
}
