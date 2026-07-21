package src.backend.routing.domain;

/** 버스 배정용 최소 정보 — engine 포트({@code BusAssigner})가 JPA 엔티티에 의존하지 않도록 분리한 순수 값 타입. */
public record BusCapacity(Long busId, int seatCapacity) {
}
