package src.backend.run.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 회차 취소를 낸 쪽 2종 — {@code run.cancel_source}(CHECK 로 강제)의 값 도메인이다. 스케줄 재활성이 되살릴 수 있는
 * 회차를 관계자가 직접 취소한 회차와 가르는 유일한 근거다(Ruling 367 ②).
 */
public enum RunCancelSource {

    /** 관계자가 직접 취소했다(SCH-03) — 스케줄이 바뀌어도 되살리지 않는다. */
    STAFF,
    /** 스케줄 변경(비활성 · 요일/방향 이탈)이 반영돼 취소됐다 — 스케줄이 다시 뒷받침하면 되살린다. */
    SCHEDULE;

    /** {@link RunCancelSource} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<RunCancelSource> {
        public Db() {
            super(RunCancelSource.class);
        }
    }
}
