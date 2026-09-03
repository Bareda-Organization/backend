package src.backend.monitoring.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.audit.service.AuditRecorder;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.monitoring.dto.AdminRunRosterResponse;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianPhone;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 메인 관리자 콘솔의 회차별 승하차지·학생 명단 조회(API_SPEC §6.9, O-06, 목표 10·11).
 *
 * <p>L3 조회다 — 사진·연락처를 원문으로 담는다(SYS-01 감사 대상). Phase 14 T1 목표 1 이 이 감사를
 * 채운다(p13-task-t2.md §6 이 남긴 표시가 이 클래스였다).
 *
 * <p><b>행위자를 {@link SecurityContextHolder} 로 얻는다</b> — 이 저장소의 다른 모든 서비스는
 * {@link AuthUser} 를 메서드 인자로 받는데({@code @AuthenticationPrincipal} 이 컨트롤러에서 주입),
 * {@link src.backend.monitoring.controller.AdminRunRosterController#roster} 는 그 주입이 없다. 컨트롤러는
 * 이 태스크의 소유 파일 목록 밖이라(p14-task-t1.md) 인자를 늘리려면 컨트롤러도 고쳐야 하고, 그러면
 * 감사 요건 하나가 소유 경계를 넘는다 — 대신 이 서비스 안에서 인증 컨텍스트를 직접 읽어 그 경계를
 * 지킨다. 이 저장소에 없던 접근 방식을 이 태스크가 새로 들인 것이라 여기 근거를 남긴다.
 *
 * <p>{@code RosterQueryService#phonesOf} 와 판정 방식은 같지만(학생당 첫 보호자 연락처) 그 메서드가
 * {@code private} 이라 직접 재사용할 수 없어(p13-task-t2.md §1 목표 10) 같은 {@link
 * GuardianStudentRepository#findGuardianPhonesByAcademyId} 호출을 이 클래스가 다시 감싼다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminRunRosterQueryService {

    private final RunRepository runRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final StopRepository stopRepository;

    private final RunRiderRepository runRiderRepository;

    private final StudentRepository studentRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final AuditRecorder auditRecorder;

    public AdminRunRosterResponse roster(Long runId) {
        Run run = runRepository.findById(runId).orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        Long academyId = run.getAcademyId();

        List<RunStop> boardingStops = orderedStopsOf(run).stream().filter(stop -> stop.getStopId() != null).toList();
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(runId, academyId);

        Map<Long, Student> studentsById = studentsOf(academyId, riders);
        Map<Long, String> guardianPhonesById = guardianPhonesOf(academyId, studentsById.keySet());
        Map<Long, Stop> stopsById = stopsOf(academyId, boardingStops);
        Map<Long, List<RunRider>> ridersByStopId = riders.stream().collect(Collectors.groupingBy(RunRider::getStopId));

        List<AdminRunRosterResponse.StopGroup> stops = boardingStops.stream()
                .map(runStop -> toStopGroup(runStop, stopsById.get(runStop.getStopId()),
                        ridersByStopId.getOrDefault(runStop.getStopId(), List.of()), studentsById, guardianPhonesById))
                .toList();
        recordAudit(academyId, run.getId(), stops);
        return new AdminRunRosterResponse(stops);
    }

    /**
     * SYS-01 감사(Phase 14 T1 목표 1) — {@code photo_url}·{@code guardian_phone} 이 L3
     * ({@link #toStudent}). {@code student_phone}(학생 본인 번호)은 L3 가 아니다(FEATURE_SPEC §6.3 —
     * 그 항목이 열거한 것은 "보호자 연락처 원본" 이지 학생 본인 번호가 아니다).
     */
    private void recordAudit(Long academyId, Long runId, List<AdminRunRosterResponse.StopGroup> stops) {
        List<String> studentIds = stops.stream()
                .flatMap(stopGroup -> stopGroup.students().stream())
                .map(AdminRunRosterResponse.Student::studentId)
                .distinct()
                .map(String::valueOf)
                .toList();
        if (studentIds.isEmpty()) {
            return;
        }
        AuthUser requester = (AuthUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        auditRecorder.recordDataAccessRead(academyId, requester.accountId(), "run_roster", runId,
                Map.of("student_ids", studentIds, "fields", List.of("photo_url", "guardian_phone")));
    }

    private AdminRunRosterResponse.StopGroup toStopGroup(RunStop runStop, Stop stop, List<RunRider> ridersAtStop,
            Map<Long, Student> studentsById, Map<Long, String> guardianPhonesById) {
        List<AdminRunRosterResponse.Student> students = ridersAtStop.stream()
                .map(rider -> toStudent(rider, studentsById.get(rider.getStudentId()),
                        guardianPhonesById.get(rider.getStudentId())))
                .toList();
        return new AdminRunRosterResponse.StopGroup(runStop.getStopId(), runStop.getSeq(),
                stop == null ? null : stop.getName(), students);
    }

    private AdminRunRosterResponse.Student toStudent(RunRider rider, Student student, String guardianPhone) {
        return new AdminRunRosterResponse.Student(rider.getStudentId(), student.getName(), student.getPhotoUrl(),
                student.getStudentPhone(), guardianPhone, lower(rider.getStatus().name()));
    }

    /**
     * {@code PositionBroadcastListener#currentStopNameOf} 와 같은 조회 경로를 이 서비스의 소유
     * 범위 안에서 다시 계산한다 — 소유 모듈이 다르다는 같은 근거로 재사용하지 않는다.
     */
    private List<RunStop> orderedStopsOf(Run run) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId()).orElse(null);
        if (confirmedRoute == null || confirmedRoute.getCurrentVersionId() == null) {
            return List.of();
        }
        return runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(confirmedRoute.getCurrentVersionId(),
                run.getAcademyId());
    }

    private Map<Long, Student> studentsOf(Long academyId, List<RunRider> riders) {
        List<Long> studentIds = riders.stream().map(RunRider::getStudentId).distinct().toList();
        return studentRepository.findAllByAcademyIdAndIdIn(academyId, studentIds).stream()
                .collect(Collectors.toMap(Student::getId, student -> student));
    }

    private Map<Long, Stop> stopsOf(Long academyId, List<RunStop> boardingStops) {
        List<Long> stopIds = boardingStops.stream().map(RunStop::getStopId).toList();
        return stopRepository.findAllByAcademyIdAndIdIn(academyId, stopIds).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
    }

    /**
     * 학생당 첫 보호자 연락처만 취한다 — {@code RosterQueryService#phonesOf} 와 같은 판정
     * (한 학생에 보호자가 여럿이어도 대표 1건).
     */
    private Map<Long, String> guardianPhonesOf(Long academyId, Set<Long> studentIds) {
        List<GuardianPhone> phones = guardianStudentRepository.findGuardianPhonesByAcademyId(academyId,
                studentIds.stream().toList());
        Map<Long, String> result = new LinkedHashMap<>();
        for (GuardianPhone phone : phones) {
            result.putIfAbsent(phone.getStudentId(), phone.getPhone());
        }
        return result;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
