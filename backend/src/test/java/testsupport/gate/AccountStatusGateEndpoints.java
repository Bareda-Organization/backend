package testsupport.gate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import src.backend.global.config.ApiPathPrefixConfig;
import src.backend.global.security.authz.PublicEndpoint;
import src.backend.global.security.gate.AllowedWhenPending;
import src.backend.global.security.gate.AllowedWhenRejected;

/**
 * 계정 상태 게이트(C-01 · API_SPEC §1.4)의 <b>거부측 대상 목록</b>과, 그 목록이 낡지 않았는지 대조할
 * 실측 수단을 함께 둔다.
 *
 * <p>정본 문면은 "허용 목록 밖 <b>전부</b> 403" 이다 — 손으로 적은 목록만 두면 다음 Phase 가
 * 엔드포인트를 늘렸을 때 목록이 조용히 낡아 검사 범위와 문면이 벌어진다. 그래서 목록(사람이 읽는
 * 정본 대조용)과 {@link #productionEndpoints} 의 런타임 실측(핸들러 매핑에서 직접 뽑음)을 둘 다 두고
 * {@code AuthFlowIntegrationTest} 가 서로 대조한다. 두 출처가 독립이라 한쪽의 누락을 다른 쪽이 잡는다.
 */
public final class AccountStatusGateEndpoints {

    /**
     * {@code rejected} 계정이 두드리면 {@code 403 AUTH_REJECTED} 여야 하는 실제 핸들러 — 허용 6개
     * ({@code @AllowedWhenPending} 5 + {@code @AllowedWhenRejected} 1)와 {@code @PublicEndpoint} 5개를
     * 뺀 나머지 전부다. 경로는 컨트롤러 소스의 bare path 로 적고 접두사는 {@link #uriOf} 가 붙인다.
     *
     * <p>Phase 5 가 {@code /staff/buses} 3개와 {@code /staff/managers} 4개를 더했다 — 차량·매니저는
     * 관계자 전용 관리 화면이라 승인 대기·거절 계정에 열어 줄 근거가 부재하다.
     *
     * <p>이어서 {@code /staff/schedules} 4개와 {@code /staff/runs} 4개(회차 조회·임시 추가·임시 취소·
     * 매니저 배치)를 더했다 — 운행 계획과 그날의 회차도 같은 관리 화면이라 근거가 같다.
     *
     * <p>Phase 6 이 {@code /staff/routes} 6개(고정 노선 CRUD 5 + 순서 최적화 1)를 더했다 — 편성은
     * 학원 관계자의 관리 화면이라 근거가 같다.
     *
     * <p>Phase 8 이 9개를 더했다 — 학원 관계자 6개({@code /staff/approvals} 목록·상세·결정 3
     * (API_SPEC §5.5·§5.6, 권한 "학원 관계자") + {@code /staff/runs/{runId}/forced-add} 1(§5.7) +
     * {@code /staff/runs/{runId}/waypoints} 배포·해제 2(§5.15, 둘 다 권한 "학원 관계자"))와 학부모
     * 3개({@code /students/{id}/runs/{runId}/intent}(§3.6) · {@code /students/{id}/change-requests}
     * 등록·조회 2(§3.8·§3.9), 셋 다 권한 "학부모") — 둘 다 승인된 계정의 기능이라 허용 목록 밖이다
     * (Ruling 145 와 같은 근거).
     *
     * <p>Phase 9 가 3개를 더했다 — {@code /runs/{runId}/start}(§4.4, 권한 "기사") ·
     * {@code /runs/{runId}/stops/{stopId}/arrive}(§4.5, 권한 "기사") ·
     * {@code /runs/{runId}/ack-changes}(§4.11, 권한 "기사 또는 동승자") — 운행 중인 단말(기사·
     * 동승자)의 기능이라 승인 대기·거절 계정에는 근거가 부재하다.
     * <p>Phase 9 T1 이 4개를 더했다 — 매니저 앱의 회차 목록·명단·노선 조회 3개({@code GET
     * /manager/runs}(§4.1) · {@code GET /runs/{runId}/roster}(§4.2) · {@code GET /runs/{runId}/route}
     * (§4.3), 셋 다 권한 "매니저")와 관계자 웹의 명단 조회 1개({@code GET /staff/runs/{runId}/roster}
     * (§5.4), 권한 "학원 관계자") — 승인된 계정만 회차 운행에 관여하므로 허용 목록 밖이다(Ruling 145 와
     * 같은 근거).
     * <p>Phase 10 이 3개를 더했다 — {@code POST /runs/{runId}/position}(§4.12, 권한 "기사") ·
     * {@code GET /students/{id}/bus-position}(§3.11, 권한 "학부모") · {@code GET /students/{id}/route}
     * (§3.10, 권한 "학부모") — 운행 중인 단말과 승인된 학부모의 기능이라 근거가 앞 Phase 들과 같다.
     *
     * <p>⚠ Phase 10 의 산출물 가운데 여기 오르는 것은 이 3개뿐이다 — STOMP 구독 채널은 HTTP 핸들러가
     * 아니라 {@code RequestMappingHandlerMapping} 에 잡히지 않고, 근접 알림은 스케줄러라 요청 진입점이
     * 부재하다. 개수가 기대만큼 안 늘었다고 누락으로 읽지 마라.
     *
     * <p>Phase 11 T1 이 3개를 더했다 — {@code POST /runs/{runId}/riders/{riderId}/no-show-contacts}
     * (§4.8, 권한 "동승자")와 {@code GET}·{@code PATCH /staff/academy-settings}(§5.21, 권한 "학원
     * 관계자") — 셋 다 승인된 계정의 기능이라 근거가 앞 Phase 들과 같다({@code Permissions} 카탈로그
     * 미매핑이라 서비스 계층이 직접 판정하는 것과는 별개 축 — 이 게이트는 그 판정보다 먼저 도는
     * 계정 상태 확인이라 권한 판정 방식과 무관하다).
     *
     * <p>Phase 11 T2 가 5개를 더했다 — 비상 신고 발신·취소(§EXC-04, 권한 "기사 또는 동승자") 2개,
     * 학원 관계자 화면의 목록·확인(권한 "학원 관계자 또는 메인관리자") 2개, 메인관리자 콘솔 목록
     * (권한 "메인관리자") 1개 — 운행 중인 단말과 승인된 관계자·관리자의 기능이라 근거가 앞 Phase 들과
     * 같다(Ruling 145 와 같은 근거).
     *
     * <p>Phase 11 T3 이 3개를 더했다 — {@code POST /runs/{runId}/reports}(§4.13, 권한 "기사 또는 동승자")는
     * Phase 9 의 운행 중 단말 엔드포인트와 같은 형태로 {@code @PreAuthorize} 없이 서비스 계층이
     * 인가한다. {@code GET /staff/reports} · {@code GET /staff/reports/{id}}(§5.20, 권한 "학원
     * 관계자")는 관계자 관리 화면이라 근거가 앞 Phase 들과 같다.
     *
     * <p>Phase 12 T1 이 2개를 더했다 — {@code GET}·{@code PATCH /me/notification-settings}(§3.14,
     * 권한 "학부모·학생")는 승인된 계정 전용 설정 화면이라 근거가 앞 Phase 들과 같다(Ruling 145 와
     * 같은 근거) — {@code /staff/academy-settings} 와 마찬가지로 대기·거절 계정이 먼저 건드릴 이유가
     * 사양에 없다.
     *
     * <p>Phase 12 T2 가 2개를 더했다 — 알림 목록 조회 {@code GET /notifications}(§3.12) · 읽음 처리
     * {@code PATCH /notifications/{id}/read}(§3.13), 둘 다 권한 "전 역할" 이나 §1.4 의 대기·거절 허용
     * 5개 목록에는 없다 — 승인된 계정의 기능이라 근거가 앞 Phase 들과 같다(Ruling 145 와 같은 근거).
     * <p>Phase 13 T1 이 2개를 더했다 — 관계자 웹 운행 대시보드 {@code GET /staff/dashboard}(§5.3)와
     * 전 차량 실시간 위치 스냅샷 {@code GET /staff/runs/live}(§5.18), 둘 다 권한 "학원 관계자"
     * ({@code @CanMonitorAcademy}) — 관계자 관리 화면이라 근거가 앞 Phase 들과 같다(Ruling 145 와
     * 같은 근거).
     *
     * <p>Phase 13 T2 가 2개를 더했다 — 메인관리자 콘솔의 학원 1곳 실시간 관제 {@code GET
     * /admin/academies/{id}/runs/live}(§6.8, 권한 "메인관리자")와 회차별 승하차지·명단 조회
     * {@code GET /admin/runs/{runId}/roster}(§6.9, 권한 "메인관리자") — 관리자 전용 관제 화면이라
     * 대기·거절 계정에는 근거가 부재하다(Ruling 145 와 같은 근거).
     *
     * <p>Phase 14 T1 이 2개를 더했다 — 메인관리자 콘솔의 감사·접속 이력 조회 {@code GET
     * /admin/audit-logs}·{@code GET /admin/login-history}(§6.13, 권한 "메인관리자") — 관리자 전용
     * 콘솔 화면이라 대기·거절 계정에는 근거가 부재하다(Ruling 145 와 같은 근거).
     *
     * <p>F1 S3 가 1개를 더했다 — 관계자 웹 확정 노선 조회 {@code GET
     * /staff/runs/{runId}/route}(§5.19, RTE-02, 권한 "학원 관계자", {@code @CanMonitorAcademy}) —
     * {@code GET /staff/dashboard}(Phase 13 T1)와 같은 계열의 관계자 관리 화면이라 근거가 같다
     * (Ruling 145 와 같은 근거).
     *
     * <p>F3 S1 이 1개를 더했다 — 지연 알림 신고 {@code POST /runs/{runId}/delay}(§4.9, NTF-06,
     * 권한 "동승자") — Phase 9 의 {@code POST /runs/{runId}/start} 와 같은 계열인 운행 중 단말
     * 기능이라 근거가 같다.
     */
    public static final List<String> DENIED_WHEN_REJECTED = List.of(
            "POST /auth/password",
            "GET /admin/academies",
            "POST /admin/academies",
            "GET /admin/academies/{id}",
            "PATCH /admin/academies/{id}",
            "GET /admin/staff-accounts",
            "PATCH /admin/staff-accounts/{id}",
            "GET /admin/blocked-accounts",
            "POST /admin/blocked-accounts/{id}/unblock",
            "GET /admin/staff-signup-requests",
            "POST /admin/staff-signup-requests/{id}/decide",
            "GET /staff/signup-requests",
            "POST /staff/signup-requests/{id}/decide",
            "GET /staff/students",
            "POST /staff/students",
            "GET /staff/students/{id}",
            "PATCH /staff/students/{id}",
            "DELETE /staff/students/{id}",
            "GET /staff/buses",
            "POST /staff/buses",
            "PATCH /staff/buses/{id}",
            "GET /staff/managers",
            "POST /staff/managers",
            "PATCH /staff/managers/{id}",
            "DELETE /staff/managers/{id}",
            "GET /staff/schedules",
            "POST /staff/schedules",
            "PATCH /staff/schedules/{id}",
            "DELETE /staff/schedules/{id}",
            "GET /staff/runs",
            "POST /staff/runs",
            "DELETE /staff/runs/{id}",
            "PATCH /staff/runs/{runId}/assignment",
            // 고정 노선 편성·최적화(§5.9 · Ruling 180) — 관계자 전용 관리 화면이라 근거가 위와 같다.
            "GET /staff/routes",
            "POST /staff/routes",
            "GET /staff/routes/{id}",
            "PATCH /staff/routes/{id}",
            "DELETE /staff/routes/{id}",
            "POST /staff/routes/{id}/optimize",
            // 자녀 목록·연결 3단계(§3.1~§3.4) — 승인된 학부모·학생의 기능이라 허용 목록 밖이다(Ruling 145).
            "GET /me/students",
            "POST /me/students/link-requests",
            "POST /me/link-code",
            "POST /me/students/link",
            // 요일별 등하원 주소(§3.7) — 승인된 학부모의 기능이라 허용 목록 밖이다(Ruling 145).
            "GET /students/{id}/weekly-address",
            "PATCH /students/{id}/weekly-address",
            // Phase 8 — ②구간 승인(§5.5·§5.6)·강제 추가(§5.7)·경유 지점(§5.15) 관계자 관리 화면 6개.
            "GET /staff/approvals",
            "GET /staff/approvals/{id}",
            "POST /staff/approvals/{id}/decide",
            "POST /staff/runs/{runId}/forced-add",
            "POST /staff/runs/{runId}/waypoints",
            "DELETE /staff/runs/{runId}/waypoints/{waypointId}",
            // Phase 8 — 탑승 토글(§3.6)·변경 신청 등록·조회(§3.8·§3.9) 학부모 기능 3개(Ruling 145).
            "PATCH /students/{id}/runs/{runId}/intent",
            "POST /students/{id}/change-requests",
            "GET /students/{id}/change-requests",
            // Phase 9 — 운행 시작·도착·변경 확인(§4.4·§4.5·§4.11) 기사·동승자 단말 기능 3개.
            "POST /runs/{runId}/start",
            "POST /runs/{runId}/stops/{stopId}/arrive",
            "POST /runs/{runId}/ack-changes",
            // Phase 9 T1 — 매니저 앱 회차 목록·명단·노선 조회(§4.1~§4.3) 3개 + 관계자 웹 명단 조회(§5.4) 1개.
            "GET /manager/runs",
            "GET /runs/{runId}/roster",
            "GET /runs/{runId}/route",
            "GET /staff/runs/{runId}/roster",
            // Phase 9 T3 — 승하차 처리·되돌리기(§4.6·§4.7) 동승자 단말 기능 2개.
            "PATCH /runs/{runId}/riders/{riderId}",
            "POST /runs/{runId}/riders/{riderId}/revert",
            // Phase 9 T4 — 외부 내비 좌표열 조회(§4.16 RUN-08) 1개.
            "GET /runs/{runId}/navigation",
            // Phase 10 — 기사 단말 위치 송신(§4.12 LOC-01) 1개 + 학부모 앱 실시간 위치·상세 노선 조회
            // (§3.11 LOC-02 · §3.10 LOC-03) 2개.
            "POST /runs/{runId}/position",
            "GET /students/{id}/bus-position",
            "GET /students/{id}/route",
            // Phase 11 T1 — 미승차 연락 이력 등록(§4.8) 동승자 기능 1개 + 학원 설정 조회·수정(§5.21)
            // 관계자 관리 화면 2개.
            "POST /runs/{runId}/riders/{riderId}/no-show-contacts",
            "GET /staff/academy-settings",
            "PATCH /staff/academy-settings",
            // Phase 11 T2 — 비상 신고 발신·취소(§EXC-04) 2개 + 학원 관계자 목록·확인 2개 +
            // 메인관리자 콘솔 목록 1개.
            "POST /runs/{runId}/emergency",
            "DELETE /runs/{runId}/emergency/{id}",
            "GET /staff/emergencies",
            "POST /staff/emergencies/{id}/ack",
            "GET /admin/emergencies",
            // Phase 11 T3 — 현장 예외 보고 등록(§4.13) 기사·동승자 단말 기능 1개 + 관계자 웹 조회(§5.20) 2개.
            "POST /runs/{runId}/reports",
            "GET /staff/reports",
            "GET /staff/reports/{id}",
            // Phase 12 T1 — 알림 수신 설정 조회·수정(§3.14) 학부모·학생 화면 2개.
            "GET /me/notification-settings",
            "PATCH /me/notification-settings",
            // Phase 12 T2 — 알림 목록 조회(§3.12)·읽음 처리(§3.13) 2개.
            "GET /notifications",
            "PATCH /notifications/{id}/read",
            // Phase 12 T3 — 알림 로그 전수 조회(§5.17, NTF-10·11, A-13) 관계자 웹 기능 1개.
            "GET /staff/notifications",
            // Phase 13 T1 — 관계자 웹 운행 대시보드(§5.3)·실시간 위치 스냅샷(§5.18) 2개.
            "GET /staff/dashboard",
            "GET /staff/runs/live",
            // Phase 13 T2 — 메인관리자 콘솔 학원 1곳 실시간 관제(§6.8) 1개 + 회차별 승하차지·명단 조회
            // (§6.9) 1개.
            "GET /admin/academies/{id}/runs/live",
            "GET /admin/runs/{runId}/roster",
            // Phase 14 T1 — 메인관리자 콘솔 감사·접속 이력 조회(§6.13) 2개.
            "GET /admin/audit-logs",
            "GET /admin/login-history",
            // F1 S3 — 관계자 웹 확정 노선 조회(§5.19) 1개.
            "GET /staff/runs/{runId}/route",
            // F3 S1 — 지연 알림 신고(§4.9, NTF-06) 동승자 단말 기능 1개.
            "POST /runs/{runId}/delay");

    /** {@code pending} 의 거부측 — {@code rejected} 거부측에 재신청 1개를 더한다({@code @AllowedWhenRejected} 는 pending 을 열지 않는다). */
    public static final List<String> DENIED_WHEN_PENDING = concat(DENIED_WHEN_REJECTED, "POST /auth/signup/reapply");

    private AccountStatusGateEndpoints() {
    }

    /**
     * 프로덕션 컨트롤러의 핸들러 매핑을 {@code "HTTP메서드 bare경로"} 로 뽑는다 — {@code filter} 로
     * 애너테이션 조건을 건다.
     *
     * <p>대상을 패키지가 {@code src.backend..controller} 인 빈으로 좁힌다. 테스트 소스의 합성 컨트롤러
     * ({@code GateTestController} 등)도 컴포넌트 스캔에 잡혀 그것까지 세면 프로덕션 핸들러 수가 흐려진다.
     * 프로덕션 컨트롤러가 이 패키지 규칙 밖에 놓이면 이 메서드가 그것을 놓치는데, 호출부의 전체 개수
     * 단언이 그때 실패해 드러난다.
     */
    public static List<String> productionEndpoints(RequestMappingHandlerMapping handlerMapping,
            Predicate<HandlerMethod> filter) {
        List<String> endpoints = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            if (!handlerMethod.getBeanType().getPackageName().startsWith("src.backend")
                    || !handlerMethod.getBeanType().getPackageName().endsWith(".controller")
                    || !filter.test(handlerMethod)) {
                return;
            }
            endpoints.add(httpMethodOf(info) + " " + barePathOf(info));
        });
        endpoints.sort(Comparator.naturalOrder());
        return endpoints;
    }

    /** {@code pending} 토큰이 통과하면 안 되는 핸들러인가 — 공개도 아니고 pending 허용도 아닌 것. */
    public static boolean deniedWhenPending(HandlerMethod handlerMethod) {
        return !handlerMethod.hasMethodAnnotation(PublicEndpoint.class)
                && !handlerMethod.hasMethodAnnotation(AllowedWhenPending.class);
    }

    /** {@code rejected} 토큰이 통과하면 안 되는 핸들러인가 — 위 조건에 재신청 허용까지 뺀 것. */
    public static boolean deniedWhenRejected(HandlerMethod handlerMethod) {
        return deniedWhenPending(handlerMethod)
                && !handlerMethod.hasMethodAnnotation(AllowedWhenRejected.class);
    }

    /**
     * {@code "HTTP메서드 bare경로"} 를 실제로 부를 수 있는 URI 로 바꾼다 — 접두사를 붙이고 경로 변수를
     * {@code 1} 로 채운다.
     *
     * <p>경로 변수 값이 실재하지 않아도 된다 — 게이트는 {@code HandlerInterceptor.preHandle} 이라
     * 인자 해석·조회보다 먼저 돌아, 거부측에서는 그 값이 읽히는 지점까지 가지 않는다.
     */
    public static String uriOf(String endpoint) {
        return ApiPathPrefixConfig.API_PREFIX + pathOf(endpoint).replaceAll("\\{[^}]+}", "1");
    }

    /** {@code "HTTP메서드 bare경로"} 의 HTTP 메서드. */
    public static HttpMethod httpMethodOf(String endpoint) {
        return HttpMethod.valueOf(endpoint.substring(0, endpoint.indexOf(' ')));
    }

    private static String pathOf(String endpoint) {
        return endpoint.substring(endpoint.indexOf(' ') + 1);
    }

    private static String httpMethodOf(RequestMappingInfo info) {
        Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                info.getMethodsCondition().getMethods();
        if (methods.size() != 1) {
            throw new IllegalStateException("HTTP 메서드가 하나로 정해지지 않은 매핑이다: " + info);
        }
        return methods.iterator().next().name();
    }

    /** 런타임 매핑 패턴에서 {@link ApiPathPrefixConfig#API_PREFIX} 를 떼어 컨트롤러 소스의 bare path 로 되돌린다. */
    private static String barePathOf(RequestMappingInfo info) {
        Set<org.springframework.web.util.pattern.PathPattern> patterns =
                info.getPathPatternsCondition() == null ? Set.of() : info.getPathPatternsCondition().getPatterns();
        if (patterns.size() != 1) {
            throw new IllegalStateException("경로가 하나로 정해지지 않은 매핑이다: " + info);
        }
        String pattern = patterns.iterator().next().getPatternString();
        if (!pattern.startsWith(ApiPathPrefixConfig.API_PREFIX)) {
            throw new IllegalStateException("API 접두사가 붙지 않은 프로덕션 매핑이다: " + pattern);
        }
        return pattern.substring(ApiPathPrefixConfig.API_PREFIX.length());
    }

    private static List<String> concat(List<String> base, String extra) {
        List<String> merged = new ArrayList<>(base);
        merged.add(extra);
        return List.copyOf(merged);
    }
}
