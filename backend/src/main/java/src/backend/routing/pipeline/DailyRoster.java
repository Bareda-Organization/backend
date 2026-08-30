package src.backend.routing.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p><b>{@code studentIds} 에 중복이 없어야 한다는 것은 명단 조립 주체(호출자)의 전제다</b>
 * ({@code ARCHITECTURE §8.1} — 정본, Phase 6 T4 리뷰 계약 공백 · Phase 7 목표 11). 이 타입은
 * 그 전제가 깨져도 정차지 인원({@code ridersByStop})이 부풀지 않도록 <b>방어적으로 중복을
 * 흡수</b>한다 — 던지면 그 회차 하나가 통째로 재시도 루프에 걸리고, 조용히 삼키면 관측 수단이
 * 없어지므로 흡수하되 {@code WARN} 로그로 호출자의 결함을 드러낸다.
 *
 * <p><b>이 안전망은 중복만 흡수한다 — {@code null} 원소까지 거르도록 넓히지 않는다</b>
 * (Phase 8 목표 15). {@code null} 이 섞여 들어오는 것은 중복과 달리 "같은 학생이 두 번
 * 실렸다" 는 흔한 조립 실수가 아니라 명단을 만드는 쪽의 다른 결함이라, 이 계층이 흡수할
 * 대상이 아니다. {@link List#copyOf} 가 {@code null} 원소를 거부하는 것을 그대로 둬 이
 * 생성 시점에 곧바로 {@link NullPointerException} 으로 드러나게 한다 — {@code distinct()}
 * 앞에 {@code null} 을 걸러내는 코드를 추가하면 이 신호가 조용히 사라지고, 결함은 더 뒤
 * (좌표 해석 단계)에서 원인 불명으로만 나타난다.
 *
 * @param academyId     학원 격리의 기준 — 이 학원 밖의 승하차지는 좌표를 얻지 못한 것으로 다룬다
 * @param weekday       요일별 주소(P-05)를 고르는 축
 * @param direction     등원·하원
 * @param studentIds    대상 명단. 퇴원 학생을 뺄지는 호출자가 정한다(A-10 — 퇴원해도 오늘 명단은 유지).
 *                       중복 {@code studentId} 는 이 타입이 하나로 합친다
 * @param stopOverrides 학생별 그날만의 승하차지(P-06 우선 적용). 비어 있으면 요일별 주소만 쓴다
 */
public record DailyRoster(
        long academyId,
        Weekday weekday,
        Direction direction,
        List<Long> studentIds,
        Map<Long, Long> stopOverrides) {

    private static final Logger log = LoggerFactory.getLogger(DailyRoster.class);

    /** 목록·맵을 복사해 잠그는 이유는 ①단계가 명단을 두 번 훑기 때문이다 — 호출자가 중간에 고치면 분리된 학생 수와 정차지 인원이 어긋난다. */
    public DailyRoster {
        Objects.requireNonNull(weekday, "요일이 없으면 그날의 주소를 고를 수 없다");
        Objects.requireNonNull(direction, "방향이 없으면 등원·하원 주소가 갈리지 않는다");
        Objects.requireNonNull(studentIds, "대상 명단이 없다");
        List<Long> distinctIds = studentIds.stream().distinct().toList();
        if (distinctIds.size() != studentIds.size()) {
            log.warn("[roster] 중복 studentId {}건을 배제했다 — 명단 조립 주체(호출자)의 결함이다. "
                            + "academyId={}, weekday={}, direction={}",
                    studentIds.size() - distinctIds.size(), academyId, weekday, direction);
        }
        studentIds = List.copyOf(distinctIds);
        stopOverrides = Map.copyOf(Objects.requireNonNull(stopOverrides, "일일 변경 목록이 없다"));
    }

    /** 일일 변경(P-06)이 없는 명단 — 요일별 주소(P-05)만으로 계산한다. */
    public static DailyRoster of(long academyId, Weekday weekday, Direction direction, List<Long> studentIds) {
        return new DailyRoster(academyId, weekday, direction, studentIds, Map.of());
    }
}
