package src.backend.manager.query;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.persistence.LikeEscape;
import src.backend.global.request.PageParams;
import src.backend.global.request.SortParam;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.manager.dto.ManagerListRequest;
import src.backend.manager.dto.ManagerResponse;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.ManagerRepository;

/** 관계자 웹의 매니저 목록·검색(MGR-01, API_SPEC §5.13). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagerQueryService {

    /**
     * {@code sort} 가 받는 필드(§1.8) — 왼쪽이 API 이름, 오른쪽이 엔티티 속성이다. 없는 이름을 그대로
     * 정렬 속성으로 넘기면 {@code 500} 이 되고 그 예외 문구가 엔티티 필드 목록을 밖으로 실어 나른다.
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of("name", "name", "role", "role");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    private final ManagerRepository managerRepository;

    /**
     * 소속 학원의 매니저 목록(§5.13) — {@code q} 를 주면 이름 부분 일치로 좁힌다.
     *
     * <p>삭제된 매니저는 저장소 쿼리가 거른다 — 여기서 한 번 더 거르지 않는 이유는 그러면
     * {@code total_count} 와 페이지 경계가 삭제분을 포함한 값으로 남아, 관계자가 2페이지를 눌러도
     * 빈 화면을 받기 때문이다.
     */
    public PageResponse<ManagerResponse> list(AuthUser requester, ManagerListRequest request) {
        Page<Manager> page = managerRepository.searchByAcademyId(requester.academyId(), namePattern(request.q()),
                PageParams.of(request.page(), request.size())
                        .toPageable(SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT)));
        List<ManagerResponse> items = page.getContent().stream().map(ManagerResponse::from).toList();
        return PageResponse.of(page, items);
    }

    /**
     * 검색어를 부분 일치 패턴으로 옮긴다 — 비었으면 {@code null} 이고 쿼리가 조건을 건너뛴다.
     *
     * <p>와일드카드를 이스케이프하지 않으면 {@code q="%"} 하나가 전체 매칭이 된다({@link LikeEscape}).
     * 소문자로 내리는 것은 쿼리의 {@code LOWER(m.name)} 과 짝을 맞추기 위함이다.
     */
    private String namePattern(String q) {
        return q == null || q.isBlank() ? null
                : "%" + LikeEscape.escape(q.trim()).toLowerCase(Locale.ROOT) + "%";
    }
}
