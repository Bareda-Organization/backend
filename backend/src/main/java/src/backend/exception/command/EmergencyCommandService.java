package src.backend.exception.command;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.dto.EmergencyAckResponse;
import src.backend.exception.dto.EmergencyCancelResponse;
import src.backend.exception.dto.EmergencyRaiseRequest;
import src.backend.exception.dto.EmergencyRaiseResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyAckedEvent;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.entity.Assignment;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.query.RunPositionCache;
import src.backend.student.query.RunPositionSnapshot;

/**
 * 비상 신고 발신·취소·확인(EXC-04, Phase 11 T2 목표 5·8·9·10) — {@code emergency_alert} 를 쓰는
 * 유일한 지점이다.
 *
 * <p><b>발신 시 회차 상태를 확인하지 않는다</b>(판단 근거, 보고서 항목) — {@link RunStartCommandService}
 * 등 다른 회차 커맨드와 달리 이 서비스는 {@code run.status} 를 가드로 쓰지 않는다. 비상 상황은
 * {@code idle}(출발 전 대기 중 차량 이상 발견 등)에서도 일어날 수 있고, 목표 5~11 어디에도 상태
 * 제약이 명시돼 있지 않다 — 배치({@link RunAssignmentAccess#assertAssignedDriverOrEscort})만 통과하면
 * 신고는 언제나 접수돼야 한다는 것이 안전 기능의 기본 전제다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EmergencyCommandService {

    /** 발신 후 취소 가능 창(목표 9) — "1분 이내" 는 경계값을 포함한다({@link #assertWithinCancelWindow} 참고). */
    private static final Duration CANCEL_WINDOW = Duration.ofMinutes(1);

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RunRiderRepository runRiderRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    private final RunPositionCache runPositionCache;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 비상 신고 접수(목표 5·8) — 재전송({@code client_key} 재사용)은 새 행을 만들지 않고 최초 접수
     * 결과를 그대로 돌려준다({@code BoardingCommandService#updateStatus} 와 같은 재생 형태, 가장
     * 먼저 갈리는 분기인 이유도 같다).
     */
    public EmergencyRaiseResponse raise(AuthUser requester, Long runId, EmergencyRaiseRequest request) {
        Optional<EmergencyAlert> replay = emergencyAlertRepository.findByClientKey(request.clientKey());
        if (replay.isPresent()) {
            EmergencyAlert existing = replay.get();
            return new EmergencyRaiseResponse(existing.getId(), existing.getReceivedAt());
        }

        Assignment assignment = runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));

        EmergencyType type = parseType(request.type());
        if (type == EmergencyType.ETC && (request.memo() == null || request.memo().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime occurredAt = request.occurredAt() == null ? now : request.occurredAt();
        int riderCount = runRiderRepository.findAllByRunId(run.getId()).size();

        EmergencyAlert alert = EmergencyAlert.onRaise(requester.academyId(), runId, bus.getBusNo(),
                assignment.getManagerId(), assignment.getRole(), type, riderCount, occurredAt, now,
                request.clientKey());
        if (request.memo() != null) {
            alert.attachMemo(request.memo());
        }
        attachLocationIfCached(alert, runId);

        emergencyAlertRepository.save(alert);

        eventPublisher.publishEvent(
                new EmergencyRaisedEvent(alert.getId(), requester.academyId(), runId, bus.getBusNo(), type, now));

        return new EmergencyRaiseResponse(alert.getId(), now);
    }

    /**
     * 발신 1분 이내 취소(목표 9) — 대상이 다른 회차·학원이거나 없으면 {@code 404}, 창을 넘겼으면
     * {@code 409}, 이미 취소됐으면 그 결과를 그대로 재반환한다(재전송과 같은 이유로 재취소도 멱등하게
     * 둔다 — 취소 응답을 놓친 클라이언트가 다시 눌러도 두 번째 {@code canceled_at} 덮어쓰기로 이력이
     * 바뀌지 않는다).
     */
    public EmergencyCancelResponse cancel(AuthUser requester, Long runId, Long emergencyId) {
        EmergencyAlert alert = emergencyAlertRepository.findByIdAndRunIdAndAcademyId(emergencyId, runId,
                        requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_NOT_FOUND));

        if (alert.isCanceled()) {
            return new EmergencyCancelResponse(alert.getId(), alert.getCanceledAt());
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        assertWithinCancelWindow(alert.getReceivedAt(), now);

        alert.cancel(now);

        eventPublisher.publishEvent(
                new EmergencyCanceledEvent(alert.getId(), requester.academyId(), runId, alert.getBusNo(), now));

        return new EmergencyCancelResponse(alert.getId(), now);
    }

    /**
     * 학원 관계자·메인관리자의 확인 처리(목표 10) — {@code /staff/emergencies/{id}/ack} 하나뿐인
     * 확인 엔드포인트를 두 역할이 공유한다({@link Permissions#EMERGENCY_ACK} 가 이미 둘 다에게
     * 부여돼 있다, {@code RolePermissions}). 조회 범위만 역할에 따라 가른다 — 메인관리자는
     * {@code academyId} 가 없어({@code hasPlatformScope()}) 학원으로 좁힌 조회로는 어떤 신고도 찾지
     * 못해 항상 404 가 나므로, 그 역할만 전 학원 범위로 대상을 찾는다(판단 근거, 보고서 항목).
     */
    public EmergencyAckResponse ack(AuthUser requester, Long emergencyId) {
        EmergencyAlert alert = requester.hasPlatformScope()
                ? emergencyAlertRepository.findById(emergencyId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_NOT_FOUND))
                : emergencyAlertRepository.findByIdAndAcademyId(emergencyId, requester.academyId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_NOT_FOUND));

        if (alert.isAcked()) {
            throw new BusinessException(ErrorCode.ALREADY_ACKED);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        alert.ack(requester.accountId(), now);

        eventPublisher.publishEvent(
                new EmergencyAckedEvent(alert.getId(), alert.getAcademyId(), alert.getRunId(), requester.accountId(),
                        now));

        return new EmergencyAckResponse(alert.getId(), now);
    }

    /**
     * 위치 캐시(Redis, T1 계약)에 값이 있을 때만 붙인다 — 비어 있어도 신고 자체는 반드시 성공해야
     * 하는 안전 요구(목표 8, 판단 근거 — 보고서 항목)라 여기서 예외를 던지지 않고 조용히 건너뛴다.
     */
    private void attachLocationIfCached(EmergencyAlert alert, Long runId) {
        Optional<RunPositionSnapshot> snapshot = runPositionCache.find(runId);
        snapshot.ifPresent(position -> alert.attachLocation(position.lat(), position.lng(), position.recordedAt()));
    }

    /**
     * "발신 후 1분 이내" 의 기준 시각은 {@code occurredAt}(클라이언트가 신고했다고 주장하는 시각,
     * 조작 가능)이 아니라 {@code receivedAt}(서버가 실제로 접수한 시각)이다 — 취소 창을 신뢰할 수
     * 없는 클라이언트 시계에 맡기지 않기 위함이다.
     *
     * <p>경계는 <b>포함</b>이다 — 정확히 60.000초에 취소 요청이 오면 성공으로 본다("1분 이내"를 닫힌
     * 구간으로 읽는 판단, 보고서 항목). {@code compareTo(...) > 0} 만 창 닫힘으로 판정해, 정확히
     * 같은 값은 아직 열린 것으로 남긴다.
     */
    private void assertWithinCancelWindow(OffsetDateTime receivedAt, OffsetDateTime now) {
        if (Duration.between(receivedAt, now).compareTo(CANCEL_WINDOW) > 0) {
            throw new BusinessException(ErrorCode.EMERGENCY_CANCEL_WINDOW_CLOSED);
        }
    }

    private static EmergencyType parseType(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "accident" -> EmergencyType.ACCIDENT;
            case "vehicle_fault" -> EmergencyType.VEHICLE_FAULT;
            case "student_emergency" -> EmergencyType.STUDENT_EMERGENCY;
            case "etc" -> EmergencyType.ETC;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }
}
