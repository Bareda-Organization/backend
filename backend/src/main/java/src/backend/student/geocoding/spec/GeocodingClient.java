package src.backend.student.geocoding.spec;

import java.util.Optional;

/**
 * 주소 → 좌표 변환 포트(§7 규칙 12 교체 축) — 공급자는 네이버 확정이나(C-18) 호출부는 이
 * 인터페이스만 안다.
 *
 * <p>구현체 선택은 {@code geocoding.provider} 한 곳이 정한다(ARCHITECTURE §3.2.1) — 기본값은 실
 * API({@code naver})이고, 테스트 전체 묶음은 결정론적 스텁({@code stub})으로 돈다. 네트워크·요금·NCP
 * 계정 상태가 판정을 바꾸면 그 초록은 코드에 대해 아무것도 말하지 않기 때문이다(Ruling 157).
 *
 * <p><b>이 포트를 트랜잭션 안에서 부르지 않는다</b>(§7 규칙 16) — 기본 구현체가 실 API 라, 검증을
 * 저장 트랜잭션 안에 넣으면 그 트랜잭션이 네이버 응답 시간만큼 열린 채로 남는다.
 */
public interface GeocodingClient {

    /**
     * 주소 한 건을 좌표로 옮긴다.
     *
     * <p><b>"그런 주소가 없다" 와 "지금 물어볼 수 없다" 를 반환값과 예외로 가른다.</b> 앞은
     * {@link Optional#empty()}(공급자가 결과 0건으로 답한 것 — 사용자가 주소를 고칠 자리)이고, 뒤는
     * {@link GeocodingUnavailableException}(공급자에 닿지 못한 것 — 같은 주소를 이따가 다시 보낼
     * 자리)이다. 둘을 한 형태로 뭉개면 호출부가 사용자에게 무엇을 안내할지 정할 수단을 잃는다.
     *
     * @throws GeocodingUnavailableException 네트워크 오류 · 공급자 5xx · 서킷 개방
     */
    Optional<GeocodedPoint> geocode(String address);
}
