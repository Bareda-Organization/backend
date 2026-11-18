package src.backend.exception.report;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.entity.ExceptionReport;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * 예외 보고 목록 조립에 필요한 연관 엔티티를 종류마다 쿼리 한 번씩으로 읽는다 — 쿼리 수가 보고 수에 비례하지
 * 않게 하는 일괄 조회 담당(BR-228). 탑승자는 보호자 부재 보고가 가리키는 것만 읽는다.
 */
@Component
@RequiredArgsConstructor
class ExceptionReportLookupLoader {

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RunRiderRepository runRiderRepository;

    private final StudentRepository studentRepository;

    private final ManagerRepository managerRepository;

    private final AccountRepository accountRepository;

    /** 보고 목록에 딸린 회차·버스·보고자·처리자·탑승자·학생을 이 순서로 읽는다. */
    ExceptionReportLookup load(List<ExceptionReport> reports, Long academyId) {
        Map<Long, Run> runsById = runs(reports, academyId);
        Map<Long, Bus> busesById = buses(runsById, academyId);
        Map<Long, Manager> reportersByAccountId = reporters(reports, academyId);
        Map<Long, Account> handlersById = handlers(reports, academyId);
        Map<Long, RunRider> ridersById = riders(reports, academyId);
        Map<Long, Student> studentsById = students(ridersById, academyId);
        return new ExceptionReportLookup(runsById, busesById, reportersByAccountId, handlersById, ridersById,
                studentsById);
    }

    private Map<Long, Run> runs(List<ExceptionReport> reports, Long academyId) {
        List<Long> runIds = reports.stream().map(ExceptionReport::getRunId).distinct().toList();
        return runRepository.findAllByIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.toMap(Run::getId, run -> run));
    }

    private Map<Long, Bus> buses(Map<Long, Run> runsById, Long academyId) {
        return busRepository
                .findAllByAcademyIdAndIdIn(academyId, runsById.values().stream().map(Run::getBusId).distinct().toList())
                .stream().collect(Collectors.toMap(Bus::getId, bus -> bus));
    }

    private Map<Long, Manager> reporters(List<ExceptionReport> reports, Long academyId) {
        return managerRepository
                .findAllByAcademyIdAndAccountIdIn(academyId,
                        reports.stream().map(ExceptionReport::getReportedBy).distinct().toList())
                .stream().collect(Collectors.toMap(Manager::getAccountId, manager -> manager));
    }

    private Map<Long, Account> handlers(List<ExceptionReport> reports, Long academyId) {
        return accountRepository
                .findAllByAcademyIdAndIdIn(academyId, reports.stream().map(ExceptionReport::getHandledBy)
                        .filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Account::getId, account -> account));
    }

    private Map<Long, RunRider> riders(List<ExceptionReport> reports, Long academyId) {
        List<Long> reportedRiderIds = reports.stream().map(ExceptionReport::getRunRiderId).filter(Objects::nonNull)
                .distinct().toList();
        return reportedRiderIds.isEmpty() ? Map.of()
                : runRiderRepository.findAllByIdInAndAcademyId(reportedRiderIds, academyId).stream()
                        .collect(Collectors.toMap(RunRider::getId, rider -> rider));
    }

    private Map<Long, Student> students(Map<Long, RunRider> ridersById, Long academyId) {
        return studentRepository
                .findAllByAcademyIdAndIdIn(academyId,
                        ridersById.values().stream().map(RunRider::getStudentId).distinct().toList())
                .stream().collect(Collectors.toMap(Student::getId, student -> student));
    }
}
