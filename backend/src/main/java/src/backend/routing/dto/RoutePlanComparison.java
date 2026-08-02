package src.backend.routing.dto;

import java.util.List;

/**
 * 배차 변경안 비교 결과 — baseline(저장된 최신 계획) vs candidate(미저장 재계산). BE-4.
 * 정원 초과는 예외로 죽이지 않는다 — {@code candidate.stops().size() > seatCapacity} 로 화면이 판단한다.
 */
public record RoutePlanComparison(
        Snapshot baseline,              // 현재 최신 계획. 없으면 null
        Snapshot candidate,
        Delta delta,                    // baseline 이 null 이면 delta 도 null
        int seatCapacity) {             // 그 버스의 물리 좌석 수. baseline 유무와 무관하게 항상 채운다

    public record Snapshot(
            Long routePlanId,           // candidate 는 항상 null(미저장)
            Integer version,            // candidate 는 항상 null
            double totalDistanceM,
            double totalDurationS,
            List<StopView> stops,
            String polyline) {}

    public record StopView(
            int seq, Long studentId, String studentName,
            String label,               // PICKUP = pickupAddress → 없으면 boardingStop.name / DROPOFF = dropoffAddress
                                        // 전부 비면 "좌표 지정". 라벨 우선순위는 좌표 우선순위(D-K)와 같은 순서다
            double lat, double lng, long etaSeconds) {}

    public record Delta(
            double distanceM,           // candidate − baseline
            double durationS,
            int stopCount) {}
}
