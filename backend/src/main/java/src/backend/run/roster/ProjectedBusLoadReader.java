package src.backend.run.roster;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.command.BusLoadReader;
import src.backend.global.common.LowerCaseFormatter;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * {@link BusLoadReader} 구현 — 확정 회차는 {@code absent} 를 뺀 {@code run_rider} 수, 확정 전 회차는
 * {@link ProjectedRosterReader} 예정 명단 수(정원 판정 BUS-04 와 같은 규칙, BR-116).
 */
@Component
@RequiredArgsConstructor
class ProjectedBusLoadReader implements BusLoadReader {

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final ProjectedRosterReader rosterReader;

    private final Clock clock;

    @Override
    public List<RunLoad> assignedCountsByRun(Long academyId, Long busId) {
        return runRepository.findAllByAcademyIdAndBusIdAndServiceDateGreaterThanEqualAndCanceledAtIsNullAndStatusIn(
                academyId, busId, LocalDate.now(clock), List.of(RunStatus.IDLE, RunStatus.CONFIRMED)).stream()
                .map(run -> new RunLoad(run.getId(), run.getServiceDate(), run.getDepartTime(),
                        LowerCaseFormatter.lower(run.getDirection().name()), assignedCountOf(academyId, run)))
                .toList();
    }

    private int assignedCountOf(Long academyId, Run run) {
        return run.getStatus() == RunStatus.CONFIRMED
                ? (int) runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), academyId).stream()
                        .filter(rider -> rider.getStatus() != RiderStatus.ABSENT).count()
                : rosterReader.read(run).size();
    }
}
