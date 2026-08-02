package src.backend.routing.domain;

import java.util.List;

/** 저장되지 않은 노선 계산 결과. 시뮬레이션과 실제 저장이 같은 계산을 공유하기 위한 중간 산출물(BE-4). */
public record PlannedRoute(
        List<Long> stopStudentIds,      // 정차 순서대로의 학생 id
        List<LatLng> stopPoints,        // 같은 순서의 좌표
        List<Long> stopEtaSeconds,      // 같은 순서의 누적 ETA(초)
        double totalDistanceM,
        double totalDurationS,
        String polyline) {
}
