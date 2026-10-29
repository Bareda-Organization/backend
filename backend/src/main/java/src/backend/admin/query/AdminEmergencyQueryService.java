package src.backend.admin.query;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.admin.dto.AdminEmergencyItemResponse;
import src.backend.admin.dto.AdminEmergencyListResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.query.EmergencyStatusFilter;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.request.PageParams;
import src.backend.manager.dto.AssignedManagerContactView;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 메인 관리자 콘솔의 전 학원 비상 알림 조회 — {@code
 * emergency_alert} 를 {@code academy_id} 로 좁히지 않는 유일한 읽기 경로다({@link
 * EmergencyAlertRepository#findAllByState} 의 {@code @AcademyScopeExempt} 참고).
 *
 * <p>§5.16 상속 필드({@code raisedBy}·{@code position}·{@code contacts}·{@code direction}·{@code
 * ackedBy})를 채우는 방식은 {@link src.backend.exception.query.EmergencyStaffQueryService} 와
 * 같다 — 다른 점은 이 조회가 전 학원을 다루므로 {@code contacts} 조회에 단일 {@code academyId} 를
 * 쓸 수 없고, 신고 1건마다 그 신고 자신의 {@link EmergencyAlert#getAcademyId} 를 넘겨야 한다는
 * 것뿐이다(회차 ID 는 학원마다 별도 시퀀스가 아니므로 학원을 특정하지 않으면 다른 학원의 동일 ID
 * 회차 배치를 잘못 붙일 위험이 있다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminEmergencyQueryService {

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final AcademyRepository academyRepository;

    private final ManagerRepository managerRepository;

    private final AccountRepository accountRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunRepository runRepository;

    private final Clock clock;

    /** 페이징 없는 목록의 행 수 상한(BR-228) — 최근 신고부터 자른다. */
    private static final Limit LIST_LIMIT = Limit.of(PageParams.UNPAGED_LIST_MAX);

    /**
     * {@code status}·{@code academy_id} 쿼리 필터(§6.11) — 프런트(academy-web
     * {@code emergencies.ts})가 이미 보내고 있던 값을 서버가 그동안 무시하고 있었다(Ruling 294).
     * {@code status} 값·기본값·우선순위는 {@link EmergencyStatusFilter} 가 정의한다(§5.16 과
     * 공유, Ruling 297). {@code academy_id} 는 존재 검증을 하지 않는다 — §6.11 에러표가 "§1.11
     * 공통 항목 외 고유 에러 부재"라 {@code AuditLogQueryService} 의 404 패턴을 옮기지 않았다
     * (존재하지 않는 학원 ID 를 주면 단순히 빈 목록이 된다).
     */
    public AdminEmergencyListResponse list(String status, Long academyId) {
        EmergencyStatusFilter statusFilter = EmergencyStatusFilter.from(status);
        List<EmergencyAlert> alerts = loadAlerts(statusFilter, academyId);
        AdminEmergencyLookups lookups = loadLookups(alerts);

        OffsetDateTime now = OffsetDateTime.now(clock);

        List<AdminEmergencyItemResponse> items = alerts.stream()
                .map(alert -> AdminEmergencyItemAssembler.toItem(alert, lookups, now))
                .toList();

        // 배지는 필터(상태·학원)와 무관하다(BR-066) — 목록에서 세면 "확인됨" 탭에서 0 이 된다.
        long unackedCount = emergencyAlertRepository.countByAckedAtIsNullAndCanceledAtIsNull();

        return new AdminEmergencyListResponse(items, unackedCount, new AdminEmergencyListResponse.Counts(
                countOf(EmergencyStatusFilter.OPEN, academyId), countOf(EmergencyStatusFilter.ACKED, academyId),
                countOf(EmergencyStatusFilter.CANCELED, academyId)));
    }

    /** 상태·학원 조건을 쿼리로 건다(BR-087) — 무기한 보존 테이블을 통째로 읽어 거르지 않는다. */
    private List<EmergencyAlert> loadAlerts(EmergencyStatusFilter statusFilter, Long academyId) {
        return academyId == null
                ? emergencyAlertRepository.findAllByState(statusFilter.name(), LIST_LIMIT)
                : emergencyAlertRepository.findAllByAcademyIdAndState(academyId, statusFilter.name(), LIST_LIMIT);
    }

    /** 행 조립에 필요한 학원·배치 인력·매니저·확인자·회차를 종류별로 한 번씩 일괄 조회한다. */
    private AdminEmergencyLookups loadLookups(List<EmergencyAlert> alerts) {
        Map<Long, Academy> academiesById = academyRepository
                .findAllById(alerts.stream().map(EmergencyAlert::getAcademyId).toList())
                .stream()
                .collect(Collectors.toMap(Academy::getId, a -> a));
        Map<Long, List<AssignedManagerContactView>> contactsByRunId = loadContactsByRunId(alerts);
        Map<Long, Manager> managersById = loadManagersById(alerts, contactsByRunId);
        Map<Long, Account> ackersById = accountRepository
                .findAllByIdIn(alerts.stream().map(EmergencyAlert::getAckedBy).filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, a -> a));
        Map<Long, Run> runsById = runRepository
                .findAllByIdIn(alerts.stream().map(EmergencyAlert::getRunId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Run::getId, r -> r));
        return new AdminEmergencyLookups(academiesById, managersById, ackersById, runsById, contactsByRunId);
    }

    /** 배치 인력은 학원마다 한 번에 읽는다(BR-087) — 신고마다 읽으면 쿼리 수가 누적 신고 수에 비례한다. */
    private Map<Long, List<AssignedManagerContactView>> loadContactsByRunId(List<EmergencyAlert> alerts) {
        Map<Long, List<AssignedManagerContactView>> contactsByRunId = new HashMap<>();
        alerts.stream()
                .collect(Collectors.groupingBy(EmergencyAlert::getAcademyId,
                        Collectors.mapping(EmergencyAlert::getRunId, Collectors.toSet())))
                .forEach((alertAcademyId, runIds) -> assignmentRepository
                        .findAssignedManagerContacts(alertAcademyId, runIds)
                        .forEach(view -> contactsByRunId.computeIfAbsent(view.runId(), key -> new ArrayList<>())
                                .add(view)));
        return contactsByRunId;
    }

    /** 신고자와 배치 인력의 매니저 행을 한 번에 읽는다. */
    private Map<Long, Manager> loadManagersById(List<EmergencyAlert> alerts,
            Map<Long, List<AssignedManagerContactView>> contactsByRunId) {
        Set<Long> managerIds = new HashSet<>(alerts.stream().map(EmergencyAlert::getRaisedBy).toList());
        contactsByRunId.values().forEach(views -> views.forEach(v -> managerIds.add(v.managerId())));
        return managerRepository.findAllByIdIn(managerIds).stream()
                .collect(Collectors.toMap(Manager::getId, m -> m));
    }

    /** 탭 건수(§6.11 {@code counts}) — {@code status} 쿼리와 무관하게 {@code academy_id} 조건만 걸어 센다. */
    private long countOf(EmergencyStatusFilter state, Long academyId) {
        return academyId == null ? emergencyAlertRepository.countAllByState(state.name())
                : emergencyAlertRepository.countByAcademyIdAndState(academyId, state.name());
    }
}
