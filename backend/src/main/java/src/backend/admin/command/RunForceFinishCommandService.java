package src.backend.admin.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.admin.dto.ForceFinishResponse;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ClientIp;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 운행일이 지난 채 끝나지 않은 이동 중 회차의 강제 종료(API_SPEC §6.17, R47 Ruling 724) — {@code StaleMovingRun} 경보가 가리키는
 * 회차를 메인 관리자가 콘솔에서 닫는다. {@code DEPLOYMENT §11.2} 의 DB 직접 갱신과 같은 동작이다.
 *
 * <p><b>일반 종료({@code Run#finish} 경로)가 따르는 후속을 일부러 따르지 않는다.</b> ① {@code RunEndedEvent} 를 내지 않는다 —
 * 그 이벤트 하나가 학부모·관계자 알림과 {@code run_ended} 방송 둘을 일으키는데, 지난 운행이라 보낼 대상이 아니다. ② 남은 탑승자를
 * 하차 처리하지 않는다 — 지난 운행의 하차 시각을 지어낼 수 없다. ③ 정차지 강제 출발({@code StopDepartureService#forceAllRemaining})도
 * 하지 않는다 — 같은 이유로 출발 시각을 지어내게 된다. 위치 송신 중단·노선 잠금은 이 회차가 이미 처리 집합({@code MovingRunWindowPolicy})
 * 밖이라 닫을 것이 없다.
 *
 * <p>종료는 조건부 UPDATE 한 문장이다({@link RunRepository#finishIfStaleMoving}) — 동승자의 마지막 하차(자동 종료)와 겹쳐도 한쪽만 성공한다.
 * 감사 적재가 같은 트랜잭션이라 적재 실패가 곧 종료 취소다({@code RunForceConfirmCommandService} 와 같은 근거).
 */
@Service
@RequiredArgsConstructor
public class RunForceFinishCommandService {

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final AccountRepository accountRepository;

    private final AuditLogRepository auditLogRepository;

    private final MovingRunWindowPolicy movingRunWindowPolicy;

    private final Clock clock;

    /**
     * 강제 종료를 실행한다 — 미존재 회차는 {@code 404 RUN_NOT_FOUND}, 임시 취소된 회차는 {@code 409 RUN_CANCELED}, 이동 중이 아니면
     * {@code 409 RUN_NOT_MOVING}, 운행일이 오늘·어제면 {@code 409 RUN_NOT_STALE}.
     *
     * <p>먼저 갱신하고 0행일 때만 회차를 다시 읽어 원인을 가른다 — 읽어서 판정한 뒤 갱신하면 그 사이에 마지막 하차가 회차를 끝낼 수 있다.
     * 갱신이 영속성 컨텍스트를 비우므로({@code clearAutomatically}) 그 뒤의 조회는 DB 의 현재 값을 읽는다.
     *
     * @param actorAccountId 강제 종료를 실행하는 메인 관리자 계정 — 감사 행위자
     * @param reason 강제 종료 사유 — 공백뿐인 값은 컨트롤러 진입 전 {@code @NotBlank} 가 이미 거른다
     */
    @Transactional
    public ForceFinishResponse forceFinish(Long runId, Long actorAccountId, String reason) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int finished = runRepository.finishIfStaleMoving(runId, movingRunWindowPolicy.earliestServiceDate(), now);
        if (finished == 0) {
            throw rejectionOf(runId);
        }

        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("방금 종료한 회차가 읽히지 않는다: " + runId));
        long boardedCount = runRiderRepository.countByRunIdAndStatus(runId, RiderStatus.BOARDED);
        String actorLoginId = accountRepository.findById(actorAccountId).map(Account::getLoginId).orElse(null);
        auditLogRepository.save(AuditLog.forRunForceFinish(run.getAcademyId(), actorAccountId, actorLoginId, runId,
                reason, boardedCount, ClientIp.ofCurrentRequest(), now));
        return new ForceFinishResponse(runId, now, boardedCount);
    }

    /** 갱신이 0행이었던 원인 — 취소는 status 를 그대로 두므로 상태 판정보다 먼저 본다(강제 확정과 같은 순서). */
    private BusinessException rejectionOf(Long runId) {
        Run run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return new BusinessException(ErrorCode.RUN_NOT_FOUND);
        }
        if (run.isCanceled()) {
            return new BusinessException(ErrorCode.RUN_CANCELED);
        }
        if (run.getStatus() != RunStatus.MOVING) {
            return new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }
        return new BusinessException(ErrorCode.RUN_NOT_STALE);
    }
}
