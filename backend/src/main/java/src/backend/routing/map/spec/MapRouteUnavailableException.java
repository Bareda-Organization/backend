package src.backend.routing.map.spec;

/**
 * 도로 경로를 지금 얻을 수 없음을 알리는 포트 예외 — 네트워크 오류 · 공급자 5xx · 타임아웃 · 서킷 개방.
 *
 * <p>포트가 자기 예외를 들고 있는 이유는 <b>HTTP 응답 코드를 어댑터에서 정하지 않기 위해서</b>다
 * ({@code GeocodingUnavailableException} 과 같은 방침). 번역은 {@code GlobalExceptionHandler}
 * 한 곳이 {@code 503 MAP_ROUTE_UNAVAILABLE} 로 한다.
 *
 * <p><b>호출부까지 올라오는 것은 {@code ON_DEMAND} × 서킷 개방 하나뿐</b>이다 — 나머지는 구현체가
 * 직선거리 근사로 삼켜 {@code RoadRoute.fallbackUsed=true} 로 표현한다.
 */
public class MapRouteUnavailableException extends RuntimeException {

    private final boolean circuitOpen;

    public MapRouteUnavailableException(String message, Throwable cause, boolean circuitOpen) {
        super(message, cause);
        this.circuitOpen = circuitOpen;
    }

    /**
     * 서킷이 열려서 막힌 호출인지 — 단발 실패와 <b>다른 처리</b>를 받는다.
     *
     * <p>둘을 가르는 이유는 서킷 개방이 "연속 실패가 확인된 상태" 이기 때문이다. 단발 타임아웃은
     * 원인 미상이라 근사값으로 진행할 여지가 있으나, 서킷이 열린 뒤에는 근사값이 계속 나온다.
     */
    public boolean isCircuitOpen() {
        return circuitOpen;
    }
}
