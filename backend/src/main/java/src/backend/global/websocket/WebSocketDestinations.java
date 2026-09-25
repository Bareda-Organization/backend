package src.backend.global.websocket;

/**
 * 방송(broadcast) 쪽에서 쓰는 구독 목적지 4종 포맷터(API_SPEC §7) — {@code StompAuthChannelInterceptor}
 * 의 정규식과 같은 문자열을 가리키지만, 그쪽은 SUBSCRIBE 인가(수신 방향)이고 이쪽은 발행(송신 방향)이라
 * 상수를 공유하지 않는다(정규식 패턴과 포맷 문자열은 같은 상수로 묶기 어렵다).
 */
public final class WebSocketDestinations {

    public static final String ADMIN_LIVE = "/topic/admin/live";

    /** 학생 1명의 개인 채널 — 회차 명단에 오른 학생마다 따로 보낸다. */
    public static String studentRun(Long studentId) {
        return "/topic/students/%d/run".formatted(studentId);
    }

    /** 매니저 앱의 회차 1건 채널. */
    public static String managerRun(Long runId) {
        return "/topic/manager/runs/%d".formatted(runId);
    }

    /** 학원 관제 채널. */
    public static String academyLive(Long academyId) {
        return "/topic/academy/%d/live".formatted(academyId);
    }

    private WebSocketDestinations() {
    }
}
