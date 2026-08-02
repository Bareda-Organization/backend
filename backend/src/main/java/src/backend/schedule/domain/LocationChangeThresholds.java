package src.backend.schedule.domain;

/**
 * 학부모 위치 변경 자동 적용 임계(D-H). 두 값을 <b>모두</b> 만족해야 통과한다(AND) —
 * OR 로 두면 "거리는 1km인데 20분이 늘어난 경로"가 통과한다.
 * 감소(음수 델타)는 항상 통과한다.
 */
public final class LocationChangeThresholds {

    /** 총 소요시간 증가 허용치(초). 이 값을 넘으면 자동 거부한다. */
    public static final double MAX_DELTA_DURATION_S = 300;   // 5분

    /** 총 거리 증가 허용치(미터). 이 값을 넘으면 자동 거부한다. */
    public static final double MAX_DELTA_DISTANCE_M = 1000;  // 1km

    private LocationChangeThresholds() {
    }
}
