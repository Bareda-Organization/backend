package src.backend.global.websocket;

import java.util.Set;

/**
 * 이 계정들이 채널 권한을 잃었으니 열린 WebSocket 세션을 닫아 달라는 알림(R57 RT-2 · K2 · Ruling 899 B) — 보호자 연결 해제 · 학생 퇴원 · 기사 교체가
 * 커밋되면 발행하고, 커밋 직후 {@link AccountSessionsRevokeListener} 가 {@code 4500} 으로 닫는다. 몇 개 더 닫는 것은 괜찮다(클라이언트가 다시 붙는다) —
 * 하나라도 빠지면 다른 아이의 위치가 계속 흐른다.
 */
public record AccountSessionsRevokeEvent(Set<Long> accountIds) {
}
