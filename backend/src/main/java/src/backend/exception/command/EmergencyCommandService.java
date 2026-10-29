package src.backend.exception.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.boarding.command.ClientKeyReplay;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.dto.EmergencyAckResponse;
import src.backend.exception.dto.EmergencyCancelResponse;
import src.backend.exception.dto.EmergencyRaiseRequest;
import src.backend.exception.dto.EmergencyRaiseResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.manager.entity.Assignment;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 비상 신고 발신·취소·확인(EXC-04) — {@code emergency_alert} 를 쓰는
 * 유일한 지점이다.
 *
 * <p><b>발신은 확정({@code confirmed}) 이후 회차에만 받는다</b>(API_SPEC §4.14 "발신 시점" · M-15 ·
 * UF-X-08, BR-109) — 확정 전({@code idle}) 회차는 {@code 409 RUN_NOT_CONFIRMED}. 운행 중이 아니어도
 * (확정 뒤 출발 전 차량 이상 등) 받는다.
 *
 * <p><b>{@link #raise} 는 클래스 트랜잭션이 아니라 {@link TransactionTemplate} 으로 쓰기만 감싼다</b> — 위치 캐시(Redis,
 * 500ms 상한)를 읽는 동안 DB 연결과 {@code client_key} 잠금을 쥐면 가장 빨라야 하는 접수가 Redis 지연만큼 늦어진다.
 * 그래서 좌표 없는 요청만 캐시를 <b>트랜잭션 앞에서</b> 읽어 저장 메서드에 넘긴다. {@link #cancel}·{@link #ack} 는
 * 외부 읽기가 없어 메서드 단위 {@code @Transactional} 이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyCommandService {

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    /** 같은 {@code client_key} 재전송의 키 잠금 · 이전 처리 조회 절차(BR-226). */
    private final ClientKeyReplay clientKeyReplay;

    private final EmergencyCancelStep cancelStep;

    private final EmergencyAckStep ackStep;

    private final RunPositionStore runPositionStore;

    private final EmergencyRaiseAssembler raiseAssembler;

    private final TransactionTemplate transactionTemplate;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 비상 신고 접수 — 재전송({@code client_key} 재사용)은 새 행을 만들지 않고 최초 접수
     * 결과를 그대로 돌려준다({@code BoardingCommandService#updateStatus} 와 같은 재생 형태). 재생은 배치·
     * 회차 확인 <b>뒤</b>에, 같은 회차·같은 종류일 때만 한다(BR-078) — 키가 다른 신고에 재사용됐으면 남의
     * 신고(다른 학원 포함)를 돌려주고 이번 신고를 버리는 대신 422 로 거절한다.
     */
    public EmergencyRaiseResponse raise(AuthUser requester, Long runId, EmergencyRaiseRequest request) {
        Optional<RunPositionRedisValue> cachedPosition = request.lat() == null && request.lng() == null
                ? readCachedPosition(runId) : Optional.empty();
        return transactionTemplate.execute(status -> persistRaise(requester, runId, request, cachedPosition));
    }

    /** 같은 키 재전송 — 같은 회차 · 같은 종류일 때만 최초 접수 결과를 돌려주고, 아니면 422 로 거절한다(BR-078). */
    private EmergencyRaiseResponse replayResponse(EmergencyAlert existing, Long runId, EmergencyRaiseRequest request) {
        if (!existing.getRunId().equals(runId)
                || existing.getType() != EmergencyRequestRules.parseType(request.type())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new EmergencyRaiseResponse(String.valueOf(existing.getId()), existing.getReceivedAt(),
                existing.cancelableUntil(), raiseAssembler.notifiedCount(existing.getAcademyId()), true);
    }

    /** 접수의 DB 구간 — 배치·회차 확인 · 재전송 재생 · 신고 저장 · 이벤트 발행이 한 트랜잭션이다. */
    private EmergencyRaiseResponse persistRaise(AuthUser requester, Long runId, EmergencyRaiseRequest request,
            Optional<RunPositionRedisValue> cachedPosition) {
        Assignment assignment = runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
        Run run = findConfirmedRun(requester.academyId(), runId);
        Optional<EmergencyRaiseResponse> replayed = clientKeyReplay.replay(request.clientKey(),
                emergencyAlertRepository::findByClientKey, existing -> replayResponse(existing, runId, request));
        if (replayed.isPresent()) {
            return replayed.get();
        }
        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        EmergencyType type = EmergencyRequestRules.parseType(request.type());
        EmergencyRequestRules.assertMemoForEtc(type, request.memo());
        EmergencyRaiseContext ctx = new EmergencyRaiseContext(requester.academyId(), runId, bus, assignment, type,
                request, OffsetDateTime.now(clock));
        EmergencyAlert alert = raiseAssembler.buildAlert(ctx, cachedPosition);
        emergencyAlertRepository.save(alert);
        eventPublisher.publishEvent(raiseAssembler.buildEvent(ctx, alert));
        return new EmergencyRaiseResponse(String.valueOf(alert.getId()), ctx.now(), alert.cancelableUntil(),
                raiseAssembler.notifiedCount(requester.academyId()), false);
    }

    /** 호출자 학원의 회차를 찾고, 확정 전({@code idle}) 회차는 {@code RUN_NOT_CONFIRMED} 로 거절한다. */
    private Run findConfirmedRun(Long academyId, Long runId) {
        Run run = runRepository.findByIdAndAcademyId(runId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() == RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }
        return run;
    }

    /**
     * 발신 1분 이내 취소 — 배치되지 않은 회차를 지목하면 {@code 403 FORBIDDEN}(§4.14(L1059)
     * "배치되지 않은 회차" 시나리오, Ruling 259(b)), 배치는 맞는데 그 신고가 없거나 다른 회차 것이면
     * {@code 404}, 창을 넘겼으면 {@code 409}, 이미 취소됐으면 그 결과를 그대로 재반환한다(재전송과
     * 같은 이유로 재취소도 멱등하게 둔다 — 취소 응답을 놓친 클라이언트가 다시 눌러도 두 번째
     * {@code canceled_at} 덮어쓰기로 이력이 바뀌지 않는다).
     *
     * <p>배치 확인을 신고 조회보다 먼저 한다({@link #raise} 와 같은 순서) — 신고 조회를 먼저 하면 미배치 회차의 신고 지목이 404 로 답해져, §4.14 가 명시한
     * "배치되지 않은 회차" 시나리오(driverA1 이 미배치 회차의 신고를 지목)가 성립하지 않는다.
     */
    @Transactional
    public EmergencyCancelResponse cancel(AuthUser requester, Long runId, Long emergencyId) {
        return cancelStep.cancel(requester, runId, emergencyId);
    }

    /**
     * 학원 관계자의 확인 처리 — {@code /staff/emergencies/{id}/ack}. 확인 주체는 발신 학원의 관계자뿐이다(Ruling 884) —
     * 메인 관리자는 열람만 하며, {@link Permissions#EMERGENCY_ACK} 를 갖지 않아 컨트롤러에서 {@code 403} 으로 막힌다. 그래서 대상은
     * 호출자의 학원으로 좁혀 찾는다(타 학원 신고는 {@code 404}).
     *
     * <p>취소된 신고는 확인할 수 없다 — {@code 409 EMERGENCY_CANCELED}(Ruling 885 ①). 철회된 신고를 확인하면 발신자에게
     * "학원이 확인했습니다" 가 가기 때문이다.
     *
     * <p>조치 메모({@code ackMemo}, Ruling 541)는 선택이다 — 확인과 같은 조건부 UPDATE 한 문장으로 쓴다. 따로 쓰면 최초 확인자가
     * 아닌 쪽의 메모가 최초 확인자의 기록을 덮을 수 있다.
     *
     * <p>{@code EmergencyAckedEvent.ackedByName} 은 여기서(트랜잭션 안에서) {@link src.backend.account.repository.AccountRepository}
     * 로 이름을 조회해 채운다({@code Ruling 277} — 판단 근거, 보고서 항목) — {@link #raise} 가
     * {@code raisedBy.name} 을 발행 지점에서 조회해 싣는 것과 같은 자리다. 리스너
     * ({@code EmergencyBroadcastListener}) 는 {@code AFTER_COMMIT} 에서 돌아 그 시점엔 트랜잭션이
     * 없으므로 조회를 리스너로 미루지 않는다({@code EmergencyRunQueryService} 가 같은 계정 id 로
     * 이름을 다시 찾는 것과 같은 이유 — {@code emergency_alert} 에 이름 컬럼이 없다).
     */
    @Transactional
    public EmergencyAckResponse ack(AuthUser requester, Long emergencyId, String ackMemo) {
        return ackStep.ack(requester, emergencyId, ackMemo);
    }

    /**
     * 최신 좌표를 읽는다 — Redis 가 죽으면 {@link RunPositionStore} 가 DB 최신 행으로 대체한다(BR-167). 신고 자체는 반드시
     * 성공해야 하는 안전 요구라, 그 밖의 <b>읽기 실패</b>도 위치 없이 접수한다(BR-039) — 실패는 경고 로그로만
     * 남기고 빈 값을 돌려준다. 트랜잭션 앞에서 부르므로 던져도 행이 롤백되지는 않지만 신고가 막히는 것은 마찬가지다.
     */
    private Optional<RunPositionRedisValue> readCachedPosition(Long runId) {
        try {
            return runPositionStore.find(runId);
        } catch (RuntimeException e) {
            log.warn("비상 신고 위치 첨부 실패 — 위치 없이 접수한다. runId={}", runId, e);
            return Optional.empty();
        }
    }
}
