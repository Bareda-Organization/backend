package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.boarding.command.RiderChangedBroadcastListener;
import src.backend.boarding.event.RiderMarkedNoShowEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.command.EmergencyBroadcastListener;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.global.location.RunRiderDirectory;
import src.backend.location.command.PositionBroadcastListener;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.location.infrastructure.RunBroadcastCache;
import src.backend.observability.metrics.WebSocketPublishMetrics;
import src.backend.run.command.RunStartedBroadcastListener;
import src.backend.run.event.RunStartedEvent;
import src.backend.student.repository.StudentRepository;

/**
 * R59 S-1 — 도메인 이벤트 1건은 Redis 발행 1건이다(Ruling 900 A). 목적지마다 발행하면 위치 1건이 22번 Redis 를 지난다.
 * 발행 횟수만 보므로 게이트웨이·리스너는 진짜, 중계({@link BroadcastRelay})만 가짜다.
 */
class BroadcastRelayPublishCountTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T09:00:00+09:00");

    private final BroadcastRelay relay = mock(BroadcastRelay.class);
    private final WebSocketBroadcastGateway gateway = new WebSocketBroadcastGateway(mock(SimpMessagingTemplate.class),
            mock(WebSocketPublishMetrics.class), mock(ThreadPoolTaskExecutor.class), Clock.systemUTC(),
            new SimpleMeterRegistry());

    @BeforeEach
    void wireRelay() {
        gateway.setRelay(relay);
    }

    @Test
    @DisplayName("run_started 1건 = 발행 1건, 학생 채널 3 + 매니저 + 학원 + 관리자 5곳이 모두 그 안에 있다")
    void 운행_시작은_발행_1건() {
        RunRiderLookup lookup = new RunRiderLookup(List.of(100L, 200L, 300L));
        RunStartedBroadcastListener listener = new RunStartedBroadcastListener(lookup.repository, gateway, Runnable::run);

        listener.broadcast(new RunStartedEvent(10L, 1L, NOW, 0));

        assertThat(published("run_started")).containsExactlyInAnyOrder("/topic/students/100/run",
                "/topic/students/200/run", "/topic/students/300/run", "/topic/manager/runs/10",
                "/topic/academy/1/live", "/topic/admin/live");
    }

    @Test
    @DisplayName("위치 1건 = 발행 1건, 학생 채널 20 + 학원 + 관리자 22곳이 모두 그 안에 있다")
    void 위치는_발행_1건() {
        List<Long> students = LongStream.rangeClosed(1, 20).boxed().toList();
        RunRiderDirectory directory = mock(RunRiderDirectory.class);
        org.mockito.BDDMockito.given(directory.studentIdsExcludingAbsent(10L)).willReturn(students);
        PositionBroadcastListener listener = new PositionBroadcastListener(directory,
                new RunBroadcastCache(Clock.systemUTC()), gateway);

        listener.broadcast(new RunPositionReceivedEvent(10L, new BigDecimal("37.5"), new BigDecimal("127.0"), NOW, NOW,
                1L, "정문", NOW.plusMinutes(5)));

        List<String> destinations = published("position");
        assertThat(destinations).hasSize(22).contains("/topic/academy/1/live", "/topic/admin/live",
                "/topic/students/1/run", "/topic/students/20/run");
    }

    @Test
    @DisplayName("비상 접수 1건 = 발행 1건, 학원 + 관리자 채널")
    void 비상은_발행_1건() {
        EmergencyBroadcastListener listener = new EmergencyBroadcastListener(gateway, Runnable::run);

        listener.broadcastRaised(new EmergencyRaisedEvent(5L, 1L, "A학원", 10L, "12가3456",
                src.backend.exception.entity.EmergencyType.ACCIDENT,
                new EmergencyRaisedEvent.RaisedBy("기사", "driver", "010"),
                new EmergencyRaisedEvent.Position(new BigDecimal("37.5"), new BigDecimal("127.0")), 3, NOW));

        assertThat(published("emergency_raised")).containsExactlyInAnyOrder("/topic/academy/1/live", "/topic/admin/live");
    }

    @Test
    @DisplayName("승하차 변경 1건 = 발행 1건, 매니저 + 학원 + 관리자 채널")
    void 승하차_변경은_발행_1건() {
        RiderChangedBroadcastListener listener = new RiderChangedBroadcastListener(mock(StudentRepository.class),
                mock(RunRiderRepository.class), gateway, Runnable::run);

        listener.broadcast(new RiderMarkedNoShowEvent(10L, 1L, 100L, 7L, 8L, 9L, false, NOW));

        assertThat(published("rider_changed")).containsExactlyInAnyOrder("/topic/manager/runs/10",
                "/topic/academy/1/live", "/topic/admin/live");
    }

    @Test
    @DisplayName("목적지 1곳짜리 send 도 발행 1건")
    void 단일_목적지도_발행_1건() {
        gateway.send("/topic/manager/runs/10", "run_route_changed", 10L, NOW, new Object());

        assertThat(published("run_route_changed")).containsExactly("/topic/manager/runs/10");
    }

    /** 이벤트 이름으로 발행이 정확히 1번 일어났는지 확인하고, 그 메시지에 담긴 목적지 전부를 돌려준다. */
    private List<String> published(String event) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BroadcastDelivery>> deliveries = ArgumentCaptor.forClass(List.class);
        verify(relay, times(1)).publish(eq(event), any(), any(), deliveries.capture());
        return deliveries.getValue().stream().flatMap(delivery -> delivery.destinations().stream())
                .collect(Collectors.toList());
    }

    /** {@code RunStartedBroadcastListener} 가 읽는 회차 명단 저장소 가짜. */
    private static final class RunRiderLookup {
        final src.backend.boarding.repository.RunRiderRepository repository = mock(RunRiderRepository.class);

        RunRiderLookup(List<Long> studentIds) {
            org.mockito.BDDMockito.given(repository.findAllByRunId(10L)).willReturn(studentIds.stream()
                    .map(id -> src.backend.boarding.entity.RunRider.uponConfirmation(10L, id, 1L)).toList());
        }
    }
}
