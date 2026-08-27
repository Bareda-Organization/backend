package src.backend.student.geocoding.spec;

/**
 * 지오코딩 공급자에 닿지 못했음을 알리는 포트 예외 — 네트워크 오류 · 공급자 5xx · 서킷 개방.
 *
 * <p>포트가 자기 예외를 들고 있는 이유는 <b>HTTP 응답 코드를 여기서 정하지 않기 위해서</b>다.
 * {@code BusinessException} 을 어댑터가 직접 던지면 "어떤 상태 코드로 답할 것인가" 라는 API 계층의
 * 결정이 외부 호출 코드 안으로 들어가고, 공급자를 갈아끼울 때마다 그 결정이 함께 복제된다. 번역은
 * 호출부({@code AddressVerification})가 한 곳에서 한다.
 */
public class GeocodingUnavailableException extends RuntimeException {

    public GeocodingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
