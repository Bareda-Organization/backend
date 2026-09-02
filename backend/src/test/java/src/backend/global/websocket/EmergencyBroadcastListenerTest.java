package src.backend.global.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyAckedEvent;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;

/**
 * 비상 신고 접수·확인·취소 방송의 채널 audience(Phase 11 T2 목표 6·7·10) — 게이트 리뷰(R2)가 지목한
 * 대로 이 리스너를 태우는 시험이 diff 전체에서 0건이었다({@code EmergencyBroadcastListener}
 * 자바독의 "학생 채널을 구조적으로 뺀다" 설계 판단을 고정하는 시험이 없었다는 뜻).
 *
 * <p>{@link RiderChangedBroadcastListenerTest} 와 같은 이유로 실제 STOMP 왕복 대신
 * {@link WebSocketBroadcastGateway} 를 목(mock)으로 대체한다 — 검증의 핵심이 "어느 목적지로, 몇 번"
 * 호출됐는가이기 때문이다.
 */
class EmergencyBroadcastListenerTest {

    private final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);

    private final EmergencyBroadcastListener listener = new EmergencyBroadcastListener(gateway);

    // ── goal 6·7 — 접수 방송은 학원·관리자 채널로만, 학생 채널은 절대 안 됨 ──────────

    @Test
    @DisplayName("접수 방송은 academyLive·ADMIN_LIVE 로만 가고, 학생 채널·managerRun 은 타지 않는다")
    void 접수_방송은_학원과_관리자_채널로만_간다() {
        Long emergencyId = 1L;
        Long academyId = 10L;
        Long runId = 100L;
        EmergencyRaisedEvent event = new EmergencyRaisedEvent(emergencyId, academyId, runId, "1호차",
                EmergencyType.ACCIDENT, OffsetDateTime.now());

        listener.broadcastRaised(event);

        verify(gateway, times(1)).send(eq(WebSocketDestinations.academyLive(academyId)), eq("emergency_raised"),
                eq(runId), any(), any());
        verify(gateway, times(1)).send(eq(WebSocketDestinations.ADMIN_LIVE), eq("emergency_raised"), eq(runId),
                any(), any());
        verify(gateway, never()).send(eq(WebSocketDestinations.managerRun(runId)), any(), any(), any(), any());
        학생_채널로는_절대_보내지_않는다();
    }

    // ── goal 10 — 확인(ack) 방송은 발신자 앱(managerRun)에도 반영된다 ────────────────

    @Test
    @DisplayName("확인 방송은 발신자 채널(managerRun)까지 3채널로 가고, 학생 채널은 여전히 안 된다")
    void 확인_방송은_발신자_채널에도_반영된다() {
        Long emergencyId = 2L;
        Long academyId = 10L;
        Long runId = 100L;
        EmergencyAckedEvent event = new EmergencyAckedEvent(emergencyId, academyId, runId, 999L,
                OffsetDateTime.now());

        listener.broadcastAcked(event);

        verify(gateway, times(1)).send(eq(WebSocketDestinations.managerRun(runId)), eq("emergency_acked"), eq(runId),
                any(), any());
        verify(gateway, times(1)).send(eq(WebSocketDestinations.academyLive(academyId)), eq("emergency_acked"),
                eq(runId), any(), any());
        verify(gateway, times(1)).send(eq(WebSocketDestinations.ADMIN_LIVE), eq("emergency_acked"), eq(runId), any(),
                any());
        학생_채널로는_절대_보내지_않는다();
    }

    // ── 취소 방송도 접수와 같은 채널 범위(발신자 자신은 REST 응답으로 이미 안다) ─────────

    @Test
    @DisplayName("취소 방송은 학원·관리자 채널로만 가고, managerRun·학생 채널은 타지 않는다")
    void 취소_방송은_학원과_관리자_채널로만_간다() {
        Long emergencyId = 3L;
        Long academyId = 10L;
        Long runId = 100L;
        EmergencyCanceledEvent event = new EmergencyCanceledEvent(emergencyId, academyId, runId, "1호차",
                OffsetDateTime.now());

        listener.broadcastCanceled(event);

        verify(gateway, times(1)).send(eq(WebSocketDestinations.academyLive(academyId)), eq("emergency_canceled"),
                eq(runId), any(), any());
        verify(gateway, times(1)).send(eq(WebSocketDestinations.ADMIN_LIVE), eq("emergency_canceled"), eq(runId),
                any(), any());
        verify(gateway, never()).send(eq(WebSocketDestinations.managerRun(runId)), any(), any(), any(), any());
        학생_채널로는_절대_보내지_않는다();
    }

    /**
     * 목표 7(학부모·학생은 비상 알림을 받지 않는다)이 WebSocket 계층에도 다시 어긋나지 않는지
     * 고정한다 — {@code send} 가 이미 다른 목적지로 호출된 뒤라 {@code never().send(any(), ...)} 를
     * 쓰면 그 호출 자체와 충돌하므로, 목적지 문자열이 학생 채널 접두사인 호출만 골라 없음을 검증한다.
     */
    private void 학생_채널로는_절대_보내지_않는다() {
        verify(gateway, never()).send(argThat(dest -> dest != null && dest.startsWith("/topic/students/")), any(),
                any(), any(), any());
        verify(gateway, never()).broadcastToRunChannels(anyLong(), anyLong(), any(), any(), any(), any());
    }
}
