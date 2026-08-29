package src.backend.routing.query;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.PageParams;
import src.backend.global.request.SortParam;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.routing.dto.RouteDetailResponse;
import src.backend.routing.dto.RouteListRequest;
import src.backend.routing.dto.RouteResponse;
import src.backend.routing.entity.Route;
import src.backend.routing.repository.RouteRepository;

/** 관계자 웹의 고정 노선 목록·상세 조회(RTE-01 · A-08, API_SPEC §5.9). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteQueryService {

    /**
     * {@code sort} 가 받는 필드(§1.8) — 왼쪽이 API 이름, 오른쪽이 엔티티 속성이다.
     *
     * <p>목록을 손으로 적는 이유는 요청 문자열을 그대로 정렬 속성으로 넘기면 없는 이름 하나가
     * {@code 500} 이 되고, 그 예외 문구가 엔티티 필드 목록을 밖으로 실어 나르기 때문이다.
     */
    private static final Map<String, String> SORTABLE_FIELDS =
            Map.of("weekday", "weekday", "direction", "direction", "name", "name");

    /** 기본 정렬은 차량 — 편성 화면이 차량 단위로 묶어 보여 준다. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "busId", "weekday", "direction");

    private final RouteRepository routeRepository;

    private final BusRepository busRepository;

    private final RouteDetailAssembler routeDetailAssembler;

    /** 소속 학원의 고정 노선 목록(§5.9) — 범위는 토큰이 정하고 요청은 페이지 위치만 정한다. */
    public PageResponse<RouteResponse> list(AuthUser requester, RouteListRequest request) {
        Page<Route> page = routeRepository.findAllByAcademyId(requester.academyId(),
                PageParams.of(request.page(), request.size())
                        .toPageable(SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT)));
        Map<Long, String> busNos = busNosOf(requester, page.getContent());
        List<RouteResponse> items = page.getContent().stream()
                .map(route -> RouteResponse.of(route, busNos.get(route.getBusId())))
                .toList();
        return PageResponse.of(page, items);
    }

    /** 편성 1건의 상세(§5.9) — 다른 학원의 편성을 지목하면 {@code 404 ROUTE_NOT_FOUND} 다. */
    public RouteDetailResponse detail(AuthUser requester, Long routeId) {
        Route route = routeRepository.findByIdAndAcademyId(routeId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
        return routeDetailAssembler.assemble(route);
    }

    /** 한 페이지가 물고 있는 차량의 호차를 한 번에 읽는다 — 행마다 조회하면 목록 하나에 N회가 붙는다. */
    private Map<Long, String> busNosOf(AuthUser requester, List<Route> routes) {
        if (routes.isEmpty()) {
            return Map.of();
        }
        return busRepository.findAllByAcademyIdAndIdIn(requester.academyId(),
                        routes.stream().map(Route::getBusId).distinct().toList()).stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo, (first, second) -> first));
    }
}
