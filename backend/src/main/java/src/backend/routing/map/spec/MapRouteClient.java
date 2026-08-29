package src.backend.routing.map.spec;

/**
 * 순서가 확정된 지점열의 <b>도로 경로</b>를 얻는 포트(§7 규칙 12 교체 축) — 노선 계산 ③단계다
 * ({@code ARCHITECTURE §8.2}).
 *
 * <p>구현체 선택은 {@code app.routing.map.provider} 한 곳이 정한다 — 기본값은 실 API
 * ({@code naver})이고, 테스트 전체 묶음은 결정론적 스텁({@code stub})으로 돈다. 네트워크·요금·NCP
 * 계정 상태가 판정을 바꾸면 그 초록은 코드에 대해 아무것도 말하지 않기 때문이다(Ruling 157).
 *
 * <p><b>보호는 구현체 설정에 둔다</b>(§7 규칙 11) — 타임아웃은 호출자가 주입하고, 재시도·서킷은
 * {@code resilience4j.*.instances.mapRoute} 다. 호출부에 두면 이 포트를 부르는 자리가 늘 때마다
 * 보호가 복제되고, 한 곳이라도 빠지면 지도 API 장애가 곧 계산 경로 전체의 정지가 된다.
 */
public interface MapRouteClient {

    /**
     * 순서가 확정된 지점열의 도로 경로. 실패는 예외가 아니라 {@code fallbackUsed} 로 표현한다.
     *
     * <p><b>단 하나의 예외</b>가 있다 — {@link CallerPolicy#ON_DEMAND} 호출 중 서킷이 열려 있으면
     * {@link MapRouteUnavailableException} 을 던진다. 서킷 개방은 <b>연속 실패가 확인된 상태</b>라
     * 근사값이 계속 나올 것이고, 사용자를 기다리게 한 화면에 그것을 실제 경로로 채우면 관리자가
     * 근사 경로를 믿고 승인한다(`p6-port-contracts.md §2`).
     *
     * @throws MapRouteUnavailableException {@code ON_DEMAND} 이면서 서킷이 열려 있을 때만
     */
    RoadRoute route(RoadRouteRequest request);
}
