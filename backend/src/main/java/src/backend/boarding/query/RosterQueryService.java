package src.backend.boarding.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.audit.service.AuditRecorder;
import src.backend.boarding.dto.ManagerRosterResponse;
import src.backend.boarding.dto.ManagerRosterResponse.Counts;
import src.backend.boarding.dto.ManagerRosterResponse.RosterStudent;
import src.backend.boarding.dto.ManagerRosterResponse.StopGroup;
import src.backend.boarding.dto.StaffRosterItemResponse;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.manager.access.ManagerRunAccess;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianPhone;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 회차 명단 조회 — 매니저 앱(§4.2)과 관계자 웹(§5.4)이 <b>같은 원본</b>({@code run_rider} ·
 * {@code run_stop} · {@code Student})을 서로 다른 응답 모양으로 읽는다(RST-01~04·A-04,
 * Phase 9 목표 6·15).
 *
 * <p>두 메서드가 대칭으로 갈리는 지점이 <b>둘</b>이다 — {@code guardian_phone}
 * 마스킹 여부({@link GuardianPhoneMasker})와 {@code absent} 학생의 노출 여부(매니저 앱은 행 제외
 * · 관계자 웹은 빨강 표시로 존치). 한쪽만 고치면 어느 화면이 깨졌는지 코드만 보고는 알 수 없어
 * 이 클래스 하나에 함께 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RosterQueryService {

    private final ManagerRunAccess managerRunAccess;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final RunRiderRepository runRiderRepository;

    private final StopRepository stopRepository;

    private final StudentRepository studentRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final AuditRecorder auditRecorder;

    /**
     * 매니저 앱의 승하차지별 명단(§4.2) — 확정 전(idle) 회차는 {@code 409 RUN_NOT_CONFIRMED}(명단이
     * 아직 채워지지 않아 빈 배열과 "확정됐는데 비었다" 가 구별되지 않기 때문, {@code ErrorCode} 참고).
     *
     * <p>회차 접근 판정은 {@link ManagerRunAccess#requireAssignedRun} 이 먼저 한다(404 → 403) —
     * 이 상태 검사는 그 다음이다. 접근이 아니라 자원 상태를 묻는 질문이라 별도 계층에 둔다.
     */
    public ManagerRosterResponse managerRoster(AuthUser requester, Long runId) {
        ManagerRunAccess.RunAssignment assigned = managerRunAccess.requireAssignedRun(requester, runId);
        Run run = assigned.run();
        if (run.getStatus() == RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }
        String busNo = busNoOf(requester, run);
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), requester.academyId());
        Map<Long, Student> studentsById = studentsOf(requester, riders);
        Map<Long, String> maskedPhonesById = maskedPhonesOf(requester, studentsById.keySet());
        List<RunStop> boardingStops = boardingStopsOf(requester, run);
        Map<Long, Stop> stopsById = stopRepository
                .findAllByAcademyIdAndIdIn(requester.academyId(), boardingStops.stream().map(RunStop::getStopId).toList())
                .stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        Map<Long, List<RunRider>> ridersByStopId = riders.stream().collect(Collectors.groupingBy(RunRider::getStopId));
        List<StopGroup> stops = boardingStops.stream()
                .map(runStop -> toStopGroup(runStop, stopsById.get(runStop.getStopId()),
                        ridersByStopId.getOrDefault(runStop.getStopId(), List.of()), studentsById, maskedPhonesById))
                .toList();
        recordManagerRosterAudit(requester, run, stops);
        return new ManagerRosterResponse(run.getId(), busNo, lower(run.getDirection().name()), countsOf(riders),
                stops);
    }

    /**
     * 매니저 앱 명단 조회 감사(SYS-01 · FEATURE_SPEC §6.3 · Phase 14 T1 목표 1) — {@code photo_url}·
     * {@code note}·{@code address}(정차지, 학생 승하차지 원문 위치)가 L3 다. {@code guardian_phone} 은
     * 이 응답에서 <b>마스킹된 값</b>이라 감사 대상에서 뺀다(§6.3 L2 마스킹 — "원본" 공개가 아니면
     * L3 의 감사 요건이 붙지 않는다).
     *
     * <p>{@code absent} 학생은 이미 응답({@code stops}) 조립 단계에서 행이 빠졌으므로, 그 학생의
     * L3 필드는 애초에 응답에 실리지 않는다 — {@code student_ids} 를 원본 {@code riders} 가 아니라
     * 조립된 {@code stops} 에서 다시 뽑는 이유다. 응답에 학생이 하나도 없으면(전원 결석·미배정)
     * L3 값이 실제로 실리지 않았으므로 행을 남기지 않는다({@code StudentQueryService.detail} 과
     * 같은 근거).
     */
    private void recordManagerRosterAudit(AuthUser requester, Run run, List<StopGroup> stops) {
        List<String> studentIds = stops.stream()
                .flatMap(stopGroup -> stopGroup.students().stream())
                .map(RosterStudent::studentId)
                .distinct()
                .map(String::valueOf)
                .toList();
        if (studentIds.isEmpty()) {
            return;
        }
        auditRecorder.recordDataAccessRead(requester.academyId(), requester.accountId(), "run_roster", run.getId(),
                Map.of("student_ids", studentIds, "fields", List.of("photo_url", "note", "address")));
    }

    /**
     * 관계자 웹의 호차별 일일 명단(§5.4) — 매니저 앱과 달리 확정 전(idle) 회차도 조회할 수 있고
     * (사양 원문 "진입 차단은 매니저 앱 전용"), 배치되지 않았다는 이유로 막지 않는다 — 학원 관계자는
     * 그 학원의 회차 전체를 볼 권한을 이미 {@code STUDENT_READ_SENSITIVE} 로 가졌다.
     *
     * <p>존재 판정과 학원 범위 판정을 분리한다(API_SPEC §1.5, Ruling 239, 2026-09-03 사용자 판정 ②) —
     * 회차 자체가 없으면 {@code 404 RUN_NOT_FOUND}, 있는데 타 학원 소속이면
     * {@link AcademyScope#assertAccessible} 이 {@code 403 ACADEMY_SCOPE_VIOLATION} 을 던진다. 조회
     * 조건에 학원 id 를 섞으면(옛 {@code findByIdAndAcademyId}) 두 사유가 같은 404 로 뭉개진다.
     */
    public List<StaffRosterItemResponse> staffRoster(AuthUser requester, Long runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        AcademyScope.assertAccessible(requester, run.getAcademyId());
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), requester.academyId());
        Map<Long, Student> studentsById = studentsOf(requester, riders);
        Map<Long, String> rawPhonesById = rawPhonesOf(requester, studentsById.keySet());
        List<Long> stopIds = riders.stream().map(RunRider::getStopId).distinct().toList();
        Map<Long, Stop> stopsById = stopRepository.findAllByAcademyIdAndIdIn(requester.academyId(), stopIds).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        List<StaffRosterItemResponse> items = riders.stream()
                .map(rider -> toStaffItem(rider, studentsById.get(rider.getStudentId()), stopsById.get(rider.getStopId()),
                        rawPhonesById.get(rider.getStudentId())))
                .toList();
        recordStaffRosterAudit(requester, run, items);
        return items;
    }

    /**
     * 관계자 웹 명단 조회 감사(SYS-01 · FEATURE_SPEC §6.3 · Phase 14 T1 목표 1) — {@code guardian_phone}
     * 이 이 응답에서는 <b>원본</b>이다(§5.4 마스킹 대상 밖, {@link StaffRosterItemResponse} 자바독) —
     * §6.3 L3 행이 명명한 "보호자 연락처 원본" 이 바로 이 값이라 감사 대상이다. {@code note} 도 L3.
     * {@code photo_url} 은 이 응답에 부재(§5.4 필드 목록에 없음)라 감사 대상에 넣지 않는다.
     *
     * <p>매니저 앱과 달리 {@code absent} 학생도 행으로 남으므로(자바독의 "행 제외 대상 아님") 원본
     * {@code riders} 를 다시 훑지 않고 <b>조립된 응답</b>에서 바로 뽑아도 결과가 같다 — 그래도
     * "응답에 실제로 실린 값" 원칙을 지키려 응답 리스트를 그대로 쓴다.
     */
    private void recordStaffRosterAudit(AuthUser requester, Run run, List<StaffRosterItemResponse> items) {
        List<String> studentIds = items.stream()
                .map(StaffRosterItemResponse::studentId)
                .distinct()
                .map(String::valueOf)
                .toList();
        if (studentIds.isEmpty()) {
            return;
        }
        auditRecorder.recordDataAccessRead(requester.academyId(), requester.accountId(), "run_roster", run.getId(),
                Map.of("student_ids", studentIds, "fields", List.of("guardian_phone", "note")));
    }

    private StopGroup toStopGroup(RunStop runStop, Stop stopInfo, List<RunRider> ridersAtStop,
            Map<Long, Student> studentsById, Map<Long, String> maskedPhonesById) {
        List<RosterStudent> students = ridersAtStop.stream()
                .filter(rider -> rider.getStatus() != RiderStatus.ABSENT)
                .map(rider -> toRosterStudent(rider, studentsById.get(rider.getStudentId()),
                        maskedPhonesById.get(rider.getStudentId())))
                .toList();
        String name = stopInfo == null ? null : stopInfo.getName();
        String address = stopInfo == null ? null : stopInfo.getAddress();
        return new StopGroup(runStop.getStopId(), runStop.getSeq(), name, address,
                runStop.getChange() == null ? null : lower(runStop.getChange().name()), runStop.getSkipNotice(),
                runStop.getArrivedAt(), students);
    }

    private RosterStudent toRosterStudent(RunRider rider, Student student, String maskedPhone) {
        return new RosterStudent(rider.getId(), rider.getStudentId(), student.getName(), student.getPhotoUrl(),
                student.getClassName(), maskedPhone, student.getNote(), student.isCanGoAlone(),
                lower(rider.getStatus().name()), rider.getChange() == null ? null : lower(rider.getChange().name()));
    }

    private StaffRosterItemResponse toStaffItem(RunRider rider, Student student, Stop stop, String rawPhone) {
        return new StaffRosterItemResponse(rider.getStudentId(), student.getName(), student.getClassName(),
                stop == null ? null : stop.getName(), rawPhone,
                rider.getChange() == null ? null : lower(rider.getChange().name()), lower(rider.getStatus().name()),
                student.getNote());
    }

    private Counts countsOf(List<RunRider> riders) {
        long boarded = riders.stream().filter(rider -> rider.getStatus() == RiderStatus.BOARDED).count();
        long waiting = riders.stream().filter(rider -> rider.getStatus() == RiderStatus.WAITING).count();
        long noShow = riders.stream().filter(rider -> rider.getStatus() == RiderStatus.NO_SHOW).count();
        long absentN = riders.stream().filter(rider -> rider.getStatus() == RiderStatus.ABSENT).count();
        return new Counts(boarded, waiting, noShow, absentN);
    }

    /** 확정 노선의 정차 항목 중 <b>학생 승하차지</b>(RTE-10 강제 경유지 제외)만 순번대로 고른다. */
    private List<RunStop> boardingStopsOf(AuthUser requester, Run run) {
        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return List.of();
        }
        List<RunStop> runStops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId,
                requester.academyId());
        return runStops.stream().filter(stop -> stop.getStopId() != null).toList();
    }

    private Map<Long, Student> studentsOf(AuthUser requester, List<RunRider> riders) {
        List<Long> studentIds = riders.stream().map(RunRider::getStudentId).distinct().toList();
        return studentRepository.findAllByAcademyIdAndIdIn(requester.academyId(), studentIds).stream()
                .collect(Collectors.toMap(Student::getId, student -> student));
    }

    private Map<Long, String> maskedPhonesOf(AuthUser requester, Set<Long> studentIds) {
        return phonesOf(requester, studentIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> GuardianPhoneMasker.mask(entry.getValue())));
    }

    private Map<Long, String> rawPhonesOf(AuthUser requester, Set<Long> studentIds) {
        return phonesOf(requester, studentIds);
    }

    /** 학생 1명에 보호자가 여럿이면 조회가 이미 고정한 정렬의 <b>첫 값</b>만 남긴다(원본과 같은 근거). */
    private Map<Long, String> phonesOf(AuthUser requester, Set<Long> studentIds) {
        List<GuardianPhone> phones = guardianStudentRepository.findGuardianPhonesByAcademyId(requester.academyId(),
                studentIds.stream().toList());
        Map<Long, String> result = new LinkedHashMap<>();
        for (GuardianPhone phone : phones) {
            result.putIfAbsent(phone.getStudentId(), phone.getPhone());
        }
        return result;
    }

    private String busNoOf(AuthUser requester, Run run) {
        return busRepository.findByIdAndAcademyId(run.getBusId(), requester.academyId())
                .map(Bus::getBusNo)
                .orElse(null);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
