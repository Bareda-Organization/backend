package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.controller.EmergencyFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.repository.RunRepository;

/**
 * {@code POST /runs/{runId}/emergency} 발신 경로를 실제로 태워 {@code emergency_raised} 방송이
 * 7필드(API_SPEC §7.1, Phase 14 F1 이월 ②·목표 10)를 싣는지 검증한다. {@link EmergencyBroadcastListenerTest}
 * 는 리스너 메서드를 직접 호출해 페이로드 조립만 보므로, "그 호출이 실제로 발신 흐름에서 일어나는가"
 * 는 별개로 확인해야 한다 — 이 클래스가 그 자리다.
 *
 * <p>{@link SimpMessagingTemplate} 을 {@code @MockitoBean} 으로 대체해 {@link WebSocketBroadcastGateway}
 * 가 실제로 감싸 보내는 {@link WebSocketEnvelope} 를 가로챈다. 클래스 전체가 {@code @Transactional}
 * 이면 mockMvc 호출의 서비스 트랜잭션이 시험 트랜잭션에 합류해 끝까지 커밋되지 않으므로
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 인 이 리스너가 아예 안 불린다 —
 * {@code BoardingIntentControllerTest#승인대기_처리가_롤백되면_알림도_남지_않는다} 와 같은 이유로
 * 이 시험 메서드만 {@link Propagation#NOT_SUPPORTED} 로 시험용 트랜잭션 자체를 끈다. 이 시험이
 * 만든 행은 커밋된 채로 남지만 {@link EmergencyFixtures} 가 매번 새 식별자를 쓰므로 이후 실행과
 * 충돌하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmergencyRaisedBroadcastIntegrationTest {

    private static final String RAISE = "/api/v1/runs/%d/emergency";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    // ObjectMapper 는 이 앱 컨텍스트에 빈으로 등록돼 있지 않다(전 프로젝트 확인 결과 자동배선하는
    // 시험이 없다) — EmergencyBroadcastListenerTest 와 같은 방식으로 SNAKE_CASE(Ruling 104)를 직접
    // 설정한 매퍼를 검증에만 쓴다.
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    private EmergencyFixtures fixtures() {
        return new EmergencyFixtures(academyRepository, busRepository, accountRepository, managerRepository,
                assignmentRepository, runRepository, academyStaffRepository);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 발신_경로를_타면_emergency_raised_가_7필드로_방송된다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사7",
                OffsetDateTime.now());

        mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"accident\",\"memo\":null,\"client_key\":\"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isCreated());

        // academyLive · ADMIN_LIVE 두 목적지로 각각 한 번씩, 총 2회 — 학생 채널은 안 탄다
        // (EmergencyBroadcastListenerTest 의 goal 6·7 시험이 이미 이 사실을 고정하므로 여기서는
        // 페이로드 내용에만 집중하고 목적지 개수만 가볍게 확인한다).
        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), envelopeCaptor.capture());

        JsonNode envelope = objectMapper.valueToTree(envelopeCaptor.getValue());
        assertThat(envelope.get("event").asText()).isEqualTo("emergency_raised");

        JsonNode payload = envelope.get("payload");
        assertThat(payload.has("emergency_id")).as("emergency_id 키가 없다").isTrue();
        assertThat(payload.get("type").asText()).isEqualTo("accident");
        assertThat(payload.get("bus_no")).isNotNull();
        assertThat(payload.get("raised_by").get("name").asText()).isEqualTo("기사7");
        assertThat(payload.get("raised_by").get("role").asText()).isEqualTo("driver");
        assertThat(payload.get("raised_by").get("phone").asText()).isEqualTo("010-0000-0000");
        assertThat(payload.has("position")).as("position 키가 없다").isTrue();
        assertThat(payload.get("rider_count").asInt()).isEqualTo(0);
        assertThat(payload.has("raised_at")).as("raised_at 키가 없다").isTrue();
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
