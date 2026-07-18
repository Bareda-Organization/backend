package src.backend.route.dto;

import src.backend.route.entity.Stop;

/**
 * 정류장 응답. 노선 안에서 seq(순서)대로 나열된다.
 */
public record StopResponse(
        Long id,
        Long routeId,
        String name,
        int seq,
        double lat,
        double lng) {

    public static StopResponse from(Stop stop) {
        return new StopResponse(
                stop.getId(),
                stop.getRoute().getId(),
                stop.getName(),
                stop.getSeq(),
                stop.getLat(),
                stop.getLng());
    }
}
