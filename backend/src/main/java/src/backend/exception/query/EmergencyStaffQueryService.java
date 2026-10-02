package src.backend.exception.query;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.exception.dto.EmergencyStaffItemResponse;
import src.backend.exception.dto.EmergencyStaffListResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.common.LowerCaseFormatter;
import src.backend.global.request.ApiValues;
import src.backend.global.request.PageParams;
import src.backend.global.security.AuthUser;
import src.backend.manager.dto.AssignedManagerContactView;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 학원 관계자 화면의 비상 알림 목록 조회(Phase 11 T2 목표 10, Phase 13 목표 13, §5.16 목록
 * 필터는 R7 목표 1) — {@code emergency_alert} 는 발신자 연락처를 컬럼으로 갖지 않으므로({@link
 * EmergencyStaffItemResponse} 자바독) 이 조회가 매번 {@code raisedBy}(manager.id) 를 일괄
 * 재조회해 이름·전화·역할을 채운다.
 *
 * <p>{@code contacts}(그 회차 배치 기사·동승자)는 {@link AssignmentRepository#findAssignedManagerContacts} 로
 * 목록의 회차 전부를 한 번에 읽는다(BR-087) — 회차마다 읽으면 무기한 보존되는 신고가 쌓일수록 호출 한 번의
 * 쿼리 수가 늘어난다. 그 조회는 삭제된 매니저도 실으므로 여기서 거른다. 상태·날짜 필터도 쿼리로 건다.
 *
 * <p>{@code status} 값·기본값·우선순위는 {@link EmergencyStatusFilter} 가 정의한다(§6.11 과
 * 공유, Ruling 297). {@code date} 필터는 {@code received_at} 의 날짜 성분이다 — {@code
 * occurred_at} 가 아니다. §5.16 응답의 {@code raised_at} 자체가 {@code received_at} 이고({@link
 * EmergencyStaffItemResponse} 자바독 — {@code occurred_at} 은 클라이언트 자기 신고라 응답에서도
 * 제외된다), 같은 관례를 쓰는 §5.20({@link ExceptionReportQueryService} 자바독의 {@code
 * reported_at})과도 일치한다. 학원 자정 경계는 {@link Clock#getZone()}(Asia/Seoul, {@code
 * ClockConfig})으로 환산한다.
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

    private final Clock clock;

    /** 페이징 없는 목록의 행 수 상한(BR-228) — 최근 신고부터 자른다. */
    private static final Limit LIST_LIMIT = Limit.of(PageParams.UNPAGED_LIST_MAX);

    /** 관계자용 비상 알림 목록(§5.16) — 상태·날짜로 거르고, 배치된 매니저 연락처를 함께 채운다. */
    public EmergencyStaffListResponse list(AuthUser requester, String status, String date) {
        EmergencyStatusFilter statusFilter = EmergencyStatusFilter.from(status);
        LocalDate parsedDate = ApiValues.date(date);

        List<EmergencyAlert> alerts = alertsOf(requester.academyId(), statusFilter, parsedDate);

        Map<Long, List<AssignedManagerContactView>> contactsByRunId = alerts.isEmpty() ? Map.of()
                : assignmentRepository.findAssignedManagerContacts(requester.academyId(),
                                alerts.stream().map(EmergencyAlert::getRunId).distinct().toList())
                        .stream()
                        .collect(Collectors.groupingBy(AssignedManagerContactView::runId));

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

        // 배지는 필터(상태·날짜)와 무관하다(BR-066) — 목록에서 세면 "확인됨" 탭에서 0 이 된다.
        long unackedCount =
                emergencyAlertRepository.countByAcademyIdAndAckedAtIsNullAndCanceledAtIsNull(requester.academyId());

        return new EmergencyStaffListResponse(items, unackedCount);
    }

    /** {@code date} 생략(null) 이면 날짜 조건 없이, 있으면 {@code receivedAt} 을 학원 자정 경계 구간으로 거른다. */
    private List<EmergencyAlert> alertsOf(Long academyId, EmergencyStatusFilter statusFilter, LocalDate date) {
        if (date == null) {
            return emergencyAlertRepository.findAllByAcademyIdAndState(academyId, statusFilter.name(), LIST_LIMIT);
        }
        ZoneId zone = clock.getZone();
        return emergencyAlertRepository.findAllByAcademyIdAndStateReceivedBetween(academyId, statusFilter.name(),
                date.atStartOfDay(zone).toOffsetDateTime(), date.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                LIST_LIMIT);
    }

    private EmergencyStaffItemResponse toItem(EmergencyAlert alert, Manager raiser, Account acker, Run run,
            List<AssignedManagerContactView> assigned, Map<Long, Manager> managersById) {
        EmergencyStaffItemResponse.RaisedBy raisedBy = new EmergencyStaffItemResponse.RaisedBy(
                raiser == null ? null : raiser.getName(),
                LowerCaseFormatter.lower(alert.getRaisedByRole().name()),
                raiser == null ? null : raiser.getPhone());

        EmergencyStaffItemResponse.Position position = new EmergencyStaffItemResponse.Position(alert.getLat(),
                alert.getLng(), alert.getPositionRecordedAt());

        List<EmergencyStaffItemResponse.Contact> contacts = assigned == null ? List.of()
                : assigned.stream()
                        .filter(v -> isLive(managersById.get(v.managerId())))
                        .map(v -> new EmergencyStaffItemResponse.Contact(v.name(),
                                LowerCaseFormatter.lower(v.role().name()), v.phone()))
                        .toList();

        EmergencyStaffItemResponse.AckedBy ackedBy = acker == null ? null
                : new EmergencyStaffItemResponse.AckedBy(acker.getName(), alert.getAckMemo());

        return new EmergencyStaffItemResponse(alert.getId(), LowerCaseFormatter.lower(alert.getType().name()),
                alert.getMemo(), raisedBy, alert.getRunId(), alert.getBusNo(),
                run == null ? null : LowerCaseFormatter.lower(run.getDirection().name()), position,
                alert.getRiderCount(), contacts, alert.getReceivedAt(), alert.getOccurredAt(), alert.getAckedAt(),
                alert.getCanceledAt(), alert.isAcked(), ackedBy);
    }

    /** 삭제된 매니저는 연락처에서 뺀다 — 회차별로 읽던 옛 조회({@code findAssignedManagerAccounts})와 같은 결과. */
    private boolean isLive(Manager manager) {
        return manager != null && manager.getDeletedAt() == null;
    }
}
