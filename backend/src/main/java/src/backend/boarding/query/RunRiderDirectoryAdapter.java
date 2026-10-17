package src.backend.boarding.query;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.location.RunRiderDirectory;

/** {@link RunRiderDirectory} 의 {@code run_rider} 구현 — 결석 제외 조건은 저장소 조회가 건다. */
@Component
@RequiredArgsConstructor
public class RunRiderDirectoryAdapter implements RunRiderDirectory {

    private final RunRiderRepository runRiderRepository;

    @Override
    public List<Long> studentIdsExcludingAbsent(Long runId) {
        return runRiderRepository.findStudentIdsByRunIdAndStatusNot(runId, RiderStatus.ABSENT);
    }

    @Override
    public List<Long> studentIdsAtStopExcludingAbsent(Long runId, Long stopId) {
        return runRiderRepository.findStudentIdsByRunIdAndStopIdExcludingAbsent(runId, stopId);
    }
}
