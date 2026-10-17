package src.backend.global.websocket;

import java.util.List;

/** 한 이벤트의 방송 묶음 하나 — 같은 payload 를 받는 목적지들. 이벤트 1건 = 이런 묶음 몇 개를 Redis 메시지 1건에 담는다. */
public record BroadcastDelivery(Object payload, List<String> destinations) {

    public BroadcastDelivery {
        destinations = List.copyOf(destinations);
    }
}
