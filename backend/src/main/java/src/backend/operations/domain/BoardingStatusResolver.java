package src.backend.operations.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import src.backend.rideevent.entity.RideType;

/**
 * 학생 1명의 승하차 기록으로부터 {@link BoardingStatus}를 계산하는 순수 함수 모음.
 * 스프링 빈이 아니고 시각·임계값·결석 여부를 인자로 받는다 — 판정 규칙이 이 프로젝트에서
 * 가장 오해하기 쉬운 부분이라 대기 없이 전 경계를 테스트로 고정한다.
 *
 * <p>임계값은 새로 만들지 않고 호출부가 {@code NotificationThresholds.NO_SHOW}(미승차 10분)를
 * 넘긴다 — 화면과 알림이 다른 기준으로 "누락"을 말하면 안 된다.
 *
 * <p>결석 여부(당일 승인된 {@code AttendanceException})는 이 클래스가 조회하지 않는다 — 순수
 * 함수라 저장소에 접근하지 않으므로 호출부가 미리 조회해 {@code approvedAbsenceToday}로 넘긴다.
 */
public final class BoardingStatusResolver {

    private BoardingStatusResolver() {
    }

    /**
     * 판정 규칙(우선순위 순).
     * <ol>
     *   <li>당일 승인된 결석 신고 존재({@code approvedAbsenceToday == true}) → {@link BoardingStatus#ABSENT}
     *       — 승하차 기록이 있어도 이 조건이 이긴다. 사전에 안 탄다고 한 학생을 현장 미승차로
     *       표시하면 관계자가 헛되이 학부모에게 연락하게 된다.</li>
     *   <li>{@code ALIGHT} 또는 {@code HANDOVER} 기록 존재 → {@link BoardingStatus#ALIGHTED}</li>
     *   <li>{@code BOARD} 기록 존재(하차 기록 부재) → {@link BoardingStatus#BOARDED}</li>
     *   <li>기록 부재 + {@link #isStopReached 정차 도달} + 경과 &ge; {@code missedThreshold}
     *       → {@link BoardingStatus#NO_SHOW}</li>
     *   <li>그 밖(기록 부재 + 정차 미도달, 도달했으나 경과 &lt; {@code missedThreshold}, 또는
     *       세션 미시작) → {@link BoardingStatus#WAITING}</li>
     * </ol>
     *
     * @param approvedAbsenceToday 당일 승인된 결석 신고 존재 여부 — 호출부가 미리 조회해 넘긴다
     * @param recordedTypes  해당 학생의 오늘 세션 승하차 기록(시간순 불필요, 종류만 확인)
     * @param stopReachedAt  버스가 이 학생의 정류소에 도달한(예상) 시각, 세션 미시작이면 {@code null}
     * @param now            판정 기준 시각
     * @param missedThreshold 미승차 판정 임계값 — 호출부가 {@code NotificationThresholds.NO_SHOW}를 넘길 것
     */
    public static BoardingStatus resolve(
            boolean approvedAbsenceToday,
            List<RideType> recordedTypes,
            LocalDateTime stopReachedAt,
            LocalDateTime now,
            Duration missedThreshold) {

        if (approvedAbsenceToday) {
            return BoardingStatus.ABSENT;
        }
        if (recordedTypes.contains(RideType.ALIGHT) || recordedTypes.contains(RideType.HANDOVER)) {
            return BoardingStatus.ALIGHTED;
        }
        if (recordedTypes.contains(RideType.BOARD)) {
            return BoardingStatus.BOARDED;
        }
        if (isStopReached(stopReachedAt, now)) {
            Duration elapsed = Duration.between(stopReachedAt, now);
            if (elapsed.compareTo(missedThreshold) >= 0) {
                return BoardingStatus.NO_SHOW;
            }
        }
        return BoardingStatus.WAITING;
    }

    /**
     * 정차 도달 여부만 따로 확인한다. {@link BoardingStatus}는 기획 확정 정책(5단계)을 벗어나지
     * 않도록 도달 여부로 상태를 더 쪼개지 않았지만, 화면이 {@code WAITING} 학생 중 "곧 도착"을
     * 구분하려면 이 값이 필요하다 — 호출부가 {@link #resolve}와 별도로 호출해 쓴다.
     *
     * @param stopReachedAt 정류소 도달(예상) 시각, 세션 미시작이면 {@code null}
     * @param now           판정 기준 시각
     * @return 세션이 시작됐고 {@code now}가 도달 시각 이후(포함)이면 {@code true}
     */
    public static boolean isStopReached(LocalDateTime stopReachedAt, LocalDateTime now) {
        return stopReachedAt != null && !now.isBefore(stopReachedAt);
    }

    /**
     * 정류소 도달 시각을 세션 시작 시각 + 구간 ETA 로 추정한다.
     *
     * <p><b>한계:</b> 이 값은 추정치다. 버스가 실제 정류소에 도달한 시각을 기록하는 수단이
     * 부재해({@code RideEvent}는 승하차 시점만 기록) 계획 ETA 를 근거로 삼는다. 운행이 지연되면
     * 실제보다 이르게 "도달"로 판정돼 {@link BoardingStatus#NO_SHOW}가 조기에 발생할 수 있다.
     *
     * @param sessionStartedAt 운행 세션 시작 시각, 세션 미시작이면 {@code null}
     * @param etaSeconds       세션 시작 지점부터 이 정류소까지의 계획 소요 시간(초)
     * @return 추정 도달 시각, 세션 미시작이면 {@code null}
     */
    public static LocalDateTime estimateStopReachedAt(LocalDateTime sessionStartedAt, long etaSeconds) {
        if (sessionStartedAt == null) {
            return null;
        }
        return sessionStartedAt.plusSeconds(etaSeconds);
    }
}
