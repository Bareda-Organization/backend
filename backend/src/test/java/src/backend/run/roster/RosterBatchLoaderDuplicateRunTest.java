package src.backend.run.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.Direction;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.run.repository.RunTransferRepository;
import src.backend.student.access.DailyStopLookup;

/** 같은 회차가 묶음에 두 번 들어와도 예외 없이 한 번만 담긴다 — 묶음 읽기는 회차 id 를 키로 모은다. */
class RosterBatchLoaderDuplicateRunTest {

    private final RosterBatchLoader loader = new RosterBatchLoader(mock(RouteRepository.class),
            mock(RouteStopRepository.class), mock(DailyStopLookup.class), mock(BoardingIntentRepository.class),
            mock(ChangeRequestRepository.class), mock(RunForcedAdditionRepository.class),
            mock(RunTransferRepository.class), Clock.systemUTC());

    @Test
    void 같은_회차를_두_번_넘겨도_한_건으로_담는다() {
        Run run = mock(Run.class);
        when(run.getId()).thenReturn(5L);
        when(run.getAcademyId()).thenReturn(1L);
        when(run.getBusId()).thenReturn(3L);
        when(run.getServiceDate()).thenReturn(LocalDate.of(2030, 4, 1));
        when(run.getDirection()).thenReturn(Direction.TO_ACADEMY);

        Map<Long, RosterSources> sources = loader.load(List.of(run, run));

        assertThat(sources).containsOnlyKeys(5L);
    }
}
