package src.backend.monitoring.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 메인 관리자 대시보드 집계(API_SPEC §6.18, Ruling 801) — 로그인 뒤 첫 화면이 30초마다 한 번 읽는 응답 하나다. 시스템 상태({@code health})와 최근
 * 기록({@code recentEvents})도 이 응답에 싣고 별도 엔드포인트를 두지 않는다. 필드 정의는 사양 표가 기준이고, 키는 항상 존재하며 해당 없는 값만
 * {@code null} 이다.
 */
public record AdminDashboardResponse(OffsetDateTime asOf, Period period, Runs runs, OnTime onTime, Delays delays,
        ChangeRequests changeRequests, Logins logins, List<Daily> daily, List<AcademyRow> academies,
        Attention attention, List<TodayRun> todayRuns, List<HealthCell> health, List<RecentEvent> recentEvents) {

    /** 오늘(서울)을 끝으로 하는 {@code days} 일. */
    public record Period(LocalDate from, LocalDate to, int days) {
    }

    /** {@code count}·{@code previousCount} 는 미취소 회차 수(오늘의 아직 출발하지 않은 회차 포함), {@code canceledCount} 는 기간의 임시 취소 수. */
    public record Runs(long count, long previousCount, long canceledCount) {
    }

    /** 정시 출발률(Ruling 802) — 분모(시작한 미취소 회차)가 0 이면 {@code rate} 는 {@code null}. {@code targetRate} 는 화면 표시 상수다. */
    public record OnTime(Double rate, long onTimeCount, long startedCount, double targetRate) {
    }

    /** 지연 알림이 1건 이상 나간 회차 수(알림 여러 건이어도 회차는 한 번). {@code peak} 는 기간 중 가장 많은 날, 0건이면 {@code null}. */
    public record Delays(long count, long todayCount, Peak peak) {
    }

    /** 기간 중 지연 회차가 가장 많았던 날과 그 수. */
    public record Peak(LocalDate date, long count) {
    }

    /** 기간에 결정된 변경 요청(§9.6). */
    public record ChangeRequests(long approved, long rejected, long autoRejected, long total) {
    }

    /** 로그인 시도·차단 집계 — 학원 필터를 걸지 않는다(계정·서버 단위 값). */
    public record Logins(long success, long fail, long todaySuccess, long yesterdaySuccess, long blocks,
            long blocksReleased) {
    }

    /** 날짜 오름차순 한 칸 — 기간이 7일보다 짧아도 최근 7일을 싣는다. */
    public record Daily(LocalDate date, long runCount, long delayCount, long loginSuccess, long loginFail) {
    }

    /** 학원별 기간 지표 — {@code onTimeRate} 는 분모가 0 이면 {@code null}. */
    public record AcademyRow(Long academyId, String academyName, long runCount, Double onTimeRate, long delayCount,
            long emergencyCount, long changeRequestCount) {
    }

    /** 지금 처리할 것 — 기간과 무관한 지금 상태. */
    public record Attention(List<SignupBlocked> signupBlocked, List<DelayedRun> delayedRuns,
            List<ExpiringChangeRequests> expiringChangeRequests, long unackedEmergencies, long staleRuns,
            long confirmFailedRuns, long blockedAccounts) {
    }

    /** 재직 관계자가 있어 지금은 승인할 수 없는 대기 중 가입 요청. */
    public record SignupBlocked(Long requestId, String name, String academyName, OffsetDateTime requestedAt) {
    }

    /** 오늘 지연 회차 한 줄 — {@code delayMinutes} 는 §5.18 과 같은 계산이다. */
    public record DelayedRun(Long runId, String academyName, String busNo, String direction, int delayMinutes) {
    }

    /** 마감이 30분 안인 대기 변경 요청의 회차별 묶음. {@code deadlineAt} 은 그 묶음에서 가장 이른 마감. */
    public record ExpiringChangeRequests(Long runId, String academyName, String busNo, String direction,
            OffsetDateTime deadlineAt, long count) {
    }

    /**
     * 오늘 미취소 회차 한 줄. {@code stopsDone}·{@code stopsTotal} 은 확정 뒤 승하차지 도착 수/전체(경유 지점·도착지·건너뛴 정차 제외)이고 확정 전은
     * {@code null}, {@code delayMinutes} 는 {@code moving} 이 아니면 {@code null}.
     */
    public record TodayRun(Long runId, Long academyId, String academyName, String busNo, String direction,
            OffsetDateTime departTime, OffsetDateTime estArrivalTime, String runStatus, OffsetDateTime startedAt,
            OffsetDateTime finishedAt, Integer delayMinutes, Integer stopsDone, Integer stopsTotal,
            long pendingChangeCount, boolean driverAssigned) {
    }

    /** 시스템 상태 한 칸 — {@code key} 는 {@code api}·{@code position}·{@code confirm_batch}·{@code notification}, {@code status} 는 ok·warn·down. */
    public record HealthCell(String key, String status, String detail) {
    }

    /** 최근 운영 사건 한 줄 — 해당 없는 키는 {@code null}. */
    public record RecentEvent(OffsetDateTime at, String kind, String academyName, Long runId, String busNo,
            String direction, Integer delayMinutes, Long pendingChangeCount, String name, String status) {
    }
}
