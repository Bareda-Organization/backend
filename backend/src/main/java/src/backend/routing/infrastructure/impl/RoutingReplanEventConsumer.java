package src.backend.routing.infrastructure.impl;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import src.backend.attendance.event.AttendanceApprovedEvent;
import src.backend.global.common.ApprovalStatus;
import src.backend.routing.command.RoutingCommandService;
import src.backend.schedule.event.ScheduleResultEvent;
import src.backend.student.event.StudentAssignmentChangedEvent;
import src.backend.student.event.StudentDropoffChangedEvent;

/**
 * attendance·schedule·student 가 Kafka 로 발행한 이벤트를 소비해 해당 학생의 배정 버스(들)만 국소 replan
 * 한다(Phase 6e, F2). {@code notification} 모듈의 {@code DomainEventNotificationConsumer}가 같은
 * {@code schedule-result} 토픽을 이미 구독하고 있어, 이 클래스는 반드시 별도 consumer group을 써야
 * 두 소비자가 서로 경쟁하지 않고 각자 전체 메시지를 받는다(Kafka 컨슈머 그룹 = 같은 그룹이면 파티션을
 * 나눠 갖는 경쟁 소비, 다른 그룹이면 팬아웃 브로드캐스트).
 */
@Component
public class RoutingReplanEventConsumer {

    private static final String GROUP_ID = "school-bus-backend-routing";

    private final RoutingCommandService routingCommandService;

    public RoutingReplanEventConsumer(RoutingCommandService routingCommandService) {
        this.routingCommandService = routingCommandService;
    }

    @KafkaListener(topics = "attendance-approved", groupId = GROUP_ID)
    public void onAttendanceApproved(AttendanceApprovedEvent event) {
        routingCommandService.replanForStudent(event.studentId(), event.targetDate());
    }

    @KafkaListener(topics = "schedule-result", groupId = GROUP_ID)
    public void onScheduleResult(ScheduleResultEvent event) {
        if (event.status() != ApprovalStatus.APPROVED) {
            return; // 반려는 명단·경로에 영향이 없다
        }
        routingCommandService.replanForStudent(event.studentId(), event.requestedDate());
    }

    @KafkaListener(topics = "student-assignment-changed", groupId = GROUP_ID)
    public void onStudentAssignmentChanged(StudentAssignmentChangedEvent event) {
        routingCommandService.replanForAssignmentChange(
                event.studentId(), event.oldBusId(), event.newBusId(), event.serviceDate());
    }

    @KafkaListener(topics = "student-dropoff-changed", groupId = GROUP_ID)
    public void onStudentDropoffChanged(StudentDropoffChangedEvent event) {
        routingCommandService.replanForStudent(event.studentId(), event.serviceDate());
    }
}
