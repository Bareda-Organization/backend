package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 근접 알림(API_SPEC §9.7 {@code arrive}, NTF-04)의 문구 — 수신자는 학부모·학생 둘(목표 4)이고,
 * 문구에 자녀 이름을 싣는다(ATT-03, Ruling 225) — {@code arrive} 는 §9.7 on/off 토글 대상이다.
 *
 * <p>정차지 이름은 여전히 본문에 싣지 않는다 — {@link RunAutoAlightedComposer} 와 같은 이유로,
 * 알림함·잠금화면에 그대로 노출되는 채널에 위치 정보를 얹지 않는다.
 */
@Component
public class RunApproachingStopComposer implements NotificationComposer<RunApproachingStopSubject> {

    private static final String TITLE = "곧 도착합니다";

    private static final String BODY = "%s 학생이 탄 버스가 승하차지 근처에 도착했습니다.";

    @Override
    public NotificationMessage compose(RunApproachingStopSubject subject) {
        return new NotificationMessage(TITLE, BODY.formatted(subject.studentName()));
    }
}
