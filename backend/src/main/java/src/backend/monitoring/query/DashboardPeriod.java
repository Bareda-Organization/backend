package src.backend.monitoring.query;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 대시보드 기간 계산 한 곳(API_SPEC §6.18) — 오늘(서울)을 끝으로 하는 {@code days} 일, 그 바로 앞의 같은 길이(직전 기간), 그리고 추이 그래프용
 * 최근 {@code max(days, 7)} 일. 회차는 {@code service_date}, 로그인·결정·접수는 발생 시각의 서울 날짜로 가르므로 날짜를 반열림 시각 구간
 * {@code [startOf(from), startOf(to + 1)]} 로 바꾸는 것도 여기서 한다.
 */
record DashboardPeriod(LocalDate today, int days, ZoneId zone) {

    /** 추이 그래프가 늘 7칸 이상이도록 하는 최소 일수. */
    static final int MIN_DAILY_DAYS = 7;

    LocalDate from() {
        return today.minusDays(days - 1L);
    }

    LocalDate previousFrom() {
        return from().minusDays(days);
    }

    LocalDate previousTo() {
        return from().minusDays(1);
    }

    LocalDate dailyFrom() {
        return today.minusDays(Math.max(days, MIN_DAILY_DAYS) - 1L);
    }

    /** 집계 쿼리가 한 번에 읽는 가장 이른 날 — 직전 기간과 추이 구간을 모두 덮는다. */
    LocalDate queryFrom() {
        return previousFrom().isBefore(dailyFrom()) ? previousFrom() : dailyFrom();
    }

    boolean inPeriod(LocalDate date) {
        return !date.isBefore(from()) && !date.isAfter(today);
    }

    boolean inPrevious(LocalDate date) {
        return !date.isBefore(previousFrom()) && !date.isAfter(previousTo());
    }

    OffsetDateTime startOf(LocalDate date) {
        return date.atStartOfDay(zone).toOffsetDateTime();
    }
}
