package src.backend.notification.infrastructure.impl;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import src.backend.global.common.ApprovalStatus;
import src.backend.drivesession.event.ApproachEvent;
import src.backend.drivesession.event.NoShowEvent;
import src.backend.location.event.StudentConnectionLostEvent;
import src.backend.notification.command.spec.NotificationCommandService;
import src.backend.notification.domain.NotificationType;
import src.backend.rideevent.event.HandoverCompletedEvent;
import src.backend.rideevent.event.RideCompletedEvent;
import src.backend.rideevent.event.StudentBoardedEvent;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.event.RoutePlanPublishedEvent;
import src.backend.routing.event.RoutePlanRecommendedEvent;
import src.backend.schedule.event.ScheduleResultEvent;
import src.backend.sos.event.SosEscalatedEvent;
import src.backend.sos.event.SosTriggeredEvent;

/**
 * rideevent·sos·location 이 Kafka 로 발행한 도메인 이벤트를 소비해 알림으로 변환한다.
 * DomainEvent 파이프라인(발행 쪽)의 첫 실사용처 — 발행 모듈은 알림 모듈을 전혀 모른 채
 * {@code ApplicationEventPublisher.publishEvent(...)}만 호출하고, 이 소비자가 유일한 구독자다.
 */
@Component
public class DomainEventNotificationConsumer {

    private final NotificationCommandService notificationCommandService;

    public DomainEventNotificationConsumer(NotificationCommandService notificationCommandService) {
        this.notificationCommandService = notificationCommandService;
    }

    @KafkaListener(topics = "student-boarded")
    public void onStudentBoarded(StudentBoardedEvent event) {
        String stage = event.stopId() != null ? "stop" + event.stopId() : "bus" + event.busId();
        String dedupKey = NotificationCommandService.dedupKey(
                NotificationType.BOARD_DONE, event.studentId(), event.occurredDate(), stage);
        notificationCommandService.notify(NotificationType.BOARD_DONE, event.tenantId(), event.studentId(),
                dedupKey, event.studentName() + " 학생이 승차했습니다");
    }

    @KafkaListener(topics = "ride-completed")
    public void onRideCompleted(RideCompletedEvent event) {
        String stage = event.stopId() != null ? "stop" + event.stopId() : "bus" + event.busId();
        String dedupKey = NotificationCommandService.dedupKey(
                NotificationType.ALIGHT_DONE, event.studentId(), event.occurredDate(), stage);
        notificationCommandService.notify(NotificationType.ALIGHT_DONE, event.tenantId(), event.studentId(),
                dedupKey, event.studentName() + " 학생이 하차했습니다");
    }

    @KafkaListener(topics = "handover-completed")
    public void onHandoverCompleted(HandoverCompletedEvent event) {
        String stage = event.stopId() != null ? "stop" + event.stopId() : "bus" + event.busId();
        String dedupKey = NotificationCommandService.dedupKey(
                NotificationType.HANDOVER_DONE, event.studentId(), event.occurredDate(), stage);
        notificationCommandService.notify(NotificationType.HANDOVER_DONE, event.tenantId(), event.studentId(),
                dedupKey, event.studentName() + " 학생이 보호자에게 인계되었습니다");
    }

    @KafkaListener(topics = "approach")
    public void onApproach(ApproachEvent event) {
        String dedupKey = NotificationCommandService.dedupKey(
                NotificationType.APPROACH, event.studentId(), event.occurredDate(), "stop:" + event.routePlanStopId());
        notificationCommandService.notify(NotificationType.APPROACH, event.tenantId(), event.studentId(),
                dedupKey, event.studentName() + " 학생의 정류장 도착이 임박했습니다");
    }

    @KafkaListener(topics = "no-show")
    public void onNoShow(NoShowEvent event) {
        String dedupKey = NotificationCommandService.dedupKey(
                NotificationType.NO_SHOW, event.studentId(), event.occurredDate(), "stop:" + event.routePlanStopId());
        notificationCommandService.notify(NotificationType.NO_SHOW, event.tenantId(), event.studentId(),
                dedupKey, event.studentName() + " 학생이 정류장 도착 후 10분간 승차하지 않았습니다");
    }

    @KafkaListener(topics = "sos-triggered")
    public void onSosTriggered(SosTriggeredEvent event) {
        String dedupKey = NotificationCommandService.dedupKey(
                NotificationType.SOS, event.studentId(), event.occurredDate(), "trigger:" + event.sosEventId());
        notificationCommandService.notify(NotificationType.SOS, event.tenantId(), event.studentId(),
                dedupKey, event.studentName() + " 학생이 긴급 SOS를 요청했습니다");
    }

    @KafkaListener(topics = "sos-escalated")
    public void onSosEscalated(SosEscalatedEvent event) {
        String dedupKey = NotificationCommandService.dedupKey(
                NotificationType.SOS, event.studentId(), event.occurredDate(), "escalate:" + event.sosEventId());
        notificationCommandService.notify(NotificationType.SOS, event.tenantId(), event.studentId(),
                dedupKey, "SOS(#" + event.sosEventId() + ")가 3분간 미확인 상태입니다 — 플랫폼관리자 확인 필요");
    }

    @KafkaListener(topics = "student-connection-lost")
    public void onStudentConnectionLost(StudentConnectionLostEvent event) {
        String dedupKey = NotificationCommandService.dedupKey(
                NotificationType.CONNECTION_LOST, event.studentId(), event.occurredDate(), "disconnect:" + event.occurredAt());
        notificationCommandService.notify(NotificationType.CONNECTION_LOST, event.tenantId(), event.studentId(),
                dedupKey, event.studentName() + " 학생의 위치 연결이 끊겼습니다");
    }

    @KafkaListener(topics = "schedule-result")
    public void onScheduleResult(ScheduleResultEvent event) {
        String dedupKey = NotificationCommandService.dedupKey(NotificationType.SCHEDULE_RESULT,
                event.studentId(), event.occurredDate(), "request:" + event.scheduleChangeRequestId());
        String resultText = event.status() == ApprovalStatus.APPROVED ? "승인" : "반려";
        notificationCommandService.notify(NotificationType.SCHEDULE_RESULT, event.tenantId(), event.studentId(),
                dedupKey, event.studentName() + " 학생의 시간 변경 요청이 " + resultText + "되었습니다");
    }

    @KafkaListener(topics = "route-plan-recommended")
    public void onRoutePlanRecommended(RoutePlanRecommendedEvent event) {
        String stage = "bus" + event.busId() + ":" + event.direction() + ":v" + event.version();
        String dedupKey = NotificationCommandService.dedupKey(
                NotificationType.ROUTE_RECOMMENDED, event.triggerStudentId(), event.serviceDate(), stage);
        String directionText = event.direction() == RouteDirection.DROPOFF ? "하원" : "등원";
        notificationCommandService.notify(NotificationType.ROUTE_RECOMMENDED, event.tenantId(), event.triggerStudentId(),
                dedupKey, event.triggerStudentName() + " 학생 변경으로 " + directionText
                        + " 노선이 재계산되었습니다(검토 필요, v" + event.version() + ")");
    }

    @KafkaListener(topics = "route-plan-published")
    public void onRoutePlanPublished(RoutePlanPublishedEvent event) {
        String stage = "published:bus" + event.busId() + ":" + event.direction() + ":v" + event.version();
        String dedupKey = NotificationCommandService.dedupKey(
                NotificationType.ROUTE_PUBLISHED, event.representativeStudentId(), event.serviceDate(), stage);
        String directionText = event.direction() == RouteDirection.DROPOFF ? "하원" : "등원";
        notificationCommandService.notify(NotificationType.ROUTE_PUBLISHED, event.tenantId(), event.representativeStudentId(),
                dedupKey, directionText + " 노선이 배포되었습니다(운행 시작, v" + event.version() + ")");
    }
}
