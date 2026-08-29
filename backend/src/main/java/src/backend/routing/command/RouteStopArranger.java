package src.backend.routing.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 고정 노선의 정차 순서를 검증하고 갈아 끼운다(RTE-01 · RTE-09, API_SPEC §5.9).
 *
 * <p>편성 서비스에서 가른 이유는 바뀌는 계기가 다르기 때문이다 — 이쪽은 <b>순번 제약</b>
 * ({@code uk_route_stop_route_seq})과 승하차지 격리가 바뀔 때, 저쪽은 편성 자체의 유일성 조합이
 * 바뀔 때다.
 */
@Component
@RequiredArgsConstructor
public class RouteStopArranger {

    private final RouteStopRepository routeStopRepository;

    private final StopRepository stopRepository;

    /**
     * 요청이 지목한 승하차지가 <b>전부 이 학원에 실재</b>하는지 확인하고 좌표를 함께 돌려준다.
     *
     * <p>"없음" 과 "남의 학원 것" 을 갈라 답하지 않는다 — 두 경우를 다른 코드로 답하면 타 학원
     * 승하차지의 존재 여부가 응답에서 드러난다. 조회 자체가 학원으로 좁혀져 있어 두 경우가 같은
     * 빠짐으로 돌아오는 것이 그 규칙을 지키는 방식이다.
     *
     * <p>중복도 여기서 막는다. {@code uk_route_stop_route_seq} 는 <b>순번</b>의 중복만 막고 같은
     * 승하차지가 다른 순번으로 두 번 실리는 것은 통과시킨다 — 그러면 버스가 같은 자리에 두 번 선다.
     *
     * @return 승하차지 id → 그 승하차지. 요청 순서는 담기지 않으므로 순번은 호출부의 목록이 정한다
     */
    public Map<Long, Stop> resolve(Long academyId, List<Long> stopIds) {
        // 아래 두 가드가 빈 목록도 그대로 통과시키므로 이 이른 반환은 정합성이 아니라 DB 왕복 한 번을
        // 아끼는 것이다 — 음성 대조에서 이 줄을 지워도 실패하는 단언이 부재했다(수정 라운드 1 R1-N6).
        // 지워도 동작은 같으니 "여기서 막고 있다" 로 읽지 마라.
        if (stopIds.isEmpty()) {
            return Map.of();
        }
        if (new LinkedHashSet<>(stopIds).size() != stopIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "같은 승하차지를 두 번 담을 수 없습니다");
        }
        Map<Long, Stop> found = stopRepository.findAllByAcademyIdAndIdIn(academyId, stopIds).stream()
                .collect(Collectors.toMap(Stop::getId, Function.identity()));
        if (found.size() != stopIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "편성할 수 없는 승하차지가 있습니다");
        }
        return found;
    }

    /**
     * 편성의 정차 순서를 통째로 그 차례로 갈아 끼운다 — {@code seq} 는 1부터 빈틈 없이 매긴다.
     *
     * <p><b>지운 뒤 밀어내고서야 넣는다.</b> 이 {@code flush} 가 {@code uk_route_stop_route_seq} 를
     * 지키는 유일한 장치다 — Hibernate 는 한 번의 flush 안에서 INSERT 를 DELETE 보다 <b>먼저</b>
     * 실행하고, {@code seq} 가 {@code IDENTITY} 라 {@code save} 는 그 자리에서 INSERT 를 내보낸다.
     * 밀어내지 않으면 옛 1번과 새 1번이 같은 순간 테이블에 존재해 순서를 바꾸는 요청마다 제약 위반이
     * 난다.
     *
     * <p>"임시 음수 순번으로 옮겼다가 다시 매기는" 방식을 쓰지 않은 이유는 {@code route_stop.id} 를
     * 가리키는 FK 가 부재해 행을 보존할 이유가 없기 때문이다 — 그 방식은 UPDATE 를 두 번 돌려
     * 중간 상태를 하나 더 만든다.
     */
    public void replace(Long routeId, Long academyId, List<Long> stopIdsInOrder) {
        routeStopRepository.deleteAll(
                routeStopRepository.findAllOrderedByRouteIdAndAcademyId(routeId, academyId));
        routeStopRepository.flush();

        List<RouteStop> replacement = new ArrayList<>(stopIdsInOrder.size());
        for (int index = 0; index < stopIdsInOrder.size(); index++) {
            replacement.add(RouteStop.forRoute(routeId, stopIdsInOrder.get(index), index + 1));
        }
        routeStopRepository.saveAll(replacement);
    }
}
