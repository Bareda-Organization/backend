package src.backend.routing.query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.request.PageParams;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.routing.assembly.StopManageAssembler;
import src.backend.routing.dto.StopListRequest;
import src.backend.routing.dto.StopManageResponse;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/** 승하차지 관리 목록 조회(RTE-01 · A-08, API_SPEC §5.9 · Ruling 849). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StopManageQueryService {

    /** 이름 오름차순, 동명은 {@code id} 순 — 쪽을 넘겨도 순서가 겹치거나 빠지지 않게 동명에도 순서를 준다. */
    private static final Sort SORT = Sort.by(Sort.Direction.ASC, "name", "id");

    private final StopRepository stopRepository;

    private final StopManageAssembler stopManageAssembler;

    /** 소속 학원의 승하차지 한 쪽 — 범위는 토큰이 정하고 요청은 검색어와 페이지 위치만 정한다. */
    public PageResponse<StopManageResponse> list(AuthUser requester, StopListRequest request) {
        String q = request.q() == null ? "" : request.q().strip();
        Page<Stop> page = stopRepository.searchByAcademyId(requester.academyId(), q,
                PageParams.of(request.page(), request.size()).toPageable(SORT));
        return PageResponse.of(page, stopManageAssembler.assemble(requester.academyId(), page.getContent()));
    }
}
