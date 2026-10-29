package src.backend.request.query;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.dto.ApprovalSummaryResponse;
import src.backend.request.entity.ChangeRequest;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * 승인 대기 목록 한 페이지의 항목 조립 — 회차·명단·버스·학생·승하차지를 id 목록으로 한 번씩 일괄 조회한 뒤
 * 항목마다 요약을 만든다. 명단은 회차마다 한 번만 읽는다(BR-075, ARCHITECTURE §14 R3).
 */
@Component
@RequiredArgsConstructor
class ApprovalListItemsReader {

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final BusRepository busRepository;

    private final StudentRepository studentRepository;

    private final ApprovalSummaryAssembler summaryAssembler;

    /** 일괄 조회한 항목 조립 재료 — 회차·회차별 명단·버스 번호·학생 이름·승하차지. */
    private record Lookups(Map<Long, Run> runs, Map<Long, List<RunRider>> ridersByRun, Map<Long, String> busNos,
            Map<Long, String> studentNames, Map<Long, Stop> stops, Long academyId) {
    }

    /** 요청 목록의 요약 항목 — 요청 순서를 그대로 지킨다. */
    List<ApprovalSummaryResponse> itemsOf(List<ChangeRequest> requests, Long academyId) {
        Map<Long, Run> runs = runsOf(requests, academyId);
        Map<Long, List<RunRider>> ridersByRun = ridersByRunOf(runs, academyId);
        Map<Long, String> busNos = busNosOf(runs, academyId);
        Map<Long, String> studentNames = studentNamesOf(requests, academyId);
        Map<Long, Stop> stops = summaryAssembler.stopsOf(requests, ridersByRun.values(), academyId);
        Lookups lookups = new Lookups(runs, ridersByRun, busNos, studentNames, stops, academyId);
        return requests.stream().map(cr -> summaryOf(cr, lookups)).toList();
    }

    private Map<Long, Run> runsOf(List<ChangeRequest> requests, Long academyId) {
        return runRepository
                .findAllByIdInAndAcademyId(requests.stream().map(ChangeRequest::getRunId).distinct().toList(), academyId)
                .stream().collect(Collectors.toMap(Run::getId, run -> run));
    }

    private Map<Long, List<RunRider>> ridersByRunOf(Map<Long, Run> runs, Long academyId) {
        Map<Long, List<RunRider>> ridersByRun = runRiderRepository
                .findAllByRunIdInAndAcademyId(runs.keySet(), academyId).stream()
                .collect(Collectors.groupingBy(RunRider::getRunId));
        runs.keySet().forEach(runId -> ridersByRun.putIfAbsent(runId, List.of()));
        return ridersByRun;
    }

    private Map<Long, String> busNosOf(Map<Long, Run> runs, Long academyId) {
        return busRepository
                .findAllByAcademyIdAndIdIn(academyId, runs.values().stream().map(Run::getBusId).distinct().toList())
                .stream().collect(Collectors.toMap(Bus::getId, Bus::getBusNo));
    }

    private Map<Long, String> studentNamesOf(List<ChangeRequest> requests, Long academyId) {
        return studentRepository
                .findAllByAcademyIdAndIdIn(academyId, requests.stream().map(ChangeRequest::getStudentId).distinct()
                        .toList())
                .stream().collect(Collectors.toMap(Student::getId, Student::getName));
    }

    private ApprovalSummaryResponse summaryOf(ChangeRequest cr, Lookups lookups) {
        Run run = Optional.ofNullable(lookups.runs().get(cr.getRunId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        String busNo = Optional.ofNullable(lookups.busNos().get(run.getBusId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        String studentName = Optional.ofNullable(lookups.studentNames().get(cr.getStudentId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        return summaryAssembler.toSummary(new ApprovalSummaryAssembler.SummaryContext(cr, run,
                lookups.ridersByRun().get(run.getId()), lookups.stops(), lookups.academyId()), busNo, studentName);
    }

}
