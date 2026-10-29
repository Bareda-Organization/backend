package src.backend.run.command;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.dto.DelayNoticeRecipient;
import src.backend.run.entity.Run;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 지연 알림(NTF-06, API_SPEC §4.9)의 수신자 세 집합(관계자 · 학부모 · 학생)을 읽는다 — {@link DelayNotificationCommandService} 가 부르는 같은 트랜잭션 안의 읽기 전용 조회다.
 */
@Component
@RequiredArgsConstructor
class DelayRecipientResolver {

    private final RunStopRepository runStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunRiderRepository runRiderRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final StudentRepository studentRepository;

    /** 관계자 · 학부모 · 학생 수신자를 읽는다 — 아직 지나지 않은 승하차지의 학생이 없으면 학부모 · 학생은 비어 있다. */
    Recipients resolve(Run run) {
        List<DelayNoticeRecipient> staffRecipients = staffRecipientsOf(run);
        List<Long> studentIds = pendingStudentIdsOf(run);
        if (studentIds.isEmpty()) {
            return new Recipients(staffRecipients, List.of(), List.of());
        }
        return new Recipients(staffRecipients, guardianRecipientsOf(run, studentIds), studentRecipientsOf(run, studentIds));
    }

    /** 지연 알림 수신자 세 집합. */
    record Recipients(List<DelayNoticeRecipient> staff, List<DelayNoticeRecipient> guardians,
            List<DelayNoticeRecipient> students) {
    }

    /** 관계자 — 그 학원 재직 전원(리스너와 같은 대상 규칙). */
    private List<DelayNoticeRecipient> staffRecipientsOf(Run run) {
        List<AcademyStaffAccountView> staff = academyStaffRepository.findActiveAccountsByAcademyId(run.getAcademyId());
        return staff.stream()
                .map(recipient -> new DelayNoticeRecipient(recipient.accountId(), recipient.name(), recipient.accountId()))
                .toList();
    }

    /**
     * 아직 지나지 않은 승하차지({@code arrivedAt IS NULL} 이고 {@code departedAt IS NULL} — 도착 없이 강제 출발된 곳도 지난 곳이다)의 탑승자 중 수신 학생 id(API_SPEC §4.9 수신 범위 · C-02) —
     * 등원은 아직 탑승하지 않은 학생({@link RunRiderRepository#findStudentIdsForDelayNotification}), 하원은 아직 하차하지
     * 않은 탑승 중 학생({@link RunRiderRepository#findBoardedStudentIdsForDelayNotification}).
     */
    private List<Long> pendingStudentIdsOf(Run run) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        List<RunStop> ordered = runStopRepository
                .findAllByRouteVersionIdAndAcademyIdOrderBySeq(confirmedRoute.getCurrentVersionId(), run.getAcademyId());
        List<Long> pendingStopIds = ordered.stream()
                .filter(stop -> stop.getArrivedAt() == null && stop.getDepartedAt() == null && stop.getStopId() != null)
                .map(RunStop::getStopId)
                .toList();
        if (pendingStopIds.isEmpty()) {
            return List.of();
        }
        // 하원은 시작 때 전원이 탑승 상태라 "아직 탑승하지 않은 학생" 이 0명이다 — 아직 내리지 않은 학생이 받는다.
        return run.getDirection() == Direction.FROM_ACADEMY
                ? runRiderRepository.findBoardedStudentIdsForDelayNotification(run.getId(), pendingStopIds)
                : runRiderRepository.findStudentIdsForDelayNotification(run.getId(), pendingStopIds);
    }

    /** 학부모 — 학생의 보호자 전원(다른 승하차 알림과 같은 규칙). 수신자 식별은 (보호자 계정, 학생) 짝이다. */
    private List<DelayNoticeRecipient> guardianRecipientsOf(Run run, List<Long> studentIds) {
        return guardianStudentRepository.findGuardianAccountsByAcademyId(run.getAcademyId(), studentIds).stream()
                .map(guardian -> new DelayNoticeRecipient(guardian.getAccountId(), guardian.getName(),
                        guardian.getStudentId()))
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
