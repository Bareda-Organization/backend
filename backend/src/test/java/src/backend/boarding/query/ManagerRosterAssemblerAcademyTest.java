package src.backend.boarding.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.dto.ManagerRosterResponse;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.manager.access.ManagerRunAccess;
import src.backend.routing.entity.RunStop;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;

/** 매니저 명단의 학원 항목은 호출자 토큰의 학원으로 읽는다 — 회차 행이 가리키는 학원 id 를 판정 기준으로 쓰지 않는다. */
class ManagerRosterAssemblerAcademyTest {

    private final ManagerRunAccess managerRunAccess = mock(ManagerRunAccess.class);

    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);

    private final AcademyRepository academyRepository = mock(AcademyRepository.class);

    private final RosterDataReader reader = mock(RosterDataReader.class);

    private final ManagerRosterAssembler assembler = new ManagerRosterAssembler(managerRunAccess, runRiderRepository,
            academyRepository, reader);

    @Test
    void 학원_항목은_요청자_학원의_이름과_주소를_싣는다() {
        AuthUser requester = new AuthUser(5L, 1L, Role.DRIVER, AccountStatus.ACTIVE);
        Run run = mock(Run.class);
        when(run.getId()).thenReturn(10L);
        when(run.getAcademyId()).thenReturn(2L);
        when(run.getStatus()).thenReturn(RunStatus.CONFIRMED);
        when(run.getDirection()).thenReturn(Direction.TO_ACADEMY);
        when(managerRunAccess.requireAssignedRun(requester, 10L)).thenReturn(new ManagerRunAccess.RunAssignment(run, null));
        RunStop destination = mock(RunStop.class);
        when(destination.getId()).thenReturn(77L);
        when(destination.isDestination()).thenReturn(true);
        when(reader.boardingStopsOf(requester, run)).thenReturn(List.of(destination));
        Academy mine = mock(Academy.class);
        when(mine.getName()).thenReturn("요청자 학원");
        when(academyRepository.findById(1L)).thenReturn(Optional.of(mine));
        Academy other = mock(Academy.class);
        when(other.getName()).thenReturn("회차 행의 학원");
        when(academyRepository.findById(2L)).thenReturn(Optional.of(other));

        ManagerRosterResponse response = assembler.assemble(requester, 10L);

        assertThat(response.toString()).contains("요청자 학원").doesNotContain("회차 행의 학원");
    }
}
