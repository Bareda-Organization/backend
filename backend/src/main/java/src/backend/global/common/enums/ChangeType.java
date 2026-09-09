package src.backend.global.common.enums;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 정차·탑승자 목록의 변경 구분 — {@code run_stop.change} 는 {@code ADDED}·{@code SKIPPED} 만,
 * {@code run_rider.change} 는 {@code ADDED}·{@code REMOVED} 만 쓰는 서로 다른 부분집합이다.
 *
 * <p>부분집합 강제는 DB CHECK 가 담당한다 — Java 쪽에서 {@code run_stop} 에 {@code REMOVED} 를,
 * {@code run_rider} 에 {@code SKIPPED} 를 넣어도 컴파일도 {@code ddl-auto: validate} 도 막지
 * 못하고 INSERT 시점 CHECK 위반으로만 드러난다.
 */
public enum ChangeType {

    ADDED, SKIPPED, REMOVED;

    /** {@link ChangeType} 을 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ChangeType> {
        public Db() {
            super(ChangeType.class);
        }
    }
}
