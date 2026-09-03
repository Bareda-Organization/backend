package src.backend.admin.query;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.manager.dto.AssignedManagerAccountView;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 메인 관리자 콘솔의 전 학원 비상 알림 조회(Phase 11 목표 11, Phase 13 목표 13) — {@code
 * emergency_alert} 를 {@code academy_id} 로 좁히지 않는 유일한 읽기 경로다({@link
 * EmergencyAlertRepository#findAllByOrderByReceivedAtDesc} 의 {@code @AcademyScopeExempt} 참고).
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

    public AdminEmergencyListResponse list() {
        List<EmergencyAlert> alerts = emergencyAlertRepository.findAllByOrderByReceivedAtDesc();

        Map<Long, Academy> academiesById = academyRepository
                .findAllById(alerts.stream().map(EmergencyAlert::getAcademyId).toList())
                .stream()
                .collect(Collectors.toMap(Academy::getId, a -> a));

        Map<Long, List<AssignedManagerAccountView>> contactsByAlertId = alerts.stream()
                .collect(Collectors.toMap(EmergencyAlert::getId,
                        alert -> assignmentRepository.findAssignedManagerAccounts(alert.getAcademyId(),
                                alert.getRunId())));

        Set<Long> managerIds = new HashSet<>(alerts.stream().map(EmergencyAlert::getRaisedBy).toList());
        contactsByAlertId.values().forEach(views -> views.forEach(v -> managerIds.add(v.managerId())));

        Map<Long, Manager> managersById = managerRepository.findAllByIdIn(managerIds).stream()
                .collect(Collectors.toMap(Manager::getId, m -> m));

        Map<Long, Account> ackersById = accountRepository
                .findAllByIdIn(alerts.stream().map(EmergencyAlert::getAckedBy).filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, a -> a));

        Map<Long, Run> runsById = runRepository
                .findAllByIdIn(alerts.stream().map(EmergencyAlert::getRunId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Run::getId, r -> r));

        OffsetDateTime now = OffsetDateTime.now(clock);

        List<AdminEmergencyItemResponse> items = alerts.stream()
                .map(alert -> toItem(alert, academiesById.get(alert.getAcademyId()),
                        managersById.get(alert.getRaisedBy()),
                        alert.getAckedBy() == null ? null : ackersById.get(alert.getAckedBy()),
                        runsById.get(alert.getRunId()), contactsByAlertId.get(alert.getId()), managersById, now))
                .toList();

        long unackedCount = items.stream().filter(item -> !item.staffAcked() && item.canceledAt() == null).count();

        return new AdminEmergencyListResponse(items, unackedCount);
    }

    /**
     * 경과 시간(목표 11)의 종료 시점 — 취소·확인으로 이미 끝난 신고는 그 시각에서 멈춘다({@link
     * AdminEmergencyItemResponse} 자바독). 아직 열려 있는 신고만 {@code now} 까지 계속 흐른다. 이
     * 계산식은 Phase 13 T3 소유가 아니라 그대로 둔다.
     */
    private AdminEmergencyItemResponse toItem(EmergencyAlert alert, Academy academy, Manager raiser, Account acker,
            Run run, List<AssignedManagerAccountView> assigned, Map<Long, Manager> managersById,
            OffsetDateTime now) {
        OffsetDateTime elapsedEnd = alert.getCanceledAt() != null ? alert.getCanceledAt()
                : alert.getAckedAt() != null ? alert.getAckedAt() : now;
        long elapsedSeconds = Duration.between(alert.getReceivedAt(), elapsedEnd).getSeconds();

        AdminEmergencyItemResponse.AcademyInfo academyInfo = academy == null ? null
                : new AdminEmergencyItemResponse.AcademyInfo(academy.getId(), academy.getName(),
                        academy.getContact());

        AdminEmergencyItemResponse.RaisedBy raisedBy = new AdminEmergencyItemResponse.RaisedBy(
                raiser == null ? null : raiser.getName(), lower(alert.getRaisedByRole().name()),
                raiser == null ? null : raiser.getPhone());

        AdminEmergencyItemResponse.Position position = new AdminEmergencyItemResponse.Position(alert.getLat(),
                alert.getLng(), alert.getPositionRecordedAt());

        List<AdminEmergencyItemResponse.Contact> contacts = assigned == null ? List.of()
                : assigned.stream()
                        .map(v -> new AdminEmergencyItemResponse.Contact(v.name(), lower(v.role().name()),
                                managerAt(managersById, v.managerId())))
                        .toList();

        AdminEmergencyItemResponse.AckedBy ackedBy = acker == null ? null
                : new AdminEmergencyItemResponse.AckedBy(acker.getName());

        return new AdminEmergencyItemResponse(alert.getId(), academyInfo, alert.getType().name(), alert.getMemo(),
                raisedBy, alert.getRunId(), alert.getBusNo(), run == null ? null : lower(run.getDirection().name()),
                position, alert.getRiderCount(), contacts, alert.getReceivedAt(), alert.isAcked(), alert.getAckedAt(),
                alert.getCanceledAt(), ackedBy, elapsedSeconds);
    }

    private String managerAt(Map<Long, Manager> managersById, Long managerId) {
        Manager manager = managersById.get(managerId);
        return manager == null ? null : manager.getPhone();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
