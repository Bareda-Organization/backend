package src.backend.exception.command;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
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
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.query.RunPositionCache;
import src.backend.student.query.RunPositionSnapshot;

/**
 * 비상 신고 발신·취소·확인(EXC-04, Phase 11 T2 목표 5·8·9·10) — {@code emergency_alert} 를 쓰는
 * 유일한 지점이다.
 *
 * <p><b>발신은 확정({@code confirmed}) 이후 회차에만 받는다</b>(API_SPEC §4.14 "발신 시점" · M-15 ·
 * UF-X-08, BR-109) — 확정 전({@code idle}) 회차는 {@code 409 RUN_NOT_CONFIRMED}. 운행 중이 아니어도
 * (확정 뒤 출발 전 차량 이상 등) 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EmergencyCommandService {

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RunRiderRepository runRiderRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    private final ManagerRepository managerRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final AccountRepository accountRepository;

    private final RunPositionCache runPositionCache;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 비상 신고 접수(목표 1·5·8) — 재전송({@code client_key} 재사용)은 새 행을 만들지 않고 최초 접수
     * 결과를 그대로 돌려준다({@code BoardingCommandService#updateStatus} 와 같은 재생 형태). 재생은 배치·
     * 회차 확인 <b>뒤</b>에, 같은 회차·같은 종류일 때만 한다(BR-078) — 키가 다른 신고에 재사용됐으면 남의
     * 신고(다른 학원 포함)를 돌려주고 이번 신고를 버리는 대신 422 로 거절한다.
     */
    public EmergencyRaiseResponse raise(AuthUser requester, Long runId, EmergencyRaiseRequest request) {
        Assignment assignment = runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() == RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }

        Optional<EmergencyAlert> replay = emergencyAlertRepository.findByClientKey(request.clientKey());
        if (replay.isPresent()) {
            EmergencyAlert existing = replay.get();
            if (!existing.getRunId().equals(runId) || existing.getType() != parseType(request.type())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return new EmergencyRaiseResponse(String.valueOf(existing.getId()), existing.getReceivedAt(),
                    existing.cancelableUntil(), notifiedCount(existing.getAcademyId()), true);
        }

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
        attachLocation(alert, runId, request, occurredAt);

        emergencyAlertRepository.save(alert);

        Manager raiser = managerRepository.findAllByIdIn(List.of(assignment.getManagerId())).stream()
                .findFirst()
                .orElse(null);
        EmergencyRaisedEvent.RaisedBy raisedBy = new EmergencyRaisedEvent.RaisedBy(
                raiser == null ? null : raiser.getName(), assignment.getRole().name().toLowerCase(Locale.ROOT),
                raiser == null ? null : raiser.getPhone());
        EmergencyRaisedEvent.Position position = new EmergencyRaisedEvent.Position(alert.getLat(), alert.getLng());

        eventPublisher.publishEvent(new EmergencyRaisedEvent(alert.getId(), requester.academyId(), runId,
                bus.getBusNo(), type, raisedBy, position, alert.getRiderCount(), now));

        return new EmergencyRaiseResponse(String.valueOf(alert.getId()), now, alert.cancelableUntil(),
                notifiedCount(requester.academyId()), false);
    }

    /**
     * 발신 1분 이내 취소(목표 9) — 배치되지 않은 회차를 지목하면 {@code 403 FORBIDDEN}(§4.14(L1059)
     * "배치되지 않은 회차" 시나리오, Ruling 259(b)), 배치는 맞는데 그 신고가 없거나 다른 회차 것이면
     * {@code 404}, 창을 넘겼으면 {@code 409}, 이미 취소됐으면 그 결과를 그대로 재반환한다(재전송과
     * 같은 이유로 재취소도 멱등하게 둔다 — 취소 응답을 놓친 클라이언트가 다시 눌러도 두 번째
     * {@code canceled_at} 덮어쓰기로 이력이 바뀌지 않는다).
     *
     * <p>배치 확인을 신고 조회보다 먼저 한다({@link #raise} 와 같은 순서) — 이전에는 배치를 전혀
     * 확인하지 않고 {@code findByIdAndRunIdAndAcademyId} 복합키만으로 404 를 던져, §4.14 가 명시한
     * "배치되지 않은 회차" 시나리오(driverA1 이 미배치 회차의 신고를 지목)를 결코 만나지 못했다.
     */
    public EmergencyCancelResponse cancel(AuthUser requester, Long runId, Long emergencyId) {
        runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
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
     *
     * <p>{@code EmergencyAckedEvent.ackedByName} 은 여기서(트랜잭션 안에서) {@link AccountRepository}
     * 로 이름을 조회해 채운다({@code Ruling 277} — 판단 근거, 보고서 항목) — {@link #raise} 가
     * {@code raisedBy.name} 을 발행 지점에서 조회해 싣는 것과 같은 자리다. 리스너
     * ({@code EmergencyBroadcastListener}) 는 {@code AFTER_COMMIT} 에서 돌아 그 시점엔 트랜잭션이
     * 없으므로 조회를 리스너로 미루지 않는다({@code EmergencyRunQueryService} 가 같은 계정 id 로
     * 이름을 다시 찾는 것과 같은 이유 — {@code emergency_alert} 에 이름 컬럼이 없다).
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
        if (emergencyAlertRepository.ackIfUnacked(alert.getId(), requester.accountId(), now) == 0) {
            throw new BusinessException(ErrorCode.ALREADY_ACKED); // 동시에 먼저 확인한 쪽이 있다(BR-079)
        }

        String ackedByName = accountRepository.findById(requester.accountId()).map(Account::getName).orElse(null);
        eventPublisher.publishEvent(
                new EmergencyAckedEvent(alert.getId(), alert.getAcademyId(), alert.getRunId(), ackedByName, now));

        return new EmergencyAckResponse(alert.getId(), now);
    }

    /**
     * 발신 응답 {@code notified}(목표 1, API_SPEC §4.14) — 이 신고가 실제로 도달할 수신자 수다.
     * {@link src.backend.notification.command.EmergencyNotificationListener} 가 팬아웃하는 대상과
     * 같은 기준(재직 관계자 + 활성 메인관리자 전원)으로 센다 — 그 리스너는 이벤트 발행 후 별도로
     * 실행돼 개수를 되돌려주지 않으므로, 응답을 만드는 이 자리에서 같은 조건으로 다시 센다(판단
     * 근거, 보고서 항목 — 목록을 불러 크기만 쓰지 않고 count 전용 조회를 골랐다. 팬아웃 자체는
     * 이름까지 필요하지만 이 응답은 개수만 필요하다).
     */
    private long notifiedCount(Long academyId) {
        return academyStaffRepository.countByAcademyIdAndStatus(academyId, StaffStatus.ACTIVE)
                + accountRepository.countByRoleAndStatus(Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    /**
     * 발신 시점 위치를 붙인다(§4.14) — 단말이 {@code lat}·{@code lng} 를 둘 다 보냈으면 그 좌표와 발신 시각을,
     * 아니면 위치 캐시의 최신 좌표를 쓴다(BR-109). 한쪽만 온 좌표는 요청 오류다.
     */
    private void attachLocation(EmergencyAlert alert, Long runId, EmergencyRaiseRequest request,
            OffsetDateTime occurredAt) {
        if ((request.lat() == null) != (request.lng() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (request.lat() != null) {
            alert.attachLocation(request.lat(), request.lng(), occurredAt);
            return;
        }
        attachLocationIfCached(alert, runId);
    }

    /**
     * 위치 캐시(Redis, T1 계약)에 값이 있을 때만 붙인다 — 신고 자체는 반드시 성공해야 하는 안전 요구
     * (목표 8)라, 값이 비었을 때뿐 아니라 <b>읽기 실패</b>(Redis 연결·시간 초과·값 형식 불일치)도 위치 없이
     * 접수한다(BR-039). 실패는 경고 로그로만 남긴다 — 여기서 던지면 {@code emergency_alert} 행까지 롤백된다.
     */
    private void attachLocationIfCached(EmergencyAlert alert, Long runId) {
        Optional<RunPositionSnapshot> snapshot;
        try {
            snapshot = runPositionCache.find(runId);
        } catch (RuntimeException e) {
            log.warn("비상 신고 위치 첨부 실패 — 위치 없이 접수한다. runId={}", runId, e);
            return;
        }
        snapshot.ifPresent(position -> alert.attachLocation(position.lat(), position.lng(), position.recordedAt()));
    }

    /**
     * "발신 후 1분 이내" 의 기준 시각은 {@code occurredAt}(클라이언트가 신고했다고 주장하는 시각,
     * 조작 가능)이 아니라 {@code receivedAt}(서버가 실제로 접수한 시각)이다 — 취소 창을 신뢰할 수
     * 없는 클라이언트 시계에 맡기지 않기 위함이다.
     *
     * <p>경계는 <b>포함</b>이다 — 정확히 60.000초에 취소 요청이 오면 성공으로 본다("1분 이내"를 닫힌
     * 구간으로 읽는 판단, 보고서 항목). {@code compareTo(...) > 0} 만 창 닫힘으로 판정해, 정확히
     * 같은 값은 아직 열린 것으로 남긴다. 기준 창은 {@link EmergencyAlert#CANCEL_WINDOW} 하나를
     * §4.15 {@code cancelable_until} 계산과 공유한다 — 두 곳이 다른 규칙으로 계산되게 두지 않는다.
     */
    private void assertWithinCancelWindow(OffsetDateTime receivedAt, OffsetDateTime now) {
        if (Duration.between(receivedAt, now).compareTo(EmergencyAlert.CANCEL_WINDOW) > 0) {
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
