package src.backend.routing.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.routing.assembly.RouteDetailAssembler;
import src.backend.routing.dto.RouteListRequest;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;

/**
 * Ruling 887 ⑦ — 노선 목록 정렬의 마지막 보조 키는 {@code id} 오름차순이다. 같은 요일 노선 둘(차량이 다른 편성)의
 * 차례를 DB 가 정하게 두면 페이지를 넘길 때 같은 행이 두 번 나오거나 빠진다. 보내진 정렬 객체를 직접 본다.
 */
@ExtendWith(MockitoExtension.class)
class RouteQueryServiceSortTest {

    private static final AuthUser REQUESTER = new AuthUser(2L, 1L, Role.STAFF, AccountStatus.ACTIVE, false);

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private BusRepository busRepository;

    @Mock
    private RouteStopRepository routeStopRepository;

    @Mock
    private RouteDetailAssembler routeDetailAssembler;

    @Test
    void 기본_정렬은_차량_요일_방향_뒤에_id_오름차순을_붙인다() {
        Sort sort = sentSortFor(new RouteListRequest(0, 20, null));

        assertThat(sort.toList()).extracting(Sort.Order::getProperty)
                .containsExactly("busId", "weekdayOrder", "direction", "id");
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void 정렬을_줘도_마지막에_id_오름차순이_붙는다() {
        Sort sort = sentSortFor(new RouteListRequest(0, 20, "weekday:asc"));

        assertThat(sort.toList()).extracting(Sort.Order::getProperty).containsExactly("weekdayOrder", "id");
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    private Sort sentSortFor(RouteListRequest request) {
        when(routeRepository.findAllByAcademyId(eq(1L), any(Pageable.class))).thenReturn(Page.empty());
        RouteQueryService service = new RouteQueryService(routeRepository, busRepository, routeStopRepository,
                routeDetailAssembler);

        service.list(REQUESTER, request);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(routeRepository).findAllByAcademyId(eq(1L), captor.capture());
        return captor.getValue().getSort();
    }
}
