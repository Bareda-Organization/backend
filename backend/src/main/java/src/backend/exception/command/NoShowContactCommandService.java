package src.backend.exception.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.dto.NoShowContactRequest;
import src.backend.exception.dto.NoShowContactResponse;
import src.backend.exception.entity.ContactAttemptType;
import src.backend.exception.entity.ContactResult;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.entity.NoShowContact;
import src.backend.exception.entity.NoShowDecision;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.exception.repository.NoShowContactRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 미승차 연락 시도 기록 커맨드(API_SPEC §4.8, Phase 11 목표 3) — 동승자 전용. 에러 체크 순서는
 * {@code BoardingCommandService.updateStatus} 와 같은 근거로 맞춘다(동승자 판정 → 회차 조회 → 회차
 * 상태 → 탑승자 조회 → 케이스 조회, 다른 학원 자원 존재 여부를 앞 단계에서부터 흘리지 않기 위해).
 */
@Service
@RequiredArgsConstructor
public class NoShowContactCommandService {

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final NoShowCaseRepository noShowCaseRepository;

    private final NoShowContactRepository noShowContactRepository;

    private final Clock clock;

    /**
     * 연락 시도 1회를 기록한다 — {@code result=answered} 면 카운트다운을 멈추고({@code resolveByAnswer}),
     * {@code decision} 이 왔으면 그와 별개로 최종 판단을 기록한다({@code decide}). 두 갱신이 같은
     * 트랜잭션 안에서 겹쳐도 안전하다 — {@link NoShowCase#decide} 는 {@link NoShowDecision#DEPART}
     * 일 때만 종결하므로 {@code RETRY} + {@code answered} 조합에서 되돌리는 일이 없다.
     */
    @Transactional
    public NoShowContactResponse recordAttempt(AuthUser requester, Long runId, Long riderId,
            NoShowContactRequest request) {
        requireEscort(requester);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }
        RunRider rider = runRiderRepository.findByIdAndRunIdAndStatusNot(riderId, runId, RiderStatus.ABSENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.RIDER_NOT_FOUND));
        NoShowCase noShowCase = noShowCaseRepository.findByRunRiderId(rider.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_SHOW_CASE_NOT_FOUND));

        ContactAttemptType attemptType = parseAttemptType(request.attemptType());
        ContactResult result = parseResult(request.result());
        NoShowDecision decision = parseDecision(request.decision());
        OffsetDateTime now = OffsetDateTime.now(clock);

        noShowContactRepository.save(NoShowContact.forAttempt(noShowCase.getId(), attemptType, result, decision,
                requester.accountId(), now));

        if (result == ContactResult.ANSWERED) {
            noShowCase.resolveByAnswer(now);
        }
        if (decision != null) {
            noShowCase.decide(decision, now);
        }

        return new NoShowContactResponse(noShowCase.getId(), lower(attemptType), lower(result),
                decision == null ? null : lower(decision), now, noShowCase.getResolvedAt());
    }

    /** C-06 과 같은 근거 — 동승자가 아니면 회차·탑승자 조회보다 먼저 걸린다. */
    private void requireEscort(AuthUser requester) {
        if (requester.role() != Role.ESCORT) {
            throw new BusinessException(ErrorCode.ESCORT_ONLY);
        }
    }

    /** §4.8 표가 허용하는 값은 {@code call} · {@code message} 뿐 — 그 외는 422. */
    private static ContactAttemptType parseAttemptType(String value) {
        return switch (value) {
            case "call" -> ContactAttemptType.CALL;
            case "message" -> ContactAttemptType.MESSAGE;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }

    /** §4.8 표가 허용하는 값은 {@code answered} · {@code no_answer} 뿐 — 그 외는 422. */
    private static ContactResult parseResult(String value) {
        return switch (value) {
            case "answered" -> ContactResult.ANSWERED;
            case "no_answer" -> ContactResult.NO_ANSWER;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }

    /** {@code decision} 은 선택이라 {@code null}·빈 문자열은 통과시킨다 — 값이 있으면 §4.8 표로 검증한다. */
    private static NoShowDecision parseDecision(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value) {
            case "depart" -> NoShowDecision.DEPART;
            case "retry" -> NoShowDecision.RETRY;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
