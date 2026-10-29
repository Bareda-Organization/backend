package src.backend.exception.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.boarding.command.ClientKeyReplay;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.dto.EmergencyRaiseRequest;
import src.backend.exception.dto.EmergencyRaiseResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.persistence.ClientKeyLock;
import src.backend.global.security.AuthUser;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/** 같은 키 재전송의 응답은 요청자 토큰의 학원으로 알림 대상 수를 센다 — 저장된 신고 행의 학원 id 가 판정 기준이 아니다. */
class EmergencyReplayAcademyTest {

    private final EmergencyAlertRepository alertRepository = mock(EmergencyAlertRepository.class);

    private final RunRepository runRepository = mock(RunRepository.class);

    private final EmergencyRaiseAssembler raiseAssembler = mock(EmergencyRaiseAssembler.class);

    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private final EmergencyCommandService service = new EmergencyCommandService(alertRepository, runRepository,
            mock(BusRepository.class), mock(RunAssignmentAccess.class), new ClientKeyReplay(mock(ClientKeyLock.class)),
            mock(EmergencyCancelStep.class), mock(EmergencyAckStep.class), mock(RunPositionStore.class), raiseAssembler,
            transactionTemplate, mock(ApplicationEventPublisher.class), Clock.systemUTC());

    @Test
    void 재전송_응답의_알림_대상_수는_요청자_학원으로_센다() {
        UUID key = UUID.randomUUID();
        AuthUser requester = new AuthUser(5L, 1L, Role.DRIVER, AccountStatus.ACTIVE);
        Run run = mock(Run.class);
        when(run.getStatus()).thenReturn(RunStatus.CONFIRMED);
        when(runRepository.findByIdAndAcademyId(10L, 1L)).thenReturn(Optional.of(run));
        EmergencyAlert stored = mock(EmergencyAlert.class);
        when(stored.getId()).thenReturn(99L);
        when(stored.getRunId()).thenReturn(10L);
        when(stored.getType()).thenReturn(EmergencyType.ACCIDENT);
        when(stored.getAcademyId()).thenReturn(2L);
        when(stored.getReceivedAt()).thenReturn(OffsetDateTime.now());
        when(alertRepository.findByClientKey(key)).thenReturn(Optional.of(stored));
        when(raiseAssembler.notifiedCount(1L)).thenReturn(3L);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> ((TransactionCallback<?>) invocation
                .getArgument(0)).doInTransaction(null));

        EmergencyRaiseResponse response = service.raise(requester, 10L,
                new EmergencyRaiseRequest("accident", null, key, null, null, null));

        verify(raiseAssembler, never()).notifiedCount(2L);
        assertThat(response.notified()).isEqualTo(3L);
    }
}
