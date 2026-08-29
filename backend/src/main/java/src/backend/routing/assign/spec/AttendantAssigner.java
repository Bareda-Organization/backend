package src.backend.routing.assign.spec;

/**
 * 동승자 자동 배정(T6 소유, Ruling 181) — {@code ARCHITECTURE §8.2} ⑤.
 *
 * <p><b>{@code BusAssigner} 가 아니다.</b> 학생↔버스 대응은 이 포트의 일이 아니라 고정 노선 편성이
 * 이미 결정한다({@code ARCHITECTURE §8.2} — "파이프라인에 학생을 버스에 배정하는 단계가 부재"). 이
 * 포트가 배정하는 것은 <b>회차에 붙는 동승자(매니저)</b> 뿐이다.
 *
 * <p>계산 파이프라인(④ ETA 산출) <b>뒤에</b> 호출해야 한다 — 근무 시간 충돌 판정은
 * {@link AttendantAssignInput#estDurationMin()} 이 있어야 성립한다.
 */
public interface AttendantAssigner {

    /** 후보 중 근무 시간·중복 배치 충돌이 없는 매니저를 고른다 — 후보 0명·전원 거절이어도 예외를 던지지 않는다. */
    AttendantAssignment assign(AttendantAssignInput input);
}
