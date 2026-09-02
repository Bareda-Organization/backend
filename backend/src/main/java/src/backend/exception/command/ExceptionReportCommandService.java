package src.backend.exception.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.dto.ExceptionReportCreateRequest;
import src.backend.exception.dto.ExceptionReportCreateResponse;
import src.backend.exception.entity.ExceptionReport;
import src.backend.exception.entity.ExceptionReportType;
import src.backend.exception.event.ExceptionReportedEvent;
import src.backend.exception.repository.ExceptionReportRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 기사·동승자의 현장 예외 보고 등록(API_SPEC §4.13, EXC-02·03).
 *
 * <p>{@code type=guardian_absent} 는 {@code riderId} 가 필수다(§4.13). 필드 자체가 없으면
 * {@code 422 VALIDATION_FAILED}(요청 결함), 값은 있어도 그 회차의 유효한 탑승자가 아니면
 * {@code 404 RIDER_NOT_FOUND}(자원 미존재)로 갈라 던진다 — {@code RiderStatus#ABSENT} 로 이미 명단에서
 * 빠진 탑승자도 {@link RunRiderRepository#findByIdAndRunIdAndStatusNot} 이 같은 404 로 합류시킨다
 * (승하차 처리·§4.6 이 이미 쓰는 것과 같은 근거 — 존재하지 않는 riderId 와 이미 제외된 탑승자를
 * 응답에서 가르지 않는다).
 *
 * <p>관계자 통지(§4.13 "즉시 통지")는 저장과 같은 트랜잭션 안에서 {@link ExceptionReportedEvent}
 * 를 발행하는 형태로 처리한다(Ruling 215 — {@code NotificationType} 에 {@code exception_reported}
 * 값과 {@code V5} CHECK 제약이 추가됐다). 실제 적재는 {@code ExceptionReportNotificationListener}
 * 가 이 이벤트를 구독해 맡는다 — 승인 요청 접수(§9.7 {@code approval_requested})가 같은 형태로
 * {@code ApprovalRequestedEvent} 를 쓰는 것과 같은 이유다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ExceptionReportCommandService {

    private final ExceptionReportRepository exceptionReportRepository;

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    public ExceptionReportCreateResponse report(AuthUser requester, Long runId,
            ExceptionReportCreateRequest request) {
        runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        ExceptionReportType type = ApiValues.reportType(request.type());
        Long runRiderId = type == ExceptionReportType.GUARDIAN_ABSENT
                ? resolveRunRiderId(request.riderId(), run.getId())
                : null;

        OffsetDateTime now = OffsetDateTime.now(clock);
        ExceptionReport report = ExceptionReport.forReport(requester.academyId(), run.getId(), type,
                request.memo(), requester.accountId(), now);
        if (runRiderId != null) {
            report.assignRunRider(runRiderId);
        }
        exceptionReportRepository.save(report);
        eventPublisher.publishEvent(
                new ExceptionReportedEvent(report.getId(), requester.academyId(), run.getId(), now));

        return new ExceptionReportCreateResponse(report.getId(), report.getReportedAt());
    }

    /** {@code guardian_absent} 보고의 {@code riderId} 필수·유효성을 함께 확인한다. */
    private Long resolveRunRiderId(Long riderId, Long runId) {
        if (riderId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "보호자 부재 보고는 rider_id 가 필수입니다");
        }
        RunRider runRider = runRiderRepository.findByIdAndRunIdAndStatusNot(riderId, runId, RiderStatus.ABSENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.RIDER_NOT_FOUND));
        return runRider.getId();
    }
}
