package src.backend.notification.domain.spec;

/**
 * 미승차 학부모 알림 문구의 재료(BRD-04, API_SPEC §4.6) — {@link NoShowParentComposer} 입력.
 *
 * <p>관계자 몫({@link NoShowStaffComposer})은 그대로 {@code RiderNoShowEvent} 를 받는다 — ATT-03(자녀
 * 이름 포함)은 학부모 수신 몫에만 걸린다({@code API_SPEC §9.7} 수신자 열).
 */
public record NoShowParentSubject(String studentName) {
}
