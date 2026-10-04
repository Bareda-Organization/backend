package src.backend.monitoring.query;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.entity.SignupRequest;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.SignupRequestRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.monitoring.dto.AdminDashboardResponse;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.request.repository.ExpiringChangeRequestRow;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.entity.Run;
import src.backend.run.repository.AcademyRunCount;
import src.backend.run.repository.RunRepository;

/**
 * 대시보드의 "지금 처리할 것"(API_SPEC §6.18 {@code attention}) — 기간과 무관한 지금 상태다. 지연 회차 목록은 오늘 회차 보드에서 받는다.
 * 학원 필터는 차단 계정 수에만 걸지 않는다(계정 단위 값).
 */
@Component
@RequiredArgsConstructor
class AdminDashboardAttentionReader {

    /** 변경 요청 마감이 이 안으로 다가오면 "곧 자동 거절" 로 싣는다(§6.18). */
    static final long EXPIRING_MINUTES = 30;

    private final SignupRequestRepository signupRequestRepository;

    private final AccountRepository accountRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final MovingRunWindowPolicy movingRunWindowPolicy;

    AdminDashboardResponse.Attention read(DashboardScope scope, LocalDate today, OffsetDateTime now,
            List<AdminDashboardResponse.DelayedRun> delayedRuns) {
        List<Long> academyIds = scope.academyIds();
        long confirmFailed = runRepository.countConfirmFailedByAcademy(today).stream()
                .filter(row -> academyIds.contains(row.getAcademyId())).mapToLong(AcademyRunCount::getRunCount).sum();
        return new AdminDashboardResponse.Attention(signupBlockedOf(scope), delayedRuns, expiringOf(scope, now),
                emergencyAlertRepository.countByAcademyIdInAndAckedAtIsNullAndCanceledAtIsNull(academyIds),
                runRepository.countStaleMovingByAcademyIds(academyIds, movingRunWindowPolicy.earliestServiceDate()),
                confirmFailed, accountRepository.countByStatus(AccountStatus.BLOCKED));
    }

    private List<AdminDashboardResponse.SignupBlocked> signupBlockedOf(DashboardScope scope) {
        List<SignupRequest> requests = signupRequestRepository.findBlockedStaffSignups(scope.academyIds());
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, Account> accounts = accountRepository
                .findAllByIdIn(requests.stream().map(SignupRequest::getAccountId).toList()).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        return requests.stream().filter(request -> accounts.containsKey(request.getAccountId()))
                .map(request -> new AdminDashboardResponse.SignupBlocked(request.getId(),
                        accounts.get(request.getAccountId()).getName(), scope.academyNames().get(request.getAcademyId()),
                        request.getRequestedAt()))
                .toList();
    }

    /** 대기 중 변경 요청을 회차별로 묶어 마감이 30분 안인 것만 — 마감이 빠른 순. */
    private List<AdminDashboardResponse.ExpiringChangeRequests> expiringOf(DashboardScope scope, OffsetDateTime now) {
        List<ExpiringChangeRequestRow> rows = changeRequestRepository.findExpiringByRun(scope.academyIds(), now,
                now.plusMinutes(EXPIRING_MINUTES));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Run> runs = runRepository.findAllByAcademyIdInAndIdIn(scope.academyIds(), rows.stream().map(ExpiringChangeRequestRow::getRunId).toList())
                .stream().collect(Collectors.toMap(Run::getId, Function.identity()));
        Map<Long, Bus> buses = busRepository.findAllByAcademyIdInAndIdIn(scope.academyIds(), runs.values().stream().map(Run::getBusId).distinct().toList())
                .stream().collect(Collectors.toMap(Bus::getId, Function.identity()));
        return rows.stream().map(row -> {
            Run run = runs.get(row.getRunId());
            return new AdminDashboardResponse.ExpiringChangeRequests(run.getId(),
                    scope.academyNames().get(run.getAcademyId()), buses.get(run.getBusId()).getBusNo(),
                    run.getDirection().name().toLowerCase(Locale.ROOT), row.getDeadlineAt(), row.getTotal());
        }).toList();
    }
}
