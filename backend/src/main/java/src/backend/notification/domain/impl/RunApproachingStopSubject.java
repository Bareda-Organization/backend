package src.backend.notification.domain.impl;

/**
 * 근접(도착) 알림 문구의 재료(NTF-04, API_SPEC §9.7 {@code arrive}) — {@link RunApproachingStopComposer}
 * 입력. 수신자가 학부모·학생 둘이라도 문구는 하나를 공유한다(목표 4).
 */
public record RunApproachingStopSubject(String studentName) {
}
