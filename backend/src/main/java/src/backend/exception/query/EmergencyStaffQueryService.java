package src.backend.exception.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import src.backend.global.security.AuthUser;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.ManagerRepository;

/**
 * 학원 관계자 화면의 비상 알림 목록 조회(Phase 11 T2 목표 10) — {@code emergency_alert} 는 발신자
 * 연락처를 컬럼으로 갖지 않으므로({@link EmergencyStaffItemResponse} 자바독) 이 조회가 매번
 * {@code raisedBy}(manager.id) 를 일괄 재조회해 이름·전화·역할을 채운다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmergencyStaffQueryService {

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final ManagerRepository managerRepository;

    private final AccountRepository accountRepository;

    public EmergencyStaffListResponse list(AuthUser requester) {
        List<EmergencyAlert> alerts = emergencyAlertRepository
                .findAllByAcademyIdOrderByReceivedAtDesc(requester.academyId());

        Map<Long, Manager> managersById = managerRepository
                .findAllByIdIn(alerts.stream().map(EmergencyAlert::getRaisedBy).toList())
                .stream()
                .collect(Collectors.toMap(Manager::getId, m -> m));

        Map<Long, Account> ackersById = accountRepository
                .findAllByIdIn(alerts.stream().map(EmergencyAlert::getAckedBy).filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, a -> a));

        List<EmergencyStaffItemResponse> items = alerts.stream()
                .map(alert -> toItem(alert, managersById.get(alert.getRaisedBy()),
                        alert.getAckedBy() == null ? null : ackersById.get(alert.getAckedBy())))
                .toList();

        long unackedCount = items.stream().filter(item -> !item.acked() && item.canceledAt() == null).count();

        return new EmergencyStaffListResponse(items, unackedCount);
    }

    private EmergencyStaffItemResponse toItem(EmergencyAlert alert, Manager raiser, Account acker) {
        return new EmergencyStaffItemResponse(alert.getId(), alert.getRunId(), alert.getBusNo(),
                alert.getType().name(), alert.getMemo(), alert.getLat(), alert.getLng(), alert.getRiderCount(),
                raiser == null ? null : raiser.getName(), alert.getRaisedByRole().name(),
                raiser == null ? null : raiser.getPhone(), alert.getOccurredAt(), alert.getReceivedAt(),
                alert.isAcked(), acker == null ? null : acker.getName(), alert.getAckedAt(), alert.getCanceledAt());
    }
}
