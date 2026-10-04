package src.backend.monitoring.query;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToLongFunction;

import src.backend.academy.entity.Academy;
import src.backend.monitoring.dto.AdminDashboardResponse;
import src.backend.run.repository.RunDailyAggregate;

/**
 * 대시보드의 회차 지표(API_SPEC §6.18) — {@code aggregateDaily} 가 돌려준 학원·운행일별 집계 행을 기간별로 접는 순수 계산이다. DB 를 부르지
 * 않으므로 경계·분모 0 같은 계산 규칙이 여기 한 곳에 있다.
 */
final class AdminDashboardRunMetrics {

    /** 정시 출발률 목표 — 화면 표시 상수이고 서버 판정에는 쓰지 않는다(FEATURE_SPEC §2.1, Ruling 802). */
    static final double TARGET_ON_TIME_RATE = 0.9;

    private AdminDashboardRunMetrics() {
    }

    static AdminDashboardResponse.Runs runs(List<RunDailyAggregate> rows, DashboardPeriod period) {
        return new AdminDashboardResponse.Runs(
                sum(rows, row -> period.inPeriod(row.getServiceDate()), RunDailyAggregate::getRunCount),
                sum(rows, row -> period.inPrevious(row.getServiceDate()), RunDailyAggregate::getRunCount),
                sum(rows, row -> period.inPeriod(row.getServiceDate()), RunDailyAggregate::getCanceledCount));
    }

    static AdminDashboardResponse.OnTime onTime(List<RunDailyAggregate> rows, DashboardPeriod period) {
        long started = sum(rows, row -> period.inPeriod(row.getServiceDate()), RunDailyAggregate::getStartedCount);
        long onTime = sum(rows, row -> period.inPeriod(row.getServiceDate()), RunDailyAggregate::getOnTimeCount);
        return new AdminDashboardResponse.OnTime(rateOf(onTime, started), onTime, started, TARGET_ON_TIME_RATE);
    }

    static AdminDashboardResponse.Delays delays(List<RunDailyAggregate> rows, DashboardPeriod period) {
        Map<LocalDate, Long> delayedByDay = new TreeMap<>();
        rows.stream().filter(row -> period.inPeriod(row.getServiceDate()))
                .forEach(row -> delayedByDay.merge(row.getServiceDate(), row.getDelayedCount(), Long::sum));
        long total = delayedByDay.values().stream().mapToLong(Long::longValue).sum();
        // 가장 많은 날 — 같은 수면 더 최근 날이다. 0건이면 없다
        AdminDashboardResponse.Peak peak = delayedByDay.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.<LocalDate, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new AdminDashboardResponse.Peak(entry.getKey(), entry.getValue())).orElse(null);
        return new AdminDashboardResponse.Delays(total, delayedByDay.getOrDefault(period.today(), 0L), peak);
    }

    /** 최근 {@code max(days, 7)} 일을 오름차순으로 — 로그인 칸은 호출부가 날짜별로 채운 값을 받는다. */
    static List<AdminDashboardResponse.Daily> daily(List<RunDailyAggregate> rows, DashboardPeriod period,
            Map<LocalDate, Long> loginSuccessByDay, Map<LocalDate, Long> loginFailByDay) {
        return period.dailyFrom().datesUntil(period.today().plusDays(1))
                .map(date -> new AdminDashboardResponse.Daily(date,
                        sum(rows, row -> row.getServiceDate().equals(date), RunDailyAggregate::getRunCount),
                        sum(rows, row -> row.getServiceDate().equals(date), RunDailyAggregate::getDelayedCount),
                        loginSuccessByDay.getOrDefault(date, 0L), loginFailByDay.getOrDefault(date, 0L)))
                .toList();
    }

    /** 학원 이름순 한 줄씩 — 기간에 회차가 없는 학원도 0 으로 싣는다. */
    static List<AdminDashboardResponse.AcademyRow> academyRows(List<RunDailyAggregate> rows, List<Academy> academies,
            DashboardPeriod period, Map<Long, Long> emergencyByAcademy, Map<Long, Long> changeRequestByAcademy) {
        return academies.stream().sorted(Comparator.comparing(Academy::getName).thenComparing(Academy::getId))
                .map(academy -> {
                    List<RunDailyAggregate> mine = rows.stream()
                            .filter(row -> row.getAcademyId().equals(academy.getId())).toList();
                    long started = sum(mine, row -> period.inPeriod(row.getServiceDate()),
                            RunDailyAggregate::getStartedCount);
                    long onTime = sum(mine, row -> period.inPeriod(row.getServiceDate()),
                            RunDailyAggregate::getOnTimeCount);
                    return new AdminDashboardResponse.AcademyRow(academy.getId(), academy.getName(),
                            sum(mine, row -> period.inPeriod(row.getServiceDate()), RunDailyAggregate::getRunCount),
                            rateOf(onTime, started),
                            sum(mine, row -> period.inPeriod(row.getServiceDate()), RunDailyAggregate::getDelayedCount),
                            emergencyByAcademy.getOrDefault(academy.getId(), 0L),
                            changeRequestByAcademy.getOrDefault(academy.getId(), 0L));
                }).toList();
    }

    /** 분모가 0 이면 {@code null} — 0 으로 표시해 "0% 정시" 로 읽히지 않게 한다. */
    private static Double rateOf(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static long sum(List<RunDailyAggregate> rows, java.util.function.Predicate<RunDailyAggregate> filter,
            ToLongFunction<RunDailyAggregate> value) {
        return rows.stream().filter(filter).mapToLong(value).sum();
    }
}
