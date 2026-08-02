package src.backend.location.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import src.backend.global.push.PushTargetResolver;
import src.backend.global.push.PushTargetResolver.BusPushTargets;
import src.backend.location.dto.BusLocationView;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.event.BusLocationUpdatedEvent;

/**
 * 버스 좌표 실시간 push 단위 테스트(BE-8) — 학부모 개인 큐 배달 횟수와
 * 관제용 테넌트 토픽 브로드캐스트가 각각 정확히 한 번씩 나가는지를 본다.
 */
class BusLocationPushConsumerTest {

    private final PushTargetResolver pushTargetResolver = mock(PushTargetResolver.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);

    private final BusLocationPushConsumer consumer =
            new BusLocationPushConsumer(pushTargetResolver, messagingTemplate);

    private static final Long TENANT_ID = 1L;
    private static final Long BUS_ID = 7L;

    @Test
    void onBusLocationUpdated_sendsToTenantTopicAndEachGuardian() {
        given(pushTargetResolver.resolveForBus(BUS_ID))
                .willReturn(Optional.of(new BusPushTargets("3호차", List.of(40L, 41L), TENANT_ID)));

        consumer.onBusLocationUpdated(event());

        verify(messagingTemplate).convertAndSendToUser(eq("40"), eq("/queue/bus-location"), any(Object.class));
        verify(messagingTemplate).convertAndSendToUser(eq("41"), eq("/queue/bus-location"), any(Object.class));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/tenant/1/bus-locations"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(BusLocationView.class);
        BusLocationView view = (BusLocationView) payload.getValue();
        assertThat(view.busId()).isEqualTo(BUS_ID);
        assertThat(view.busName()).isEqualTo("3호차");
        assertThat(view.lat()).isEqualTo(37.5);
        assertThat(view.lng()).isEqualTo(127.0);
        assertThat(view.origin()).isEqualTo(LocationOrigin.GPS);
    }

    @Test
    void onBusLocationUpdated_busNotFound_sendsNothing() {
        given(pushTargetResolver.resolveForBus(BUS_ID)).willReturn(Optional.empty());

        consumer.onBusLocationUpdated(event());

        verifyNoInteractions(messagingTemplate);
    }

    private BusLocationUpdatedEvent event() {
        return BusLocationUpdatedEvent.of(TENANT_ID, BUS_ID, 37.5, 127.0, LocationOrigin.GPS,
                LocalDateTime.of(2026, 8, 2, 8, 30));
    }
}
