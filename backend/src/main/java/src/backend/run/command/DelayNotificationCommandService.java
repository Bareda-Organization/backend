package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.notification.command.NotificationDraft;
import src.backend.notification.command.NotificationOutbox;
import src.backend.notification.domain.impl.DelaySubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
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
import src.backend.run.repository.DelayNoticeRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 지연 알림 신고(NTF-06, M-05, API_SPEC §4.9) — F3 S1 목표 1~4.
 *
 * <p>이벤트·리스너 간접을 쓰지 않는다({@link src.backend.notification.command.RunStartedNotificationListener}
 * 와 다른 결정) — 응답의 세 불리언({@code notifiedGuardians} 등)이 <b>그 요청 처리 안에서 실제로 몇
 * 명에게 적재했는지</b>를 그대로 반영해야 하는데, 이벤트 발행은 이 서비스가 그 결과를 동기적으로
 * 알 방법을 주지 않는다. 그래서 조회·조립·발신을 이 서비스가 직접 순서대로 한다.
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

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<DelaySubject> delayComposer;

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

        NotificationMessage notificationMessage = delayComposer.compose(new DelaySubject(reason, minutes, message));

        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean notifiedStaff = appendToStaff(run, notificationMessage, now);
        List<Long> studentIds = pendingStudentIdsOf(run);
        boolean notifiedGuardians = false;
        boolean notifiedStudents = false;
        if (!studentIds.isEmpty()) {
            notifiedGuardians = appendToGuardians(run, notificationMessage, studentIds, now);
            notifiedStudents = appendToStudents(run, notificationMessage, studentIds, now);
        }

        delayNoticeRepository.save(DelayNotice.onSend(runId, requester.accountId(), minutes, reason, message, now));

        return new DelayResponse(notifiedGuardians, notifiedStudents, notifiedStaff);
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

    /** 관계자 — 그 학원 재직 전원({@link src.backend.notification.command.RunStartedNotificationListener}과 같은 대상 규칙). */
    private boolean appendToStaff(Run run, NotificationMessage message, OffsetDateTime now) {
        List<AcademyStaffAccountView> staff = academyStaffRepository.findActiveAccountsByAcademyId(run.getAcademyId());
        for (AcademyStaffAccountView recipient : staff) {
            notificationOutbox.append(new NotificationDraft(run.getAcademyId(), recipient.accountId(),
                    recipient.name(), Role.STAFF, NotificationType.DELAY, message.title(), message.body(),
                    dedupKey(run.getId(), recipient.accountId(), now)));
        }
        return !staff.isEmpty();
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

    /** 학부모 — 학생 1명당 첫 보호자 1명({@link src.backend.notification.command.RunStartedNotificationListener}과 같은 규칙). */
    private boolean appendToGuardians(Run run, NotificationMessage message, List<Long> studentIds, OffsetDateTime now) {
        List<GuardianAccountRecipient> guardians = guardianStudentRepository
                .findGuardianAccountsByAcademyId(run.getAcademyId(), studentIds);
        Map<Long, GuardianAccountRecipient> firstGuardianPerStudent = new LinkedHashMap<>();
        for (GuardianAccountRecipient guardian : guardians) {
            firstGuardianPerStudent.putIfAbsent(guardian.getStudentId(), guardian);
        }
        for (Map.Entry<Long, GuardianAccountRecipient> entry : firstGuardianPerStudent.entrySet()) {
            GuardianAccountRecipient guardian = entry.getValue();
            notificationOutbox.append(new NotificationDraft(run.getAcademyId(), guardian.getAccountId(),
                    guardian.getName(), Role.PARENT, NotificationType.DELAY, message.title(), message.body(),
                    dedupKey(run.getId(), entry.getKey(), now)));
        }
        return !firstGuardianPerStudent.isEmpty();
    }

    /** 학생 — 계정이 연결된 학생만. */
    private boolean appendToStudents(Run run, NotificationMessage message, List<Long> studentIds, OffsetDateTime now) {
        List<Student> students = studentRepository
                .findAllByIdInAndAcademyIdAndAccountIdIsNotNull(studentIds, run.getAcademyId());
        for (Student student : students) {
            notificationOutbox.append(new NotificationDraft(run.getAcademyId(), student.getAccountId(),
                    student.getName(), Role.STUDENT, NotificationType.DELAY, message.title(), message.body(),
                    dedupKey(run.getId(), student.getAccountId(), now)));
        }
        return !students.isEmpty();
    }

    private String dedupKey(Long runId, Long targetId, OffsetDateTime now) {
        return "delay:%d:%d:%s".formatted(runId, targetId, now);
    }
}
