package src.backend.monitoring.query;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.persistence.AcademyCount;
import src.backend.monitoring.dto.AdminDashboardResponse;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.request.repository.ChangeRequestStatusCount;
import src.backend.run.repository.RunDailyAggregate;
import src.backend.run.repository.RunRepository;

/**
 * 메인 관리자 대시보드 집계(API_SPEC §6.18 {@code GET /admin/dashboard}, Ruling 801) — 기간·학원 범위를 정하고 지표별 읽기 구성 요소를 모아 응답
 * 하나로 조립한다. 로그인 · 시스템 상태 · 차단 계정은 학원 필터를 걸지 않는다(계정·서버 단위 값).
 *
 * <p>클래스에 {@code @Transactional} 을 두지 않는다 — 시스템 상태가 Redis 를 읽는데({@code AdminAcademyLiveQueryService} 와 같은 이유) 그동안 DB
 * 연결을 쥐지 않는다. 저장소 호출마다 짧은 읽기 트랜잭션이 돈다.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardQueryService {

    /** {@code days} 가 받는 값(§6.18). */
    private static final Set<Integer> ALLOWED_DAYS = Set.of(1, 7, 30);

    private static final int DEFAULT_DAYS = 7;

    private final AcademyRepository academyRepository;

    private final RunRepository runRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final AdminDashboardLoginMetrics loginMetrics;

    private final AdminDashboardTodayRuns todayRuns;

    private final AdminDashboardAttentionReader attentionReader;

    private final AdminDashboardEventReader eventReader;

    private final SystemHealthReader healthReader;

    private final Clock clock;

    /**
     * @param requestedDays {@code 1}·{@code 7}·{@code 30}, {@code null} 이면 7 — 그 밖은 {@code 422 VALIDATION_FAILED}
     * @param academyId 주면 그 학원만, 미등록이면 {@code 404 ACADEMY_NOT_FOUND}
     */
    public AdminDashboardResponse dashboard(Integer requestedDays, Long academyId) {
        int days = requestedDays == null ? DEFAULT_DAYS : requestedDays;
        if (!ALLOWED_DAYS.contains(days)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<Academy> academies = academiesOf(academyId);
        DashboardScope scope = new DashboardScope(academies.stream().map(Academy::getId).toList(),
                academies.stream().collect(Collectors.toMap(Academy::getId, Academy::getName, (a, b) -> a,
                        LinkedHashMap::new)));
        OffsetDateTime now = OffsetDateTime.now(clock);
        DashboardPeriod period = new DashboardPeriod(LocalDate.now(clock), days, clock.getZone());

        List<RunDailyAggregate> aggregates = scope.academyIds().isEmpty() ? List.of()
                : runRepository.aggregateDaily(scope.academyIds(), period.queryFrom(), period.today());
        AdminDashboardLoginMetrics.Result logins = loginMetrics.read(period);
        AdminDashboardTodayRuns.Board board = todayRuns.read(scope, period.today());

        return new AdminDashboardResponse(now,
                new AdminDashboardResponse.Period(period.from(), period.today(), days),
                AdminDashboardRunMetrics.runs(aggregates, period), AdminDashboardRunMetrics.onTime(aggregates, period),
                AdminDashboardRunMetrics.delays(aggregates, period), changeRequestsOf(scope, period), logins.logins(),
                AdminDashboardRunMetrics.daily(aggregates, period, logins.successByDay(), logins.failByDay()),
                AdminDashboardRunMetrics.academyRows(aggregates, academies, period, emergencyCountsOf(scope, period),
                        changeRequestCountsOf(scope, period)),
                attentionReader.read(scope, period.today(), now, board.delayedRuns()), board.todayRuns(),
                healthReader.read(), eventReader.read(scope, period.today(), now));
    }

    private List<Academy> academiesOf(Long academyId) {
        if (academyId == null) {
            return academyRepository.findAll();
        }
        return List.of(academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND)));
    }

    /** 기간에 <b>결정된</b> 변경 요청의 결정별 건수(§9.6). */
    private AdminDashboardResponse.ChangeRequests changeRequestsOf(DashboardScope scope, DashboardPeriod period) {
        Map<ChangeRequestStatus, Long> byStatus = changeRequestRepository
                .countDecidedByStatus(scope.academyIds(), period.startOf(period.from()),
                        period.startOf(period.today().plusDays(1)))
                .stream().collect(Collectors.toMap(ChangeRequestStatusCount::getStatus, ChangeRequestStatusCount::getTotal));
        long approved = byStatus.getOrDefault(ChangeRequestStatus.APPROVED, 0L);
        long rejected = byStatus.getOrDefault(ChangeRequestStatus.REJECTED, 0L);
        long autoRejected = byStatus.getOrDefault(ChangeRequestStatus.AUTO_REJECTED, 0L);
        return new AdminDashboardResponse.ChangeRequests(approved, rejected, autoRejected,
                approved + rejected + autoRejected);
    }

    private Map<Long, Long> emergencyCountsOf(DashboardScope scope, DashboardPeriod period) {
        return emergencyAlertRepository.countRaisedByAcademy(scope.academyIds(), period.startOf(period.from()),
                period.startOf(period.today().plusDays(1))).stream()
                .collect(Collectors.toMap(AcademyCount::getAcademyId, AcademyCount::getTotal));
    }

    private Map<Long, Long> changeRequestCountsOf(DashboardScope scope, DashboardPeriod period) {
        return changeRequestRepository.countRequestedByAcademy(scope.academyIds(), period.startOf(period.from()),
                period.startOf(period.today().plusDays(1))).stream()
                .collect(Collectors.toMap(AcademyCount::getAcademyId, AcademyCount::getTotal));
    }
}
