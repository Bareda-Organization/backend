package src.backend.bus.query;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.bus.dto.BusListRequest;
import src.backend.bus.dto.BusResponse;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.request.PageParams;
import src.backend.global.request.SortParam;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;

/** 관계자 웹의 차량 목록 조회(BUS-01, API_SPEC §5.12). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusQueryService {

    /**
     * {@code sort} 가 받는 필드(§1.8) — 왼쪽이 API 이름, 오른쪽이 엔티티 속성이다.
     *
     * <p>목록을 손으로 적는 이유는 요청 문자열을 그대로 정렬 속성으로 넘기면 없는 이름 하나가
     * {@code 500} 이 되고, 그 예외 문구가 엔티티 필드 목록을 밖으로 실어 나르기 때문이다.
     */
    private static final Map<String, String> SORTABLE_FIELDS =
            Map.of("bus_no", "busNo", "plate_no", "plateNo", "capacity", "capacity");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "busNo");

    private final BusRepository busRepository;

    /** 소속 학원의 차량 목록(§5.12) — 범위는 토큰이 정하고 요청은 페이지 위치만 정한다. */
    public PageResponse<BusResponse> list(AuthUser requester, BusListRequest request) {
        Page<Bus> page = busRepository.findAllByAcademyId(requester.academyId(),
                PageParams.of(request.page(), request.size())
                        .toPageable(SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT)));
        List<BusResponse> items = page.getContent().stream().map(BusResponse::from).toList();
        return PageResponse.of(page, items);
    }
}
