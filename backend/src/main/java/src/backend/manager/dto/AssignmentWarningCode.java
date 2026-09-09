package src.backend.manager.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 배치 충돌 경고 3종(MGR-06 · API_SPEC §5.14, Ruling 165) — <b>세 판정은 서로 독립이다.</b>
 *
 * <p>묶으면 {@link #MANAGER_DOUBLE_BOOKED} 가 근무 시간 미기재 매니저에서 조용히 사라진다 —
 * 근무 시간이 없다고 해서 같은 시각에 두 대를 몰 수 있는 것은 아니다.
 *
 * <p>{@code ErrorCode} 와 다른 축이라 그 enum 에 넣지 않는다 — 이쪽은 <b>200 응답에 실리는</b> 판정
 * 결과이고, 저쪽은 요청을 거부한 이유다. 한 곳에 두면 다음 사람이 경고를 상태 코드에 잇는다.
 */
@Getter
@RequiredArgsConstructor
public enum AssignmentWarningCode {

    /**
     * 회차 시간대가 그 매니저의 근무 구간 밖 — 그 요일 키가 없거나 어느 구간에도 들지 않는다.
     * {@code est_duration_min} 이 있으면 구간으로, 없으면 출발 시각 점으로 판정한다
     * (Ruling 165 ② 재판정 · Phase 7 목표 12).
     */
    WORK_HOURS_MISMATCH("근무 시간 밖입니다"),

    /** 그 매니저가 같은 날 <b>출발 시각이 같은</b> 다른 회차에도 배치돼 있다 — 근무 시간을 보지 않는다. */
    MANAGER_DOUBLE_BOOKED("같은 시각의 다른 회차에도 배치돼 있습니다"),

    /** 근무 시간이 비어 있어 <b>판정할 근거가 부재</b>하다 — 적합한 것이 아니라 판정하지 못한 것이다. */
    WORK_HOURS_NOT_SET("근무 시간이 등록돼 있지 않아 판정할 수 없습니다");

    private final String message;
}
