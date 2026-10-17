package src.backend.global.location;

import java.util.List;

/**
 * 회차의 탑승 학생(결석 제외) 조회 포트 — {@code location} 이 쓰고 {@code boarding} 이 구현한다(AR-4, Ruling 899). 계약은 두 모듈 어디에도 두지 않고
 * {@code global} 에 둔다 — {@code boarding} 이 {@code location} 의 타입을 import 하면 {@code location→run→boarding→location} 새 고리가 닫힌다
 * ({@code ModuleCycleBaselineTest}). 같은 이유의 선례가 {@code global/sms/spec/SmsSender} 다.
 * 위치 방송 수신자와 근접 알림 대상을 {@code boarding} 의 저장소를 직접 import 하지 않고 얻는다.
 */
public interface RunRiderDirectory {

    /** 회차의 결석(다른 버스로 옮긴 removed 포함)이 아닌 학생 id — 위치 방송의 학생 채널 수신자다. */
    List<Long> studentIdsExcludingAbsent(Long runId);

    /** 회차의 그 정차지에 서는 결석이 아닌 학생 id — 근접 알림 대상이다. */
    List<Long> studentIdsAtStopExcludingAbsent(Long runId, Long stopId);
}
