package src.backend.routing.map.spec;

/**
 * 이웃한 두 지점 사이의 한 구간 — ETA 산출(④단계)이 이 값을 누적한다.
 *
 * @param distanceMeters  구간 거리(m). 폴백일 때는 직선거리다
 * @param durationSeconds 구간 소요 시간(초). 폴백일 때는 평균 속도 가정으로 환산한 값이다
 */
public record RoadLeg(int distanceMeters, int durationSeconds) {
}
