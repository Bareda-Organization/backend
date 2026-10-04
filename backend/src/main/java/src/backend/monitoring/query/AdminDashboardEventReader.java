package src.backend.monitoring.query;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.entity.SignupRequest;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.SignupRequestRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.monitoring.dto.AdminDashboardResponse;
import src.backend.monitoring.dto.AdminDashboardResponse.RecentEvent;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.request.repository.RunPendingCount;
import src.backend.run.entity.DelayNotice;
import src.backend.run.entity.Run;
import src.backend.run.repository.DelayNoticeRepository;
import src.backend.run.repository.RunRepository;

/**
 * 대시보드의 최근 기록(API_SPEC §6.18 {@code recent_events[]}, Ruling 804) — 회차 확정 · 출발 · 종료 · 지연 알림 발송 · 관계자 가입 신청 5종 중
 * 최근 7일 최신 10건. 새 테이블 없이 각 사건의 시각 컬럼({@code run.confirmed_at} · {@code started_at} · {@code finished_at} ·
 * {@code delay_notice.sent_at} · {@code signup_request.requested_at})에서 읽는다. 종류마다 최신 10건씩만 읽어 합친 뒤 10건으로 자른다 — 전체의
 * 최신 10건은 어느 한 종류에서도 10건을 넘지 않는다.
 */
@Component
@RequiredArgsConstructor
class AdminDashboardEventReader {

    static final int LIMIT = 10;

    static final int WINDOW_DAYS = 7;

    private final RunRepository runRepository;

    private final DelayNoticeRepository delayNoticeRepository;

    private final SignupRequestRepository signupRequestRepository;

    private final AccountRepository accountRepository;

    private final BusRepository busRepository;

    private final ChangeRequestRepository changeRequestRepository;

    List<RecentEvent> read(DashboardScope scope, LocalDate today, OffsetDateTime now) {
        OffsetDateTime since = now.minusDays(WINDOW_DAYS);
        // 회차의 운행일은 사건 시각보다 하루 이상 늦지 않다 — 운행일 하한으로 먼저 좁히면 인덱스(학원·운행일)를 탄다
        LocalDate sinceDate = today.minusDays(WINDOW_DAYS + 1L);
        List<Long> academyIds = scope.academyIds();
        Limit limit = Limit.of(LIMIT);

        List<Run> confirmed = runRepository.findRecentlyConfirmed(academyIds, sinceDate, since, limit);
        List<Run> started = runRepository.findRecentlyStarted(academyIds, sinceDate, since, limit);
        List<Run> finished = runRepository.findRecentlyFinished(academyIds, sinceDate, since, limit);
        List<DelayNotice> notices = delayNoticeRepository.findRecent(academyIds, sinceDate, since, limit);
        List<SignupRequest> signups = signupRequestRepository.findRecentStaffSignups(academyIds, since, limit);

        Map<Long, Run> runs = runsOf(academyIds, confirmed, started, finished, notices);
        Map<Long, Bus> buses = busesOf(academyIds, runs.values());
        Map<Long, Long> pendingByRun = changeRequestRepository
                .countPendingByRun(academyIds, confirmed.stream().map(Run::getId).toList()).stream()
                .collect(Collectors.toMap(RunPendingCount::getRunId, RunPendingCount::getTotal));
        Map<Long, Account> accounts = accountRepository
                .findAllByIdIn(signups.stream().map(SignupRequest::getAccountId).toList()).stream()
                .collect(Collectors.toMap(Account::getId, account -> account));

        List<RecentEvent> events = new ArrayList<>();
        confirmed.forEach(run -> events.add(runEvent("run_confirmed", run.getConfirmedAt(), run, scope, buses, null,
                pendingByRun.getOrDefault(run.getId(), 0L))));
        started.forEach(run -> events.add(runEvent("run_started", run.getStartedAt(), run, scope, buses, null, null)));
        finished.forEach(run -> events.add(runEvent("run_finished", run.getFinishedAt(), run, scope, buses, null, null)));
        notices.forEach(notice -> events.add(runEvent("delay_notified", notice.getSentAt(), runs.get(notice.getRunId()),
                scope, buses, notice.getMinutes(), null)));
        signups.stream().filter(signup -> accounts.containsKey(signup.getAccountId()))
                .forEach(signup -> events.add(signupEvent(signup, accounts.get(signup.getAccountId()), scope)));
        return events.stream().sorted(Comparator.comparing(RecentEvent::at).reversed()
                .thenComparing(RecentEvent::kind)).limit(LIMIT).toList();
    }

    /** 사건이 가리키는 회차 — 확정·출발·종료는 이미 읽었고, 지연 알림은 회차를 따로 읽는다. */
    private Map<Long, Run> runsOf(List<Long> academyIds, List<Run> confirmed, List<Run> started, List<Run> finished,
            List<DelayNotice> notices) {
        Map<Long, Run> runs = new HashMap<>();
        confirmed.forEach(run -> runs.put(run.getId(), run));
        started.forEach(run -> runs.put(run.getId(), run));
        finished.forEach(run -> runs.put(run.getId(), run));
        Set<Long> missing = notices.stream().map(DelayNotice::getRunId).filter(id -> !runs.containsKey(id))
                .collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            runRepository.findAllByAcademyIdInAndIdIn(academyIds, missing).forEach(run -> runs.put(run.getId(), run));
        }
        return runs;
    }

    private Map<Long, Bus> busesOf(List<Long> academyIds, java.util.Collection<Run> runs) {
        if (runs.isEmpty()) {
            return Map.of();
        }
        return busRepository.findAllByAcademyIdInAndIdIn(academyIds, runs.stream().map(Run::getBusId).distinct().toList()).stream()
                .collect(Collectors.toMap(Bus::getId, bus -> bus));
    }

    private RecentEvent runEvent(String kind, OffsetDateTime at, Run run, DashboardScope scope, Map<Long, Bus> buses,
            Integer delayMinutes, Long pendingChangeCount) {
        return new RecentEvent(at, kind, scope.academyNames().get(run.getAcademyId()), run.getId(),
                buses.get(run.getBusId()).getBusNo(), run.getDirection().name().toLowerCase(Locale.ROOT), delayMinutes,
                pendingChangeCount, null, null);
    }

    private RecentEvent signupEvent(SignupRequest signup, Account account, DashboardScope scope) {
        return new RecentEvent(signup.getRequestedAt(), "staff_signup_requested",
                scope.academyNames().get(signup.getAcademyId()), null, null, null, null, null, account.getName(),
                signup.getStatus().name().toLowerCase(Locale.ROOT));
    }
}
