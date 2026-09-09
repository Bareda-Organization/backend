package src.backend.schedule.query;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.request.PageParams;
import src.backend.global.request.SortParam;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.schedule.dto.ScheduleListRequest;
import src.backend.schedule.dto.ScheduleResponse;
import src.backend.schedule.entity.Schedule;
import src.backend.schedule.repository.ScheduleRepository;

/** 관계자 웹의 운행 스케줄 목록 조회(SCH-01, API_SPEC §5.10). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleQueryService {

    /**
     * {@code sort} 가 받는 필드(§1.8) — 왼쪽이 API 이름, 오른쪽이 엔티티 속성이다.
     *
     * <p>목록을 손으로 적는 이유는 요청 문자열을 그대로 정렬 속성으로 넘기면 없는 이름 하나가
     * {@code 500} 이 되고, 그 예외 문구가 엔티티 필드 목록을 밖으로 실어 나르기 때문이다.
     */
    private static final Map<String, String> SORTABLE_FIELDS =
            Map.of("weekday", "weekday", "direction", "direction", "depart_time", "departTime");

    /** 운행 계획은 요일·시각 순으로 읽는 것이 기본이다 — 화면이 시간표 형태이기 때문이다. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "weekday", "departTime");

    private final ScheduleRepository scheduleRepository;

    private final BusRepository busRepository;

    /** 소속 학원의 스케줄 목록(§5.10) — 범위는 토큰이 정하고 요청은 페이지 위치만 정한다. */
    public PageResponse<ScheduleResponse> list(AuthUser requester, ScheduleListRequest request) {
        Page<Schedule> page = scheduleRepository.findAllByAcademyId(requester.academyId(),
                PageParams.of(request.page(), request.size())
                        .toPageable(SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT)));
        Map<Long, String> busNos = busNosOf(requester, page.getContent());
        List<ScheduleResponse> items = page.getContent().stream()
                .map(schedule -> ScheduleResponse.of(schedule, busNos.get(schedule.getBusId())))
                .toList();
        return PageResponse.of(page, items);
    }

    /**
     * 이 페이지가 참조하는 차량의 호차를 한 번에 읽는다 — 행마다 조회하면 목록 하나가 질의 N+1 개가 된다.
     *
     * <p>빈 페이지에 질의를 보내지 않는다 — {@code IN ()} 은 DB 마다 해석이 갈린다.
     */
    private Map<Long, String> busNosOf(AuthUser requester, List<Schedule> schedules) {
        List<Long> busIds = schedules.stream().map(Schedule::getBusId).distinct().toList();
        if (busIds.isEmpty()) {
            return Map.of();
        }
        return busRepository.findAllByAcademyIdAndIdIn(requester.academyId(), busIds).stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo));
    }
}
