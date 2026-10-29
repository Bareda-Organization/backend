package src.backend.admin.query;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import src.backend.academy.entity.Academy;
import src.backend.account.entity.Account;
import src.backend.admin.dto.AdminEmergencyItemResponse;
import src.backend.exception.dto.EmergencyStaffItemResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.global.common.LowerCaseFormatter;
import src.backend.manager.dto.AssignedManagerContactView;
import src.backend.manager.entity.Manager;
import src.backend.run.entity.Run;

/**
 * 비상 알림 1건과 일괄 조회 결과({@link AdminEmergencyLookups})로 콘솔 목록의 한 행을 조립한다.
 */
final class AdminEmergencyItemAssembler {

    private AdminEmergencyItemAssembler() {
    }

    /** 신고 1건을 목록 행으로 바꾼다 — 연관 행이 조회 결과에 없으면 해당 필드는 null(연락처는 빈 목록)이다. */
    static AdminEmergencyItemResponse toItem(EmergencyAlert alert, AdminEmergencyLookups lookups,
            OffsetDateTime now) {
        Manager raiser = lookups.managersById().get(alert.getRaisedBy());
        Account acker = alert.getAckedBy() == null ? null : lookups.ackersById().get(alert.getAckedBy());
        Run run = lookups.runsById().get(alert.getRunId());
        EmergencyStaffItemResponse.Position position = new EmergencyStaffItemResponse.Position(alert.getLat(),
                alert.getLng(), alert.getPositionRecordedAt());

        return new AdminEmergencyItemResponse(alert.getId(),
                academyInfo(lookups.academiesById().get(alert.getAcademyId())),
                LowerCaseFormatter.lower(alert.getType().name()), alert.getMemo(), raisedBy(alert, raiser),
                alert.getRunId(), alert.getBusNo(),
                run == null ? null : LowerCaseFormatter.lower(run.getDirection().name()),
                position, alert.getRiderCount(),
                contacts(lookups.contactsByRunId().get(alert.getRunId()), lookups.managersById()),
                alert.getReceivedAt(), alert.getOccurredAt(), alert.isAcked(),
                alert.getAckedAt(), alert.getCanceledAt(), ackedBy(alert, acker), elapsedSeconds(alert, now));
    }

    /**
     * 경과 시간(초) — 취소·확인으로 이미 끝난 신고는 그 시각에서 멈춘다({@link
     * AdminEmergencyItemResponse} 자바독). 아직 열려 있는 신고만 {@code now} 까지 계속 흐른다.
     */
    private static long elapsedSeconds(EmergencyAlert alert, OffsetDateTime now) {
        OffsetDateTime elapsedEnd = alert.getCanceledAt() != null ? alert.getCanceledAt()
                : alert.getAckedAt() != null ? alert.getAckedAt() : now;
        return Duration.between(alert.getReceivedAt(), elapsedEnd).getSeconds();
    }

    private static AdminEmergencyItemResponse.AcademyInfo academyInfo(Academy academy) {
        return academy == null ? null
                : new AdminEmergencyItemResponse.AcademyInfo(academy.getId(), academy.getName(),
                        academy.getContact());
    }

    private static EmergencyStaffItemResponse.RaisedBy raisedBy(EmergencyAlert alert, Manager raiser) {
        return new EmergencyStaffItemResponse.RaisedBy(
                raiser == null ? null : raiser.getName(),
                LowerCaseFormatter.lower(alert.getRaisedByRole().name()),
                raiser == null ? null : raiser.getPhone());
    }

    private static List<EmergencyStaffItemResponse.Contact> contacts(List<AssignedManagerContactView> assigned,
            Map<Long, Manager> managersById) {
        return assigned == null ? List.of()
                : assigned.stream()
                        .filter(v -> isLive(managersById.get(v.managerId())))
                        .map(v -> new EmergencyStaffItemResponse.Contact(v.name(),
                                LowerCaseFormatter.lower(v.role().name()), v.phone()))
                        .toList();
    }

    private static EmergencyStaffItemResponse.AckedBy ackedBy(EmergencyAlert alert, Account acker) {
        return acker == null ? null
                : new EmergencyStaffItemResponse.AckedBy(acker.getName(), alert.getAckMemo());
    }

    /** 삭제된 매니저는 연락처에서 뺀다 — 신고별로 읽던 옛 조회({@code findAssignedManagerAccounts})와 같은 결과. */
    private static boolean isLive(Manager manager) {
        return manager != null && manager.getDeletedAt() == null;
    }
}
