package src.backend.routing.engine.impl;

import src.backend.routing.domain.GeoPoint;

/**
 * 계산 도중의 한 자리 — 순번을 아직 달지 않은 {@code OrderedStop} 이다.
 *
 * <p>순번을 마지막에 붙이는 이유는 2-opt 가 구간을 뒤집기 때문이다. 자리마다 순번을 들고 있으면
 * 뒤집을 때마다 레코드를 다시 만들어야 하고, 그 재생성이 한 번 빠지면 순번과 위치가 어긋난다.
 */
record Slot(Long stopId, Long waypointId, GeoPoint point) {}
