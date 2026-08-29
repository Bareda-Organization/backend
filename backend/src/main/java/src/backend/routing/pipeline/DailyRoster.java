package src.backend.routing.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;

/**
 * 그날 그 회차를 타는 학생 명단 — 파이프라인 ①단계(좌표 해석)의 입력이다({@code ARCHITECTURE §8.1}).
 *
 * <p><b>명단을 확정하는 것은 이 타입이 아니라 호출자다.</b> C-16 이 요구하는 세 축 중 요일별
 * 주소(P-05)만 지금 조회 가능한 형태로 존재하고, 금일 탑승 의사(ATT-01)와 일일 변경(P-06)은
 * 저장 테이블({@code boarding_intent}·{@code change_request})만 있고 그 값을 읽어 명단에
 * 반영하는 경로가 아직 부재하다(Phase 8 소유). 그래서 두 축을 파이프라인 안에서 지어내지 않고
 * <b>입력 자리로만 열어 둔다</b> — 탑승 의사는 {@code studentIds} 를 고르는 것으로,
 * 일일 변경은 {@link #stopOverrides} 로 들어온다.
 *
 * @param academyId     학원 격리의 기준 — 이 학원 밖의 승하차지는 좌표를 얻지 못한 것으로 다룬다
 * @param weekday       요일별 주소(P-05)를 고르는 축
 * @param direction     등원·하원
 * @param studentIds    대상 명단. 퇴원 학생을 뺄지는 호출자가 정한다(A-10 — 퇴원해도 오늘 명단은 유지)
 * @param stopOverrides 학생별 그날만의 승하차지(P-06 우선 적용). 비어 있으면 요일별 주소만 쓴다
 */
public record DailyRoster(
        long academyId,
        Weekday weekday,
        Direction direction,
        List<Long> studentIds,
        Map<Long, Long> stopOverrides) {

    /** 목록·맵을 복사해 잠그는 이유는 ①단계가 명단을 두 번 훑기 때문이다 — 호출자가 중간에 고치면 분리된 학생 수와 정차지 인원이 어긋난다. */
    public DailyRoster {
        Objects.requireNonNull(weekday, "요일이 없으면 그날의 주소를 고를 수 없다");
        Objects.requireNonNull(direction, "방향이 없으면 등원·하원 주소가 갈리지 않는다");
        studentIds = List.copyOf(Objects.requireNonNull(studentIds, "대상 명단이 없다"));
        stopOverrides = Map.copyOf(Objects.requireNonNull(stopOverrides, "일일 변경 목록이 없다"));
    }

    /** 일일 변경(P-06)이 없는 명단 — 요일별 주소(P-05)만으로 계산한다. */
    public static DailyRoster of(long academyId, Weekday weekday, Direction direction, List<Long> studentIds) {
        return new DailyRoster(academyId, weekday, direction, studentIds, Map.of());
    }
}
