package src.backend.global.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** {@link ConstraintViolations#isViolationOf} — 이름이 같을 때만 그 제약으로 판정하는지(BR-099 통합 유틸). */
class ConstraintViolationsTest {

    @Test
    void 원인_체인의_제약명이_같으면_그_제약이다() {
        DataIntegrityViolationException e = wrap("uk_bus_no");

        assertThat(ConstraintViolations.isViolationOf(e, "uk_bus_no")).isTrue();
    }

    @Test
    void 다른_제약이_거부한_것이면_그_제약이_아니다() {
        DataIntegrityViolationException e = wrap("uk_schedule_bus_weekday_direction_depart");

        assertThat(ConstraintViolations.isViolationOf(e, "uk_bus_no")).isFalse();
    }

    @Test
    void 원인이_제약_예외가_아니면_그_제약이_아니다() {
        DataIntegrityViolationException e = new DataIntegrityViolationException("no constraint cause");

        assertThat(ConstraintViolations.isViolationOf(e, "uk_bus_no")).isFalse();
    }

    @Test
    void 사이에_예외가_한_겹_더_끼어도_원인_체인에서_그_제약을_찾는다() {
        var cause = new ConstraintViolationException("violated", new SQLException("dup"), "uk_bus_no");
        var e = new DataIntegrityViolationException("failed", new IllegalStateException("flush 래핑", cause));

        assertThat(ConstraintViolations.isViolationOf(e, "uk_bus_no")).isTrue();
        assertThat(ConstraintViolations.isViolationOf(e, "uk_other")).as("체인을 따라가도 이름은 정확히 대조한다").isFalse();
    }

    private static DataIntegrityViolationException wrap(String constraintName) {
        var cause = new ConstraintViolationException("violated", new SQLException("dup"), constraintName);
        return new DataIntegrityViolationException("failed", cause);
    }
}
