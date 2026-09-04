package src.backend.exception.query;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.exception.dto.EmergencyStaffItemResponse;
import src.backend.exception.dto.EmergencyStaffListResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.common.LowerCaseFormatter;
import src.backend.global.security.AuthUser;
import src.backend.manager.dto.AssignedManagerAccountView;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 학원 관계자 화면의 비상 알림 목록 조회(Phase 11 T2 목표 10, Phase 13 목표 13) — {@code
 * emergency_alert} 는 발신자 연락처를 컬럼으로 갖지 않으므로({@link EmergencyStaffItemResponse}
 * 자바독) 이 조회가 매번 {@code raisedBy}(manager.id) 를 일괄 재조회해 이름·전화·역할을 채운다.
 *
 * <p>{@code contacts}(그 회차 배치 기사·동승자)는 {@link AssignmentRepository
 * #findAssignedManagerAccounts} 를 회차별로 호출해 채운다 — 배치 시험 자체가 회차당 소수이므로
 * 회차 수만큼의 추가 조회를 받아들인다({@code manager/} 모듈에 배치 인력을 일괄 조회하는 메서드가
 * 없고, 이 좌석의 쓰기 소유가 그 모듈까지 미치지 않는다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmergencyStaffQueryService {

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final ManagerRepository managerRepository;

    private final AccountRepository accountRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunRepository runRepository;

    public EmergencyStaffListResponse list(AuthUser requester) {
        List<EmergencyAlert> alerts = emergencyAlertRepository
                .findAllByAcademyIdOrderByReceivedAtDesc(requester.academyId());

        Map<Long, List<AssignedManagerAccountView>> contactsByRunId = alerts.stream()
                .map(EmergencyAlert::getRunId)
                .distinct()
                .collect(Collectors.toMap(runId -> runId,
                        runId -> assignmentRepository.findAssignedManagerAccounts(requester.academyId(), runId)));

        Set<Long> managerIds = new HashSet<>(alerts.stream().map(EmergencyAlert::getRaisedBy).toList());
        contactsByRunId.values().forEach(views -> views.forEach(v -> managerIds.add(v.managerId())));

        Map<Long, Manager> managersById = managerRepository.findAllByIdIn(managerIds).stream()
                .collect(Collectors.toMap(Manager::getId, m -> m));

        Map<Long, Account> ackersById = accountRepository
                .findAllByIdIn(alerts.stream().map(EmergencyAlert::getAckedBy).filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, a -> a));

        Map<Long, Run> runsById = runRepository
                .findAllByIdInAndAcademyId(alerts.stream().map(EmergencyAlert::getRunId).distinct().toList(),
                        requester.academyId())
                .stream()
                .collect(Collectors.toMap(Run::getId, r -> r));

        List<EmergencyStaffItemResponse> items = alerts.stream()
                .map(alert -> toItem(alert, managersById.get(alert.getRaisedBy()),
                        alert.getAckedBy() == null ? null : ackersById.get(alert.getAckedBy()),
                        runsById.get(alert.getRunId()), contactsByRunId.get(alert.getRunId()), managersById))
                .toList();

        long unackedCount = items.stream().filter(item -> !item.acked() && item.canceledAt() == null).count();

        return new EmergencyStaffListResponse(items, unackedCount);
    }

    private EmergencyStaffItemResponse toItem(EmergencyAlert alert, Manager raiser, Account acker, Run run,
            List<AssignedManagerAccountView> assigned, Map<Long, Manager> managersById) {
        EmergencyStaffItemResponse.RaisedBy raisedBy = new EmergencyStaffItemResponse.RaisedBy(
                raiser == null ? null : raiser.getName(),
                LowerCaseFormatter.lower(alert.getRaisedByRole().name()),
                raiser == null ? null : raiser.getPhone());

        EmergencyStaffItemResponse.Position position = new EmergencyStaffItemResponse.Position(alert.getLat(),
                alert.getLng(), alert.getPositionRecordedAt());

        List<EmergencyStaffItemResponse.Contact> contacts = assigned == null ? List.of()
                : assigned.stream()
                        .map(v -> new EmergencyStaffItemResponse.Contact(v.name(),
                                LowerCaseFormatter.lower(v.role().name()), managerAt(managersById, v.managerId())))
                        .toList();

        EmergencyStaffItemResponse.AckedBy ackedBy = acker == null ? null
                : new EmergencyStaffItemResponse.AckedBy(acker.getName());

        return new EmergencyStaffItemResponse(alert.getId(), alert.getType().name(), alert.getMemo(), raisedBy,
                alert.getRunId(), alert.getBusNo(),
                run == null ? null : LowerCaseFormatter.lower(run.getDirection().name()), position,
                alert.getRiderCount(), contacts, alert.getReceivedAt(), alert.getAckedAt(), alert.getCanceledAt(),
                alert.isAcked(), ackedBy);
    }

    private String managerAt(Map<Long, Manager> managersById, Long managerId) {
        Manager manager = managersById.get(managerId);
        return manager == null ? null : manager.getPhone();
    }
}
