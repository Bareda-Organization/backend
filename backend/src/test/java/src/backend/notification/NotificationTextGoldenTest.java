package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;

import src.backend.account.event.SignupDecidedEvent;
import src.backend.boarding.event.RiderMarkedNoShowEvent;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.exception.event.ExceptionReportedEvent;
import src.backend.exception.event.NoShowEscalatedEvent;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.manager.event.AssignmentChangedEvent;
import src.backend.notification.domain.spec.ChangeAutoRejectedSubject;
import src.backend.notification.domain.spec.ChangeDecidedSubject;
import src.backend.notification.domain.spec.DelaySubject;
import src.backend.notification.domain.spec.NoShowParentSubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.domain.spec.RiderStatusChangedSubject;
import src.backend.notification.domain.spec.RunApproachingStopSubject;
import src.backend.notification.domain.spec.RunAutoAlightedSubject;
import src.backend.notification.domain.spec.RunStartedChildSubject;
import src.backend.request.event.AbsentRecordedEvent;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.event.IntentChangedEvent;
import src.backend.run.entity.DelayReason;
import src.backend.run.event.RunCanceledEvent;
import src.backend.run.event.RunEndedEvent;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.event.RunStartedEvent;

/**
 * AR-3(Ruling 898 ④) — 알림 문구 22종이 구현 클래스를 합치기 전과 <b>글자 단위로 같다</b>. 기대값은 합치기 전 커밋({@code 5f083a93})의
 * 컴포저를 돌려 얻은 것이고, 이 시험은 합치기 전후 코드에 그대로 돌아야 한다(컨텍스트에서 {@code NotificationComposer<재료>} 를 제네릭 타입으로
 * 찾으므로 구현이 클래스든 설정 빈이든 상관없다). 문구 한 글자를 바꾸면 이 시험이 실패한다.
 */
@SpringBootTest
class NotificationTextGoldenTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-17T09:00:00+09:00");

    @Autowired
    private ApplicationContext context;

    @Test
    void 알림_문구_22종은_합치기_전과_글자_단위로_같다() {
        Map<String, String[]> expected = new LinkedHashMap<>();
        Map<String, NotificationMessage> actual = new LinkedHashMap<>();

        put(expected, actual, "AbsentRecorded", compose(AbsentRecordedEvent.class,
                new AbsentRecordedEvent(1L, 1L, 1L, AT)),
                "미탑승 안내", "학생 한 명이 이 회차에 타지 않습니다. 명단에서 확인해 주세요.");
        put(expected, actual, "ApprovalRequested", compose(ApprovalRequestedEvent.class,
                new ApprovalRequestedEvent(1L, 1L, 1L, 1L, AT)),
                "변경 승인 요청", "학부모의 일일 변경 요청이 접수되어 승인이 필요합니다. 앱에서 확인해 주세요.");
        put(expected, actual, "AssignmentChanged", compose(AssignmentChangedEvent.class,
                new AssignmentChangedEvent(1L, 1L, 1L, null, AT)),
                "배치 변경 안내", "오늘 배정된 회차가 바뀌었습니다. 앱에서 운행 일정을 확인해 주세요.");
        put(expected, actual, "ChangeAutoRejected", compose(ChangeAutoRejectedSubject.class,
                new ChangeAutoRejectedSubject("김하늘")),
                "변경 요청 처리 안내",
                "김하늘 학생의 요청하신 변경이 처리 기한 안에 확인되지 않아 반영되지 않았습니다. 기존 승하차 정보가 그대로 유지됩니다.");
        put(expected, actual, "ChangeDecided/approved", compose(ChangeDecidedSubject.class,
                new ChangeDecidedSubject(true, null, "김하늘")),
                "변경 요청 승인 안내", "김하늘 학생의 승하차 변경 요청이 승인되어 반영되었습니다.");
        put(expected, actual, "ChangeDecided/rejected", compose(ChangeDecidedSubject.class,
                new ChangeDecidedSubject(false, "정원 초과", "김하늘")),
                "변경 요청 거절 안내", "김하늘 학생의 승하차 변경 요청이 거절되었습니다. 사유: 정원 초과");
        put(expected, actual, "Delay/traffic", compose(DelaySubject.class,
                new DelaySubject(DelayReason.TRAFFIC, 7, null)),
                "지연 알림", "교통 정체로 인해 지연되고 있습니다. 현재 예상 지연 7분입니다.");
        put(expected, actual, "Delay/weather", compose(DelaySubject.class,
                new DelaySubject(DelayReason.WEATHER, 5, null)),
                "지연 알림", "기상 상황으로 인해 지연되고 있습니다. 현재 예상 지연 5분입니다.");
        put(expected, actual, "Delay/vehicleCheck", compose(DelaySubject.class,
                new DelaySubject(DelayReason.VEHICLE_CHECK, 10, null)),
                "지연 알림", "차량 점검으로 인해 지연되고 있습니다. 현재 예상 지연 10분입니다.");
        put(expected, actual, "Delay/prevStopWait", compose(DelaySubject.class,
                new DelaySubject(DelayReason.PREV_STOP_WAIT, 3, null)),
                "지연 알림", "이전 승하차지 대기로 인해 지연되고 있습니다. 현재 예상 지연 3분입니다.");
        put(expected, actual, "Delay/custom+student", compose(DelaySubject.class,
                new DelaySubject(DelayReason.TRAFFIC, 7, "공사로 우회합니다.", "김하늘")),
                "지연 알림", "김하늘 학생이 탄 버스 — 공사로 우회합니다.");
        put(expected, actual, "EmergencyCanceled", compose(EmergencyCanceledEvent.class,
                new EmergencyCanceledEvent(1L, 1L, 1L, "12가3456", AT)),
                "비상 신고 취소", "12가3456 차량의 비상 신고가 취소되었습니다.");
        put(expected, actual, "EmergencyRaised/accident", raised(EmergencyType.ACCIDENT),
                "비상 상황 발생", "12가3456 차량에서 사고 상황이 발생했습니다. 즉시 확인해 주세요.");
        put(expected, actual, "EmergencyRaised/fault", raised(EmergencyType.VEHICLE_FAULT),
                "비상 상황 발생", "12가3456 차량에서 차량 고장 상황이 발생했습니다. 즉시 확인해 주세요.");
        put(expected, actual, "EmergencyRaised/student", raised(EmergencyType.STUDENT_EMERGENCY),
                "비상 상황 발생", "12가3456 차량에서 학생 응급 상황이 발생했습니다. 즉시 확인해 주세요.");
        put(expected, actual, "EmergencyRaised/etc", raised(EmergencyType.ETC),
                "비상 상황 발생", "12가3456 차량에서 기타 비상 상황이 발생했습니다. 즉시 확인해 주세요.");
        put(expected, actual, "ExceptionReported", compose(ExceptionReportedEvent.class,
                new ExceptionReportedEvent(1L, 1L, 1L, AT)),
                "현장 예외 보고 접수", "현장에서 예외 상황이 보고됐습니다. 목록에서 확인해 주세요.");
        put(expected, actual, "IntentChanged/on", compose(IntentChangedEvent.class,
                new IntentChangedEvent(1L, 1L, 1L, true, AT)),
                "탑승 의사 변경 안내", "학생 한 명이 탑승으로 전환했습니다. 명단에서 확인해 주세요.");
        put(expected, actual, "IntentChanged/off", compose(IntentChangedEvent.class,
                new IntentChangedEvent(1L, 1L, 1L, false, AT)),
                "탑승 의사 변경 안내", "학생 한 명이 미탑승으로 전환했습니다. 명단에서 확인해 주세요.");
        put(expected, actual, "NoShowEscalated", compose(NoShowEscalatedEvent.class,
                new NoShowEscalatedEvent(1L, 1L, 1L, 1L, AT)),
                "미승차 에스컬레이션", "미승차 대기 시간이 지났는데도 응답이 없습니다. 확인해 주세요.");
        put(expected, actual, "NoShowParent/toAcademy", compose(NoShowParentSubject.class,
                new NoShowParentSubject("김하늘", Direction.TO_ACADEMY)),
                "미승차 안내", "김하늘 학생이 아직 버스에 탑승하지 않았습니다. 확인해 주세요.");
        put(expected, actual, "NoShowParent/fromAcademy", compose(NoShowParentSubject.class,
                new NoShowParentSubject("김하늘", Direction.FROM_ACADEMY)),
                "미승차 안내", "김하늘 학생의 하차가 확인되지 않았습니다. 학원에 확인해 주세요.");
        put(expected, actual, "RiderMarkedNoShow", compose(RiderMarkedNoShowEvent.class,
                new RiderMarkedNoShowEvent(1L, 1L, 1L, 1L, 1L, 1L, false, AT)),
                "미승차 발생", "학생 한 명이 미승차로 확인됐습니다. 명단에서 확인해 주세요.");
        put(expected, actual, "RiderStatusChanged/boarded", compose(RiderStatusChangedSubject.class,
                new RiderStatusChangedSubject("boarded", "김하늘")),
                "승하차 안내", "김하늘 학생이 버스에 탑승했습니다.");
        put(expected, actual, "RiderStatusChanged/alighted", compose(RiderStatusChangedSubject.class,
                new RiderStatusChangedSubject("alighted", "김하늘")),
                "승하차 안내", "김하늘 학생이 버스에서 하차했습니다.");
        put(expected, actual, "RunRouteConfirmed", compose(RunRouteConfirmedEvent.class,
                new RunRouteConfirmedEvent(1L, 1L, 1L, AT)),
                "노선 변경 안내", "배정된 회차의 노선이 확정·변경되었습니다. 앱에서 새 노선을 확인해 주세요.");
        put(expected, actual, "RunApproachingStop", compose(RunApproachingStopSubject.class,
                new RunApproachingStopSubject("김하늘")),
                "곧 도착합니다", "김하늘 학생이 탄 버스가 승하차지 근처에 도착했습니다.");
        put(expected, actual, "RunAutoAlighted", compose(RunAutoAlightedSubject.class,
                new RunAutoAlightedSubject("김하늘")),
                "하차 안내", "김하늘 학생이 목적지에 도착해 하차 처리되었습니다.");
        put(expected, actual, "RunCanceled", compose(RunCanceledEvent.class,
                new RunCanceledEvent(1L, 1L, AT)),
                "운행 취소 안내", "배정된 회차가 취소되었습니다. 앱에서 운행 일정을 확인해 주세요.");
        put(expected, actual, "RunEnded", compose(RunEndedEvent.class,
                new RunEndedEvent(1L, 1L, AT, 0)),
                "운행 종료 안내", "회차 운행이 종료되었습니다.");
        put(expected, actual, "RunStartedChild", compose(RunStartedChildSubject.class,
                new RunStartedChildSubject("김하늘")),
                "운행 시작 안내", "김하늘 학생이 탈 버스의 운행이 시작되었습니다.");
        put(expected, actual, "RunStarted", compose(RunStartedEvent.class,
                new RunStartedEvent(1L, 1L, AT, 0)),
                "운행 시작 안내", "배정된 회차의 운행이 시작되었습니다.");
        put(expected, actual, "SignupDecided/accepted", compose(SignupDecidedEvent.class,
                new SignupDecidedEvent(1L, 1L, "홍길동", Role.PARENT, true, null, AT)),
                "가입 승인 안내", "가입이 승인되었습니다. 로그인 후 이용해 주세요.");
        put(expected, actual, "SignupDecided/rejected", compose(SignupDecidedEvent.class,
                new SignupDecidedEvent(1L, 1L, "홍길동", Role.PARENT, false, "서류 미비", AT)),
                "가입 거절 안내", "가입이 거절되었습니다. 사유: 서류 미비");

        assertThat(expected).as("22 개 컴포저의 모든 분기를 덮는다").hasSize(34);
        expected.forEach((id, text) -> assertThat(new String[] { actual.get(id).title(), actual.get(id).body() })
                .as(id).containsExactly(text));
    }

    private static void put(Map<String, String[]> expected, Map<String, NotificationMessage> actual, String id,
            NotificationMessage message, String title, String body) {
        expected.put(id, new String[] { title, body });
        actual.put(id, message);
    }

    private NotificationMessage raised(EmergencyType type) {
        return compose(EmergencyRaisedEvent.class,
                new EmergencyRaisedEvent(1L, 1L, "학원", 1L, "12가3456", type, null, null, 0, AT));
    }

    /** 컨텍스트에서 {@code NotificationComposer<재료>} 를 제네릭 타입으로 찾아 문구를 만든다 — 구현이 클래스든 설정 빈이든 같다. */
    @SuppressWarnings("unchecked")
    private <S> NotificationMessage compose(Class<S> subjectType, S subject) {
        ResolvableType type = ResolvableType.forClassWithGenerics(NotificationComposer.class, subjectType);
        return ((NotificationComposer<S>) context.getBeanProvider(type).getObject()).compose(subject);
    }
}
