package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.dto.DelayRequest;
import src.backend.run.dto.DelayResponse;
import src.backend.run.entity.DelayNotice;
import src.backend.run.entity.DelayReason;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.event.DelayNoticeRecipient;
import src.backend.run.event.DelayRequestedEvent;
import src.backend.run.repository.DelayNoticeRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 지연 알림 신고(NTF-06, M-05, API_SPEC §4.9) — F3 S1 목표 1~4.
 *
 * <p>수신자 조회·발신은 알림 모듈의 {@code DelayNotificationListener} 로 넘긴다(F3 S1 라운드 1 —
 * 반려 🔴-A 해소, ARCHITECTURE §3.3 규칙 17 · BRD-04). 이 서비스가 그 모듈을 직접 부르면 승인
 * 트랜잭션 안에서 알림 적재가 돌아, 그 적재가 실패했을 때 지연 신고 자체가 롤백된다 — 그래서
 * {@link ApplicationEventPublisher} 로 {@link DelayRequestedEvent} 만 발행한다.
 *
 * <p>응답의 세 불리언({@code notifiedGuardians} 등)은 <b>리스너 실행 결과에 기대지 않는다</b> — 이
 * 서비스가 이벤트에 실어 보내는 수신자 3집합을 그대로 계산해 그 집합의 비어 있음 여부로 채운다(이벤트
 * 발행은 리스너가 실제로 몇 건을 적재했는지 동기적으로 돌려주지 않는다). 그래서 관계자·학부모·학생
 * 조회 로직은 여전히 이 서비스가 갖고, 리스너는 그 결과를 그대로 받아 적재만 한다.
 *
 * <p>{@code minutes}·{@code reason} 을 여기서 검증하는 이유는 {@link DelayRequest} 자바독과 같다 —
 * 값 도메인 위반(5의 배수가 아님·정의되지 않은 사유 문자열)은 400 이 아니라 422 여야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DelayNotificationCommandService {

    private final RunRepository runRepository;

    private final RunStopRepository runStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunRiderRepository runRiderRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final StudentRepository studentRepository;

    private final DelayNoticeRepository delayNoticeRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    public DelayResponse notifyDelay(AuthUser requester, Long runId, DelayRequest request) {
        runAssignmentAccess.assertAssignedEscort(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }

        int minutes = validateMinutes(request.minutes());
        DelayReason reason = parseReason(request.reason());
        String message = request.message();

        delayNoticeRepository.findFirstByRunIdOrderBySentAtDesc(runId)
                .filter(previous -> previous.isSameContent(minutes, reason, message))
                .ifPresent(previous -> {
                    throw new BusinessException(ErrorCode.DELAY_DUPLICATE);
                });

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<DelayNoticeRecipient> staffRecipients = staffRecipientsOf(run);
        List<Long> studentIds = pendingStudentIdsOf(run);
        List<DelayNoticeRecipient> guardianRecipients = List.of();
        List<DelayNoticeRecipient> studentRecipients = List.of();
        if (!studentIds.isEmpty()) {
            guardianRecipients = guardianRecipientsOf(run, studentIds);
            studentRecipients = studentRecipientsOf(run, studentIds);
        }

        eventPublisher.publishEvent(new DelayRequestedEvent(runId, run.getAcademyId(), minutes, reason, message, now,
                staffRecipients, guardianRecipients, studentRecipients));

        delayNoticeRepository.save(DelayNotice.onSend(runId, requester.accountId(), minutes, reason, message, now));

        return new DelayResponse(!guardianRecipients.isEmpty(), !studentRecipients.isEmpty(), !staffRecipients.isEmpty());
    }

    private int validateMinutes(Integer minutes) {
        if (minutes == null || minutes <= 0 || minutes % 5 != 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return minutes;
    }

    private DelayReason parseReason(String reason) {
        if (reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return switch (reason.toLowerCase(Locale.ROOT)) {
            case "traffic" -> DelayReason.TRAFFIC;
            case "weather" -> DelayReason.WEATHER;
            case "vehicle_check" -> DelayReason.VEHICLE_CHECK;
            case "prev_stop_wait" -> DelayReason.PREV_STOP_WAIT;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }

    /** 관계자 — 그 학원 재직 전원(리스너와 같은 대상 규칙). */
    private List<DelayNoticeRecipient> staffRecipientsOf(Run run) {
        List<AcademyStaffAccountView> staff = academyStaffRepository.findActiveAccountsByAcademyId(run.getAcademyId());
        return staff.stream()
                .map(recipient -> new DelayNoticeRecipient(recipient.accountId(), recipient.name(), recipient.accountId()))
                .toList();
    }

    /**
     * 아직 지나지 않은 승하차지({@code arrivedAt IS NULL})의 탑승자 중, 이미 탑승한 학생과 결석
     * 처리된 학생을 뺀 학생 id(API_SPEC §4.9 수신 범위 · C-02) — {@link RunRiderRepository
     * #findStudentIdsForDelayNotification} 로 위임한다.
     */
    private List<Long> pendingStudentIdsOf(Run run) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        List<RunStop> ordered = runStopRepository
                .findAllByRouteVersionIdAndAcademyIdOrderBySeq(confirmedRoute.getCurrentVersionId(), run.getAcademyId());
        List<Long> pendingStopIds = ordered.stream()
                .filter(stop -> stop.getArrivedAt() == null && stop.getStopId() != null)
                .map(RunStop::getStopId)
                .toList();
        if (pendingStopIds.isEmpty()) {
            return List.of();
        }
        return runRiderRepository.findStudentIdsForDelayNotification(run.getId(), pendingStopIds);
    }

    /** 학부모 — 학생 1명당 첫 보호자 1명(리스너와 같은 규칙). */
    private List<DelayNoticeRecipient> guardianRecipientsOf(Run run, List<Long> studentIds) {
        List<GuardianAccountRecipient> guardians = guardianStudentRepository
                .findGuardianAccountsByAcademyId(run.getAcademyId(), studentIds);
        Map<Long, GuardianAccountRecipient> firstGuardianPerStudent = new LinkedHashMap<>();
        for (GuardianAccountRecipient guardian : guardians) {
            firstGuardianPerStudent.putIfAbsent(guardian.getStudentId(), guardian);
        }
        return firstGuardianPerStudent.entrySet().stream()
                .map(entry -> new DelayNoticeRecipient(entry.getValue().getAccountId(), entry.getValue().getName(),
                        entry.getKey()))
                .toList();
    }

    /** 학생 — 계정이 연결된 학생만. */
    private List<DelayNoticeRecipient> studentRecipientsOf(Run run, List<Long> studentIds) {
        List<Student> students = studentRepository
                .findAllByIdInAndAcademyIdAndAccountIdIsNotNull(studentIds, run.getAcademyId());
        return students.stream()
                .map(student -> new DelayNoticeRecipient(student.getAccountId(), student.getName(), student.getAccountId()))
                .toList();
    }
}
