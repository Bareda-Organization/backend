package src.backend.exception.command;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.dto.EmergencyRaiseRequest;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.ManagerRepository;

/** 비상 신고 접수의 조립 단계 — 신고 행 · 발행 이벤트 · 응답의 수신자 수를 만든다(저장 · 발행은 호출부가 한다). */
@Component
@RequiredArgsConstructor
class EmergencyRaiseAssembler {

    private final RunRiderRepository runRiderRepository;

    private final ManagerRepository managerRepository;

    private final AcademyRepository academyRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final AccountRepository accountRepository;

    /** 신고 행을 만든다 — 탑승 인원 조회 · 메모 · 위치 첨부까지 하며 아직 저장하지 않는다. */
    EmergencyAlert buildAlert(EmergencyRaiseContext ctx, Optional<RunPositionRedisValue> cachedPosition) {
        EmergencyRaiseRequest request = ctx.request();
        OffsetDateTime occurredAt = request.occurredAt() == null ? ctx.now() : request.occurredAt();
        int riderCount = Math.toIntExact(runRiderRepository.countByRunId(ctx.runId()));

        EmergencyAlert alert = EmergencyAlert.onRaise(ctx.academyId(), ctx.runId(), ctx.bus().getBusNo(),
                ctx.assignment().getManagerId(), ctx.assignment().getRole(), ctx.type(), riderCount, occurredAt,
                ctx.now(), request.clientKey());
        if (request.memo() != null) {
            alert.attachMemo(request.memo());
        }
        attachLocation(alert, request, occurredAt, cachedPosition);
        return alert;
    }

    /** 저장된 신고로 발행 이벤트를 만든다 — 발신자 이름 · 연락처와 학원 이름을 이 자리에서 조회해 싣는다. */
    EmergencyRaisedEvent buildEvent(EmergencyRaiseContext ctx, EmergencyAlert alert) {
        Assignment assignment = ctx.assignment();
        Manager raiser = managerRepository.findAllByIdIn(List.of(assignment.getManagerId())).stream()
                .findFirst()
                .orElse(null);
        EmergencyRaisedEvent.RaisedBy raisedBy = new EmergencyRaisedEvent.RaisedBy(
                raiser == null ? null : raiser.getName(), assignment.getRole().name().toLowerCase(Locale.ROOT),
                raiser == null ? null : raiser.getPhone());
        EmergencyRaisedEvent.Position position = new EmergencyRaisedEvent.Position(alert.getLat(), alert.getLng());

        String academyName = academyRepository.findById(ctx.academyId()).map(Academy::getName).orElse(null);
        return new EmergencyRaisedEvent(alert.getId(), ctx.academyId(), academyName, ctx.runId(),
                ctx.bus().getBusNo(), ctx.type(), raisedBy, position, alert.getRiderCount(), ctx.now());
    }

    /**
     * 발신 응답 {@code notified}(API_SPEC §4.14) — 이 신고가 실제로 도달할 수신자 수다.
     * {@link src.backend.notification.command.EmergencyNotificationListener} 가 팬아웃하는 대상과
     * 같은 기준(재직 관계자 + 알림을 받는 상태의 메인관리자 전원 — 차단 포함, {@link AccountStatus#NOTIFIABLE})으로 센다 — 그 리스너는 이벤트 발행 후 별도로
     * 실행돼 개수를 되돌려주지 않으므로, 응답을 만드는 이 자리에서 같은 조건으로 다시 센다(판단
     * 근거, 보고서 항목 — 목록을 불러 크기만 쓰지 않고 count 전용 조회를 골랐다. 팬아웃 자체는
     * 이름까지 필요하지만 이 응답은 개수만 필요하다).
     */
    long notifiedCount(Long academyId) {
        return academyStaffRepository.countByAcademyIdAndStatus(academyId, StaffStatus.ACTIVE)
                + accountRepository.countByRoleAndStatusIn(Role.SYSTEM_ADMIN, AccountStatus.NOTIFIABLE);
    }

    /**
     * 발신 시점 위치를 붙인다(§4.14) — 단말이 {@code lat}·{@code lng} 를 둘 다 보냈으면 그 좌표와 발신 시각을,
     * 아니면 트랜잭션 앞에서 읽어 둔 위치 캐시의 최신 좌표를 쓴다(BR-109). 한쪽만 온 좌표는 요청 오류다.
     */
    private void attachLocation(EmergencyAlert alert, EmergencyRaiseRequest request, OffsetDateTime occurredAt,
            Optional<RunPositionRedisValue> cachedPosition) {
        if ((request.lat() == null) != (request.lng() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (request.lat() != null) {
            alert.attachLocation(request.lat(), request.lng(), occurredAt);
            return;
        }
        cachedPosition.ifPresent(position -> alert.attachLocation(position.lat(), position.lng(), position.recordedAt()));
    }
}
