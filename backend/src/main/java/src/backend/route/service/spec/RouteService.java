package src.backend.route.service.spec;

import java.util.List;

import src.backend.global.security.AuthUser;
import src.backend.route.dto.CreateRouteRequest;
import src.backend.route.dto.CreateStopRequest;
import src.backend.route.dto.RouteResponse;
import src.backend.route.dto.StopResponse;

/**
 * 노선·정류장 관리(관리자)의 계약(인터페이스) — 목록/생성 + 정류장 추가 + 정원 초과 경고(기능9).
 *
 * <p>정류장 조회는 인증된 사용자면 가능(기사·학생이 노선 정보를 봐야 함).
 * 실제 구현은 {@link RouteServiceImpl}.
 */
public interface RouteService {

    /** 학원 노선 목록(배정 인원·정원 초과 경고 포함). */
    List<RouteResponse> listRoutes(AuthUser admin, Long tenantId);

    /** 노선의 정류장 목록(순서대로). */
    List<StopResponse> getStops(Long routeId);

    /** 노선 생성. */
    RouteResponse createRoute(AuthUser admin, CreateRouteRequest req);

    /** 노선에 정류장 추가. */
    StopResponse addStop(AuthUser admin, Long routeId, CreateStopRequest req);
}
