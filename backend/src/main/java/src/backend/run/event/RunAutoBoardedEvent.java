package src.backend.run.event;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 하원 회차 시작에서 <b>서버가 자동으로</b> 승차 처리한 학생 전원을 알리는 도메인 이벤트(C-07 · BRD-03 ·
 * NTF-01, R51 H1) — 학부모가 "아이가 버스에 탔다" 를 알아야 해서 자동 승차분도 {@code boarding} 알림 대상이다.
 *
 * <p>하원 출발지(학원)는 {@code run_stop} 에 승하차지 항목이 없어 {@link StopDepartedEvent} 가 나오지
 * 않는다 — 그래서 시작 시점의 이 이벤트가 하원 승차 알림의 유일한 재료다. 학생 단위 발행이 아니라 한
 * 번에 담는 이유는 수신 측이 보호자를 학생 id 목록 하나로 조회해 문장 수가 학생 수에 비례하지 않게
 * 하기 위해서다(BR-134 · BR-374). 자동 승차된 학생이 0명이면 발행하지 않는다.
 */
public record RunAutoBoardedEvent(Long runId, Long academyId, List<Long> studentIds, OffsetDateTime boardedAt) {
}
