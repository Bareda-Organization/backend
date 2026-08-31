package src.backend.run.command;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import src.backend.run.entity.Run;

/**
 * 하원 잔여 탑승자 0명 도달을 판정해 자동 종료한다(C-15, 목표 10) — <b>T2 소유 자리표시자</b>.
 *
 * <p>이 클래스는 T2 좌석이 만드는 실제 구현이 이 워크트리에 아직 병합되지 않아 컴파일·테스트를
 * 위해 T3(boarding/command)이 임시로 채워 둔 것이다. 실제 전이 로직(마지막 하차 판정 → {@code
 * run.status=finished} 전이 → {@code finished_at} 기록)은 여기서 구현하지 않는다 — T2 가 병합될 때
 * 이 클래스를 T2 의 실제 구현으로 통째로 교체한다. 지금은 항상 {@code false} 를 반환해 "아직 전이가
 * 일어나지 않았다"는 안전한 기본값을 유지한다.
 *
 * <p>계약(조율자 확정) — {@link #completeIfAllAlighted} 는 매 {@code alight()} 뒤 무조건 호출해도
 * 안전하다. 내부적으로 {@code run.isFinishPending()} 류의 판정으로 하원이 아니거나 종료 대상이
 * 아닌 회차는 조용히 {@code false} 를 반환하는 no-op 이 된다 — 호출자가 방향(등원/하원)을 직접
 * 분기할 필요가 없다.
 */
@Service
public class RunCompletionService {

    /**
     * 이 호출로 회차가 실제로 {@code finished} 전이됐는지를 반환한다.
     *
     * @return {@code true} — 이 호출로 마지막 잔여 탑승자가 하차해 방금 종료됨(알림 발송 대상).
     *         {@code false} — 아직 잔류자가 있거나 하원·종료 대상이 아니라 아무 일도 안 일어남.
     */
    public boolean completeIfAllAlighted(Run run, OffsetDateTime decidedAt) {
        return false;
    }
}
