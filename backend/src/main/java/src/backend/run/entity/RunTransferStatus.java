package src.backend.run.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 버스 간 이동(F4 S1, API_SPEC §5.8, RTE-07, Ruling 256) 신청의 처리 상태 2종 —
 * {@code run_transfer.status}(CHECK 로 강제)의 값 도메인이다.
 *
 * <p>{@code staged → applied} 전이는 사용자 조작이 아니라 도착·출발 두 회차 중 먼저 도는
 * 확정 배치(RTE-08)가 그 회차 쪽 절반을 반영하는 시점에 일어난다 — 이 enum 은 값 도메인만
 * 정의하고 전이 로직은 이 태스크의 범위 밖(확정 배치 서비스 계층)이다.
 */
public enum RunTransferStatus {

    /** 확정 배치가 아직 반영하지 않은 대기 상태. */
    STAGED,
    /** 출발·도착 중 한쪽 이상의 확정 배치가 명단에 반영함. */
    APPLIED;

    /** {@link RunTransferStatus} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<RunTransferStatus> {
        public Db() {
            super(RunTransferStatus.class);
        }
    }
}
