package src.backend.run.navigation.service;

/** 요청 {@code scope} 파라미터의 값 도메인(API_SPEC §4.16) — 서버가 몇 개까지 자를지의 기준이다. */
public enum NavigationScope {

    /** 다음 목적지 1개만 — 상한과 무관하게 항상 1건이라 {@code truncated} 대상이 아니다. */
    NEXT,
    /** 남은 전 구간 — 공급자 상한({@link src.backend.run.navigation.spec.NavProvider#maxStops()})까지 채운다. */
    REMAINING
}
