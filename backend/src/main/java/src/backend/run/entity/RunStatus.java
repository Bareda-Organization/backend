package src.backend.run.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 회차 상태 4종 — {@code run.status}(CHECK 로 강제)의 값 도메인이다.
 *
 * <p>{@code idle → confirmed} 전이는 사용자 조작이 아니라 출발 30분 전 도래가 일으키는
 * 시간 기반 배치의 산출물이다(ARCHITECTURE §9) — 이 enum 은 값 도메인만 정의하고 전이 판정
 * 로직은 이 태스크의 범위 밖(배치·서비스 계층)이다.
 */
public enum RunStatus {

    /** 확정 전 대기. */
    IDLE,
    /** 출발 30분 전 도래로 확정됨. */
    CONFIRMED,
    /** 운행 중. */
    MOVING,
    /** 종료됨. */
    FINISHED;

    /** {@link RunStatus} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<RunStatus> {
        public Db() {
            super(RunStatus.class);
        }
    }
}
