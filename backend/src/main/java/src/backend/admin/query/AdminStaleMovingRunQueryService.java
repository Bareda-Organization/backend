package src.backend.admin.query;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.admin.dto.StaleMovingRunItemResponse;
import src.backend.admin.dto.StaleMovingRunListResponse;
import src.backend.global.request.PageParams;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.repository.RunRepository;

/**
 * 메인 관리자 콘솔의 끝나지 않은 이동 중 회차 목록(API_SPEC §6.16, R47 Ruling 724) — {@code StaleMovingRun} 경보가 세는 회차를
 * 사람이 처리할 수 있게 보인다. 대상 조건과 경계 운행일은 경보 지표와 같은 곳({@code RunRepository#STALE_MOVING} ·
 * {@link MovingRunWindowPolicy})에서 오므로 경보와 목록이 서로 다른 회차를 가리키지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminStaleMovingRunQueryService {

    /** 페이징 없는 목록의 행 수 상한(BR-228) — 운행일이 오래된 회차부터 자른다. */
    private static final Limit LIST_LIMIT = Limit.of(PageParams.UNPAGED_LIST_MAX);

    private final RunRepository runRepository;

    private final MovingRunWindowPolicy movingRunWindowPolicy;

    /** 운행일이 어제보다 이른 미취소 이동 중 회차 전부 — 운행일 오름차순. */
    public StaleMovingRunListResponse list() {
        return new StaleMovingRunListResponse(
                runRepository.findStaleMoving(movingRunWindowPolicy.earliestServiceDate(), LIST_LIMIT).stream()
                        .map(StaleMovingRunItemResponse::from)
                        .toList());
    }
}
