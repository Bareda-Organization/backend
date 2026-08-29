package src.backend.routing.map.spec;

/** 어느 소비자가 부르는지 — 서킷 개방 시 처리가 갈린다(`ARCHITECTURE §8.3` 계산의 두 소비자). */
public enum CallerPolicy {

    /** 확정 배치(출발 30분 전 도래) — 사용자가 대기하지 않으므로 서킷이 열려도 폴백으로 진행한다. */
    BATCH,

    /** ②구간 승인 미리보기·경유 지점 지정 — 사용자가 대기 중이라 서킷 개방 시 즉시 오류로 끝낸다. */
    ON_DEMAND
}
