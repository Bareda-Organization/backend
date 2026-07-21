package src.backend.routing.engine.spec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import src.backend.routing.domain.BusCapacity;
import src.backend.routing.domain.LatLng;

/**
 * 버스 자동 배정(클러스터링) 포트 — 알고리즘 교체 가능성이 있는 전략이라 인터페이스로 분리한다(§11.3).
 * {@link RouteEngine}과 마찬가지로 외부 호출 없는 순수 계산만 수행한다.
 */
public interface BusAssigner {

    /**
     * depot 기준으로 학생을 버스별로 배정한다. 반환 map 의 key=busId, value=배정된 studentId 목록
     * (방문 순서 아님 — 순서 최적화는 {@link RouteEngine} 몫). 총원이 총 정원을 초과하면 empty.
     */
    Optional<Map<Long, List<Long>>> assign(LatLng depot, Map<Long, LatLng> studentPoints, List<BusCapacity> buses);
}
