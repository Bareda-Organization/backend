package src.backend.routing.engine.spec;

import java.util.Map;

/**
 * 순서 최적화 전략 포트(§7 규칙 12 교체 축) — 파이프라인 ②구간이 이 인터페이스만 안다
 * (ARCHITECTURE §8.2).
 *
 * <p>포트로 가른 이유는 <b>최적화 기준이 미확정</b>이기 때문이다(PRD §10.1 G · §7.1 P2 후속 F-01).
 * 거리·시간·정원 가중치를 어떻게 섞을지가 정해지지 않은 채로 구현을 파이프라인에 박아 넣으면,
 * 기준이 정해졌을 때 ③④⑤ 단계까지 함께 흔들린다.
 *
 * <p>구현체를 바꿔도 <b>나빠졌는지 알 수 있어야</b> 하므로 {@link #name()} 과
 * {@link #policySnapshot()} 을 함께 요구한다 — 산출물만 남고 산출 조건이 안 남으면 과거 노선을
 * 나중에 설명할 수단이 부재하다(TECH_DECISIONS §8.5.1).
 */
public interface RouteEngine {

    /**
     * 이 전략의 식별자 — {@code route_version.engine_name} 에 그대로 실린다.
     *
     * <p>컬럼이 {@code varchar(30)} 이라 <b>30자를 넘지 않는다.</b> 구현체마다 서로 다른 값이어야
     * 어느 전략의 산출물인지 사후에 가릴 수 있다.
     */
    String name();

    /**
     * 이번 산출에 실제로 쓴 정책값 — {@code route_version.policy_snapshot}(jsonb) 로 직렬화된다.
     *
     * <p><b>참조가 아니라 값을 담는다.</b> 정책은 바뀌는데 과거 노선은 남으므로, 상수를 가리키기만
     * 하면 "왜 이 순서로 돌았나" 를 나중에 재현할 수 없다(TECH_DECISIONS §8.5.1).
     */
    Map<String, Object> policySnapshot();

    /**
     * 정차지를 방문 순서대로 재배열한다.
     *
     * <p>보장하는 것 둘 — {@link RouteOrderInput#fixedStops()} 의 순번은 <b>그대로 남고</b>,
     * 같은 입력은 <b>언제나 같은 출력</b>을 낸다. 앞은 관리자 지정의 의미를 지키는 것이고
     * (ARCHITECTURE §8.2), 뒤가 없으면 품질 회귀 판정 자체가 성립하지 않는다
     * (TECH_DECISIONS §8.5.2).
     */
    StopOrder order(RouteOrderInput input);
}
