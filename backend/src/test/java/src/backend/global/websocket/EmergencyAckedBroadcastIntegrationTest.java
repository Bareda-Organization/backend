package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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
import com.jayway.jsonpath.JsonPath;

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
import testsupport.redis.RedisTestContainerBase;

/**
 * {@code POST /staff/emergencies/{id}/ack} 발신 경로를 실제로 태워 {@code emergency_acked} 방송이
 * 매니저 채널({@code managerRun}) 하나로만 나가고, 3필드(API_SPEC §7.1, {@code Ruling 277})가
 * 발행 지점({@link src.backend.exception.command.EmergencyCommandService#ack})의 이름 조회
 * 결과를 그대로 싣는지(`Ruling 278`) 검증한다. {@link src.backend.exception.command.EmergencyBroadcastListenerTest} 는 손으로
 * 만든 이벤트를 리스너에 직접 넣어 페이로드 조립만 보므로, "그 이벤트가 애초에 발행 지점에서 옳게
 * 만들어지는가" 는 별개로 확인해야 한다 — 이 클래스가 그 자리다({@link EmergencyRaisedBroadcastIntegrationTest}
 * 와 같은 이유, {@code emergency_raised} 의 짝).
 *
 * <p>{@link SimpMessagingTemplate} 을 {@code @MockitoBean} 으로 대체해 {@link WebSocketBroadcastGateway}
 * 가 실제로 감싸 보내는 {@link WebSocketEnvelope} 를 가로챈다. {@code @Transactional} 클래스 전체를
 * 그대로 두면 mockMvc 호출의 서비스 트랜잭션이 시험 트랜잭션에 합류해 끝까지 커밋되지 않으므로
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 인 이 리스너가 아예 안 불린다 — 그래서 이 시험
 * 메서드만 {@link Propagation#NOT_SUPPORTED} 로 시험용 트랜잭션 자체를 끈다(짝 클래스와 같은 이유).
 * 이 시험이 만든 행은 커밋된 채로 남지만 {@link EmergencyFixtures} 가 매번 새 식별자를 쓰므로 이후
 * 실행과 충돌하지 않는다.
 *
 * <p>{@link RedisTestContainerBase} 를 상속한다 — 위치 캐시 자체는 이 시험의 관심사가 아니지만,
 * 발신(raise) 경로가 {@code RunPositionCache} 를 통해 실제 Redis 빈을 참조하므로 컨테이너 없이는
 * 앱 컨텍스트가 뜨지 않는다({@code EmergencyControllerTest} 와 같은 이유).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmergencyAckedBroadcastIntegrationTest extends RedisTestContainerBase {

    private static final String RAISE = "/api/v1/runs/%d/emergency";

    private static final String ACK = "/api/v1/staff/emergencies/%d/ack";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    // ObjectMapper 는 이 앱 컨텍스트에 빈으로 등록돼 있지 않다 — EmergencyRaisedBroadcastIntegrationTest
    // 와 같은 방식으로 SNAKE_CASE(Ruling 104)를 직접 설정한 매퍼를 검증에만 쓴다.
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
    void 확인_경로를_타면_emergency_acked_가_매니저_채널로만_3필드로_방송된다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        // 두 번째 실행부터 login_id 가 uk_account_login_id 에 충돌하지 않도록 매번 새 이름을 쓴다
        // (EmergencyRaisedBroadcastIntegrationTest 와 같은 이유).
        String driverName = "기사" + System.nanoTime();
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, driverName,
                OffsetDateTime.now());
        String staffName = "확인자" + System.nanoTime();
        long staffAccountId = fixtures.staffAccount(academyId, staffName);

        String raiseBody = mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"accident\",\"memo\":null,\"client_key\":\"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long emergencyId = Long.parseLong(JsonPath.read(raiseBody, "$.data.emergency_id"));

        mockMvc.perform(post(ACK.formatted(emergencyId))
                        .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk());

        // managerRun 채널 하나로만 정확히 한 번 — 학원·관리자·학생 채널은 이 페이로드를 받지 않는다
        // (Ruling 277, 채널 audience 는 EmergencyBroadcastListenerTest 의 goal 10 시험이 이미 고정
        // 하므로 여기서는 페이로드 내용에 집중한다).
        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(1)).convertAndSend(eq(WebSocketDestinations.managerRun(runId)),
                envelopeCaptor.capture());

        JsonNode envelope = objectMapper.valueToTree(envelopeCaptor.getValue());
        assertThat(envelope.get("event").asText()).isEqualTo("emergency_acked");

        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("emergency_id").asLong()).isEqualTo(emergencyId);
        // 이 단언이 이 시험의 본체다(Ruling 278) — 발행 지점(EmergencyCommandService#ack)의 이름
        // 조회 결과가 그대로 실려야 한다. 그 조회를 항상 null 로 바꿔도 리스너 단독 시험은 못 잡지만
        // 이 시험은 잡아야 한다.
        assertThat(payload.get("acked_by_name").asText()).isEqualTo(staffName);
        assertThat(payload.has("acked_at")).as("acked_at 키가 없다").isTrue();
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
