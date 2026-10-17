package src.backend.notification.domain.impl;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import src.backend.account.event.SignupDecidedEvent;
import src.backend.boarding.event.RiderMarkedNoShowEvent;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.exception.event.ExceptionReportedEvent;
import src.backend.exception.event.NoShowEscalatedEvent;
import src.backend.manager.event.AssignmentChangedEvent;
import src.backend.notification.domain.spec.ChangeAutoRejectedSubject;
import src.backend.notification.domain.spec.ChangeDecidedSubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.domain.spec.RiderStatusChangedSubject;
import src.backend.notification.domain.spec.RunApproachingStopSubject;
import src.backend.notification.domain.spec.RunAutoAlightedSubject;
import src.backend.notification.domain.spec.RunStartedChildSubject;
import src.backend.request.event.AbsentRecordedEvent;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.event.IntentChangedEvent;
import src.backend.run.event.RunCanceledEvent;
import src.backend.run.event.RunEndedEvent;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.event.RunStartedEvent;

/**
 * 알림 문구 표(Ruling 898 ④) — 제목·본문이 고정이거나 이름·분기 하나뿐인 문구를 한 곳에 모은다. 서식이 있는 지연
 * ({@link DelayComposer})·미승차({@link NoShowParentComposer}) 문구는 개별 구현으로 남는다. 글자는 {@code NotificationTextGoldenTest} 가
 * 고정한다.
 *
 * <p><b>관계자 몫 문구는 학생 이름·주소를 싣지 않는다</b>(FEATURE_SPEC §6.3) — 그 등급(L3)의 개인정보는 명단 화면에서 인증된
 * 접근으로만 봐야 하고, 알림은 기기 알림함·잠금화면에 그대로 노출된다. 회차를 특정하는 노선·시각을 싣지 않는 것도 같은 이유다.
 * <b>학부모·학생 몫 문구는 자녀 이름을 싣는다</b>(ATT-03, Ruling 225) — 다자녀 가정이 같은 시각에 두 자녀의 알림을 받으면 이름
 * 없이는 구분할 수 없다. 정차지 이름은 싣지 않는다(위치 정보).
 */
@Configuration
public class NotificationTexts {

    // ── 관계자·기사·동승자 몫: 고정 문구 ──

    /** 미탑승 기록 — 학부모가 오늘 안 탄다고 알린 건(수신자 관계자). */
    @Bean
    public NotificationComposer<AbsentRecordedEvent> absentComposer() {
        return fixed("미탑승 안내", "학생 한 명이 이 회차에 타지 않습니다. 명단에서 확인해 주세요.");
    }

    /** 일일 변경 요청 접수(수신자 관계자). */
    @Bean
    public NotificationComposer<ApprovalRequestedEvent> approvalRequestedComposer() {
        return fixed("변경 승인 요청", "학부모의 일일 변경 요청이 접수되어 승인이 필요합니다. 앱에서 확인해 주세요.");
    }

    /** 회차 배치 변경(수신자 기사·동승자). */
    @Bean
    public NotificationComposer<AssignmentChangedEvent> assignmentChangedComposer() {
        return fixed("배치 변경 안내", "오늘 배정된 회차가 바뀌었습니다. 앱에서 운행 일정을 확인해 주세요.");
    }

    /** 현장 예외 보고 접수(수신자 관계자). */
    @Bean
    public NotificationComposer<ExceptionReportedEvent> exceptionReportedComposer() {
        return fixed("현장 예외 보고 접수", "현장에서 예외 상황이 보고됐습니다. 목록에서 확인해 주세요.");
    }

    /** 미승차 무응답 지속 — 관계자 몫(대기가 만료된 뒤에만 발행돼 "발생" 이 아니라 "지속" 을 알린다). */
    @Bean
    public NotificationComposer<NoShowEscalatedEvent> noShowEscalatedStaffComposer() {
        return fixed("미승차 에스컬레이션", "미승차 대기 시간이 지났는데도 응답이 없습니다. 확인해 주세요.");
    }

    /** 미승차 발생 — 관계자 몫(이름을 싣지 않는다). */
    @Bean
    public NotificationComposer<RiderMarkedNoShowEvent> noShowStaffComposer() {
        return fixed("미승차 발생", "학생 한 명이 미승차로 확인됐습니다. 명단에서 확인해 주세요.");
    }

    /** 회차 노선 확정(수신자 기사·동승자). */
    @Bean
    public NotificationComposer<RunRouteConfirmedEvent> routeChangedComposer() {
        return fixed("노선 변경 안내", "배정된 회차의 노선이 확정·변경되었습니다. 앱에서 새 노선을 확인해 주세요.");
    }

    /** 회차 취소(수신자 기사·동승자). */
    @Bean
    public NotificationComposer<RunCanceledEvent> runCanceledComposer() {
        return fixed("운행 취소 안내", "배정된 회차가 취소되었습니다. 앱에서 운행 일정을 확인해 주세요.");
    }

    /** 회차 종료. */
    @Bean
    public NotificationComposer<RunEndedEvent> runEndedComposer() {
        return fixed("운행 종료 안내", "회차 운행이 종료되었습니다.");
    }

    /** 회차 시작 — 관계자·기사 몫(학부모·학생 몫은 {@link #runStartedChildComposer}). */
    @Bean
    public NotificationComposer<RunStartedEvent> runStartedComposer() {
        return fixed("운행 시작 안내", "배정된 회차의 운행이 시작되었습니다.");
    }

    // ── 두 갈래 문구 ──

    /** 탑승 의사 변경 — {@code riding} 에 따라 문구만 갈고 학생 식별자는 담지 않는다. */
    @Bean
    public NotificationComposer<IntentChangedEvent> intentChangedComposer() {
        return event -> new NotificationMessage("탑승 의사 변경 안내", event.riding()
                ? "학생 한 명이 탑승으로 전환했습니다. 명단에서 확인해 주세요."
                : "학생 한 명이 미탑승으로 전환했습니다. 명단에서 확인해 주세요.");
    }

    /** 가입 결정 — 거절 문구에는 사유를 싣는다(없으면 무엇이 반려됐는지 알 수단이 없다). */
    @Bean
    public NotificationComposer<SignupDecidedEvent> signupDecidedComposer() {
        return event -> event.accepted()
                ? new NotificationMessage("가입 승인 안내", "가입이 승인되었습니다. 로그인 후 이용해 주세요.")
                : new NotificationMessage("가입 거절 안내", "가입이 거절되었습니다. 사유: " + event.rejectReason());
    }

    /** 변경 요청 결정 — 거절 문구에 사유를 싣는다(자동 거절 {@link #changeAutoRejectedComposer} 는 마감 도과라 실을 사유가 없다). */
    @Bean
    public NotificationComposer<ChangeDecidedSubject> changeDecidedComposer() {
        return subject -> subject.approved()
                ? new NotificationMessage("변경 요청 승인 안내",
                        "%s 학생의 승하차 변경 요청이 승인되어 반영되었습니다.".formatted(subject.studentName()))
                : new NotificationMessage("변경 요청 거절 안내",
                        "%s 학생의 승하차 변경 요청이 거절되었습니다. 사유: %s".formatted(subject.studentName(), subject.rejectReason()));
    }

    /** 승차·하차 — 알 수 없는 상태는 조용히 틀린 문구를 내는 대신 예외로 드러낸다. */
    @Bean
    public NotificationComposer<RiderStatusChangedSubject> riderStatusChangedComposer() {
        return subject -> new NotificationMessage("승하차 안내", switch (subject.status()) {
            case "boarded" -> "%s 학생이 버스에 탑승했습니다.".formatted(subject.studentName());
            case "alighted" -> "%s 학생이 버스에서 하차했습니다.".formatted(subject.studentName());
            default -> throw new IllegalStateException("알 수 없는 승하차 상태: " + subject.status());
        });
    }

    // ── 비상 ──

    /** 비상 신고 접수 — 수신자 관계자·메인관리자 공통. 상황 유형을 한글로 싣는다(차량 번호만으로는 심각도를 가늠할 수 없다). */
    @Bean
    public NotificationComposer<EmergencyRaisedEvent> emergencyRaisedComposer() {
        return event -> new NotificationMessage("비상 상황 발생",
                "%s 차량에서 %s 상황이 발생했습니다. 즉시 확인해 주세요.".formatted(event.busNo(), label(event.type())));
    }

    /** 비상 신고 취소. */
    @Bean
    public NotificationComposer<EmergencyCanceledEvent> emergencyCanceledComposer() {
        return event -> new NotificationMessage("비상 신고 취소", "%s 차량의 비상 신고가 취소되었습니다.".formatted(event.busNo()));
    }

    private static String label(EmergencyType type) {
        return switch (type) {
            case ACCIDENT -> "사고";
            case VEHICLE_FAULT -> "차량 고장";
            case STUDENT_EMERGENCY -> "학생 응급";
            case ETC -> "기타 비상";
        };
    }

    // ── 학부모·학생 몫: 자녀 이름 한 칸 ──

    /** 변경 요청 자동 거절(처리 기한 도과). */
    @Bean
    public NotificationComposer<ChangeAutoRejectedSubject> changeAutoRejectedComposer() {
        return subject -> new NotificationMessage("변경 요청 처리 안내", ("%s 학생의 요청하신 변경이 처리 기한 안에 확인되지 않아 반영되지 않았습니다. "
                + "기존 승하차 정보가 그대로 유지됩니다.").formatted(subject.studentName()));
    }

    /** 근접(도착) 알림. */
    @Bean
    public NotificationComposer<RunApproachingStopSubject> runApproachingStopComposer() {
        return subject -> new NotificationMessage("곧 도착합니다",
                "%s 학생이 탄 버스가 승하차지 근처에 도착했습니다.".formatted(subject.studentName()));
    }

    /** 자동 하차. */
    @Bean
    public NotificationComposer<RunAutoAlightedSubject> runAutoAlightedComposer() {
        return subject -> new NotificationMessage("하차 안내",
                "%s 학생이 목적지에 도착해 하차 처리되었습니다.".formatted(subject.studentName()));
    }

    /** 회차 시작 — 학부모·학생 몫(2건이 와도 누구 버스인지 가를 수 있게 자녀 이름을 싣는다 · Ruling 868). */
    @Bean
    public NotificationComposer<RunStartedChildSubject> runStartedChildComposer() {
        return subject -> new NotificationMessage("운행 시작 안내",
                "%s 학생이 탈 버스의 운행이 시작되었습니다.".formatted(subject.studentName()));
    }

    private static <S> NotificationComposer<S> fixed(String title, String body) {
        NotificationMessage message = new NotificationMessage(title, body);
        return subject -> message;
    }
}
