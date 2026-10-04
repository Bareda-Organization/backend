package src.backend.monitoring.query;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.audit.repository.AuditLogRepository;
import src.backend.audit.repository.LoginActionCount;
import src.backend.monitoring.dto.AdminDashboardResponse;

/**
 * 대시보드의 로그인 지표(API_SPEC §6.18) — 접속 이력(§6.13)과 같은 {@code audit_log} 로그인 행에서 성공·실패·차단을 서울 날짜별로 한 번에 읽어
 * 기간 합·오늘/어제·추이 칸으로 나눈다. 학원 필터를 걸지 않는다(계정·서버 단위 값).
 */
@Component
@RequiredArgsConstructor
class AdminDashboardLoginMetrics {

    private static final String SUCCESS = "login_success";

    private static final String FAIL = "login_fail";

    private static final String BLOCK = "block";

    private final AuditLogRepository auditLogRepository;

    /** 로그인 집계 결과 — {@code logins} 와 추이 그래프용 날짜별 성공·실패 수. */
    record Result(AdminDashboardResponse.Logins logins, Map<LocalDate, Long> successByDay,
            Map<LocalDate, Long> failByDay) {
    }

    Result read(DashboardPeriod period) {
        LocalDate readFrom = period.from().isBefore(period.dailyFrom()) ? period.from() : period.dailyFrom();
        List<LoginActionCount> rows = auditLogRepository.countLoginActionsByDay(period.zone().getId(),
                period.startOf(readFrom), period.startOf(period.today().plusDays(1)));
        Map<LocalDate, Long> successByDay = new HashMap<>();
        Map<LocalDate, Long> failByDay = new HashMap<>();
        long blocks = 0;
        for (LoginActionCount row : rows) {
            switch (row.getAction()) {
                case SUCCESS -> successByDay.merge(row.getDay(), row.getTotal(), Long::sum);
                case FAIL -> failByDay.merge(row.getDay(), row.getTotal(), Long::sum);
                case BLOCK -> blocks += period.inPeriod(row.getDay()) ? row.getTotal() : 0;
                default -> { /* unblock 은 시도가 아니다 */ }
            }
        }
        long releasedBlocks = auditLogRepository.countReleasedBlocks(period.startOf(period.from()),
                period.startOf(period.today().plusDays(1)));
        AdminDashboardResponse.Logins logins = new AdminDashboardResponse.Logins(
                inPeriod(successByDay, period), inPeriod(failByDay, period),
                successByDay.getOrDefault(period.today(), 0L), successByDay.getOrDefault(period.today().minusDays(1), 0L),
                blocks, releasedBlocks);
        return new Result(logins, successByDay, failByDay);
    }

    private static long inPeriod(Map<LocalDate, Long> byDay, DashboardPeriod period) {
        return byDay.entrySet().stream().filter(entry -> period.inPeriod(entry.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
    }
}
