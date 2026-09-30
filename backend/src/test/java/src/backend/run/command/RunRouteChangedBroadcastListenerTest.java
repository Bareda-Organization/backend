package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.global.websocket.WebSocketDestinations;
import src.backend.run.event.RunRouteConfirmedEvent;

/**
 * {@code route_changed} 방송(Ruling 373, R36-BE2 목표 1·3·5) — 게이트웨이를 목으로 대체해 "어느 목적지로,
 * 몇 번, 어떤 페이로드로" 만 본다. 커밋 뒤에만 나가는지(목표 2)·실제 발행 경로(목표 4)는
 * {@link RunRouteChangedBroadcastWiringTest} 가 맡는다.
 */
class RunRouteChangedBroadcastListenerTest {

    private static final long RUN_ID = 10L;

    private static final OffsetDateTime CONFIRMED_AT = OffsetDateTime.parse("2030-05-06T07:30:00+09:00");

    private final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);

    private final RunRouteChangedBroadcastListener listener = new RunRouteChangedBroadcastListener(gateway);

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private RunRouteConfirmedEvent event() {
        return new RunRouteConfirmedEvent(RUN_ID, 1L, 2L, CONFIRMED_AT);
    }

    @Test
    @DisplayName("route_changed 를 매니저 채널로 run_id·changed_at 두 필드만 실어 1건 보낸다")
    void 매니저_채널로_두_필드를_1건_보낸다() {
        listener.broadcast(event());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(gateway, times(1)).send(eq(WebSocketDestinations.managerRun(RUN_ID)), eq("route_changed"),
                eq(RUN_ID), eq(CONFIRMED_AT), payload.capture());
        JsonNode json = objectMapper.valueToTree(payload.getValue());
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("run_id", "changed_at");
        assertThat(json.get("run_id").asLong()).isEqualTo(RUN_ID);
        assertThat(OffsetDateTime.parse(json.get("changed_at").asText()).toInstant())
                .isEqualTo(CONFIRMED_AT.toInstant());
    }

    @Test
    @DisplayName("학생·관제·관리자 채널로는 보내지 않는다 — 매니저 채널 1곳이 전부")
    void 매니저_채널_외에는_보내지_않는다() {
        listener.broadcast(event());

        verify(gateway, times(1)).send(anyString(), anyString(), anyLong(), any(), any());
        verifyNoMoreInteractions(gateway);
    }

    @Test
    @DisplayName("게이트웨이가 예외를 던져도 리스너 밖으로 퍼지지 않는다")
    void 방송_실패는_밖으로_퍼지지_않는다() {
        doThrow(new IllegalStateException("브로커 다운")).when(gateway).send(anyString(), anyString(), anyLong(),
                any(), any());

        assertThatCode(() -> listener.broadcast(event())).doesNotThrowAnyException();
    }
}
