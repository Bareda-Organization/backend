package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * {@code rider_changed} 방송의 채널 audience(목표 5, C-08·§1.12)를 고정한다 — 실제 STOMP 왕복 대신
 * {@link WebSocketBroadcastGateway} 를 목(mock)으로 대체해 호출 인자만 검증하는 이유는, 이 검사의
 * 핵심이 전송 자체가 아니라 <b>"어느 목적지로, 몇 번" 호출됐는가</b>이기 때문이다 — 실제 소켓을 띄우면
 * 그 사실을 프레임 문자열 파싱으로 우회해서 봐야 해 검증이 간접적이 된다.
 */
class RiderChangedBroadcastListenerTest {

    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
    private final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);

    private final RiderChangedBroadcastListener listener =
            new RiderChangedBroadcastListener(studentRepository, runRiderRepository, gateway);

    @Test
    @DisplayName("목표 5 — 학생 채널로는 절대 보내지 않는다, 매니저·학원·관리자 3채널로만 보낸다")
    void 학생_채널로는_보내지_않는다() {
        Long runId = 10L;
        Long academyId = 1L;
        Long studentId = 100L;
        RiderStatusChangedEvent event = new RiderStatusChangedEvent(runId, academyId, studentId, 200L, "boarded",
                OffsetDateTime.now(), false);

        Student student = mock(Student.class);
        when(student.getName()).thenReturn("홍길동");
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(runRiderRepository.findByRunIdAndStudentId(runId, studentId)).thenReturn(Optional.empty());
        when(runRiderRepository.countByRunIdAndStatus(eq(runId), any(RiderStatus.class))).thenReturn(0L);

        listener.broadcast(event);

        // 목표 5 본체 — 학생 개인 채널(/topic/students/{id}/run)로 보내는 send 호출이 단 한 번도 없어야 한다.
        verify(gateway, never()).send(eq(WebSocketDestinations.studentRun(studentId)), any(), any(), any(), any());
        verify(gateway, never()).broadcastToRunChannels(anyLong(), anyLong(), any(), any(), any(), any());

        verify(gateway, times(1)).send(eq(WebSocketDestinations.managerRun(runId)), eq("rider_changed"), eq(runId),
                any(), any());
        verify(gateway, times(1)).send(eq(WebSocketDestinations.academyLive(academyId)), eq("rider_changed"),
                eq(runId), any(), any());
        verify(gateway, times(1)).send(eq(WebSocketDestinations.ADMIN_LIVE), eq("rider_changed"), eq(runId), any(),
                any());
    }

    @Test
    @DisplayName("student_name·stop_id 는 읽기 전용 조회로 채워지고, 이벤트에 없는 값이다")
    void 페이로드가_조회로_채워진다() {
        Long runId = 10L;
        Long studentId = 100L;
        RiderStatusChangedEvent event = new RiderStatusChangedEvent(runId, 1L, studentId, 200L, "alighted",
                OffsetDateTime.now(), false);

        Student student = mock(Student.class);
        when(student.getName()).thenReturn("김민성");
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));

        RunRider rider = RunRider.uponConfirmation(runId, studentId, 55L);
        when(runRiderRepository.findByRunIdAndStudentId(runId, studentId)).thenReturn(Optional.of(rider));
        when(runRiderRepository.countByRunIdAndStatus(eq(runId), any(RiderStatus.class))).thenReturn(0L);

        listener.broadcast(event);

        // Payload 가 private record 라 리스너 밖에서 타입으로 잡을 수 없다 — 리플렉션 대신 record 의
        // 기본 toString() 문자열에 기대값이 실렸는지로 확인한다(페이로드 구조가 바뀌면 함께 깨지는
        // 의도된 결합). 같은 payload 인스턴스가 3채널 전부에 실리므로 3회로 검증한다.
        verify(gateway, times(3)).send(any(), eq("rider_changed"), eq(runId), any(), argThat(payload -> {
            String text = String.valueOf(payload);
            assertThat(text).contains("김민성").contains("stopId=55");
            return true;
        }));
    }
}
