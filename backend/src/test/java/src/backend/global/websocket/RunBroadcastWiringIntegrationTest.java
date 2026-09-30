package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * "API 호출 → 커밋 → 방송" 배선(BR-105) — 운행 방송 5종이 실제 HTTP 경로에서 나가는지.
 *
 * <p>리스너가 전부 {@code @TransactionalEventListener(AFTER_COMMIT)} 라, 리스너를 직접 부르는 단위
 * 시험도 클래스 단위 {@code @Transactional} 인 컨트롤러 시험도 이 배선을 타지 않는다 — 발행 줄이 빠지거나
 * 발행 메서드의 트랜잭션이 사라져도 초록이다. 그래서 이 클래스는 시험 트랜잭션 없이 실제로 커밋한다.
 * 만든 행은 남지만 이름에 매번 다른 값을 붙여 다음 실행과 충돌하지 않는다({@code EmergencyRaisedBroadcastIntegrationTest}
 * 와 같은 방식). {@code approval_requested} 는 비상 방송처럼 별도 흐름(②구간 변경 요청)이라 여기 두지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunBroadcastWiringIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @MockitoBean private SimpMessagingTemplate messagingTemplate;
    @Autowired @Qualifier("simpleBrokerMessageHandler") private AbstractBrokerMessageHandler broker;

    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ManagerRepository managerRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private ConfirmedRouteRepository confirmedRouteRepository;
    @Autowired private RouteVersionRepository routeVersionRepository;
    @Autowired private RunStopRepository runStopRepository;
    @Autowired private RunRiderRepository runRiderRepository;
    @Autowired private AcademyStaffRepository academyStaffRepository;
    @Autowired private GuardianRepository guardianRepository;
    @Autowired private GuardianStudentRepository guardianStudentRepository;
    @Autowired private ChangeRequestRepository changeRequestRepository;

    /** 하원 회차 하나를 시작 → 위치 → 최종 지점 도착 → 마지막 하차까지 태우며 단계마다 나간 방송을 본다. */
    @Test
    void 운행_조작마다_해당_방송이_커밋_뒤_나간다() throws Exception {
        DriverRunFixtures fixtures = new DriverRunFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, managerRepository, assignmentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                academyStaffRepository, guardianRepository, guardianStudentRepository, changeRequestRepository);
        OffsetDateTime now = OffsetDateTime.now();
        String unique = String.valueOf(System.nanoTime());
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.FROM_ACADEMY, now, now.minusMinutes(30));
        long driver = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사" + unique, now);
        long escort = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자" + unique, now);
        // 도착 처리 경로의 {stopId} 는 정차 항목(run_stop.id)이다(Ruling 327 · API_SPEC §4.5).
        long runStopId = fixtures.runStopForStop(fixtures.confirmedRouteWithVersion(runId, now), stopId, 1, now);
        long riderId = fixtures.rider(runId, fixtures.student(academyId, "학생" + unique), stopId,
                RiderStatus.WAITING, now);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", token(driver, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        assertThat(sentEvents()).contains("run_started");

        // 위치 방송은 구독자가 있는 채널에만 나간다(R46 D #6) — 학원 관제 채널에 구독자 1명을 브로커 등록부에 직접 둔다.
        구독자를_둔다(WebSocketDestinations.academyLive(academyId));
        mockMvc.perform(post("/api/v1/runs/" + runId + "/position").header("Authorization", token(driver, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":37.5601,\"lng\":126.9701,\"recorded_at\":\"%s\"}".formatted(OffsetDateTime.now())))
                .andExpect(status().is2xxSuccessful());
        assertThat(sentEvents()).contains("position");

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStopId + "/arrive")
                        .header("Authorization", token(driver, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        assertThat(sentEvents()).contains("stop_arrived");

        mockMvc.perform(patch("/api/v1/runs/" + runId + "/riders/" + riderId)
                        .header("Authorization", token(escort, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"alighted\",\"verify_method\":\"manual\",\"client_key\":\"%s\",\"occurred_at\":\"%s\"}"
                                .formatted(UUID.randomUUID(), OffsetDateTime.now())))
                .andExpect(status().isOk());
        assertThat(sentEvents()).as("마지막 하차가 종료 보류 회차를 끝낸다").contains("rider_changed", "run_ended");
    }

    @AfterEach
    void 구독자를_치운다() {
        ((SimpleBrokerMessageHandler) broker).getSubscriptionRegistry().unregisterAllSubscriptions("wiring-test-session");
    }

    private void 구독자를_둔다(String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        headers.setSessionId("wiring-test-session");
        headers.setSubscriptionId("wiring-test-sub");
        headers.setDestination(destination);
        ((SimpleBrokerMessageHandler) broker).getSubscriptionRegistry()
                .registerSubscription(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
    }

    /** 직전 단계 뒤로 나간 방송의 이벤트 이름들 — 읽은 뒤 기록을 비워 단계마다 새로 센다. */
    private List<String> sentEvents() {
        ArgumentCaptor<Object> envelopes = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(anyString(), envelopes.capture());
        List<String> events = envelopes.getAllValues().stream()
                .map(envelope -> ((WebSocketEnvelope) envelope).event())
                .distinct()
                .toList();
        clearInvocations(messagingTemplate);
        return events;
    }

    private String token(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
