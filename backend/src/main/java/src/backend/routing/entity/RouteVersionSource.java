package src.backend.routing.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 노선 버전 발생 원인 4종 — {@code route_version.source}(CHECK 로 강제)의 값 도메인이다.
 */
public enum RouteVersionSource {

    /** 확정 배치가 생성. */
    CONFIRM_BATCH,
    /** 변경 요청 승인이 생성. */
    APPROVAL,
    /** 경유지 재계산이 생성. */
    WAYPOINT,
    /** 인수인계(기사·동승자 교체 등)가 생성. */
    TRANSFER;

    /** {@link RouteVersionSource} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<RouteVersionSource> {
        public Db() {
            super(RouteVersionSource.class);
        }
    }
}
