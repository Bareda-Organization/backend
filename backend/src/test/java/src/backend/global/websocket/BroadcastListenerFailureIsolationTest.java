package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;

import src.backend.boarding.command.RiderChangedBroadcastListener;
import src.backend.boarding.event.RiderMarkedNoShowEvent;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.command.EmergencyBroadcastListener;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyAckedEvent;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.location.command.PositionBroadcastListener;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.request.command.ApprovalRequestedBroadcastListener;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.run.command.RunEndedBroadcastListener;
import src.backend.run.command.RunStartedBroadcastListener;
import src.backend.run.command.StopArrivedBroadcastListener;
import src.backend.run.event.RunEndedEvent;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.event.StopArrivedEvent;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 커밋 뒤 방송 리스너 8곳 전부(BR-207) — 방송 송신·명단 조회가 예외를 던져도 리스너 밖으로 퍼지지 않아야 한다.
 * {@code AFTER_COMMIT} 시점의 예외는 응답을 바꾸지 않고 스프링이 {@code afterCompletion} 오류 스택으로만 남긴다
 * (2026-09-30 F8 관측) — 방송 실패는 예상된 운영 상황이라 리스너가 WARN 한 줄로 남기게 한다. 새 방송 리스너를
 * 만들면 여기에 한 줄을 더한다.
 */
class BroadcastListenerFailureIsolationTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-05-06T07:30:00+09:00");

    /** 무엇을 불러도 예외를 던지는 협력 객체 — 브로커 다운·DB 실패를 흉내 낸다. */
    private static final Answer<Object> FAILS = invocation -> {
        throw new IllegalStateException("협력 객체 실패");
    };

    /** 예외를 던지는 게이트웨이 + 기본 응답(빈 명단·빈 Optional)의 저장소로 리스너를 만들어 부른다. */
    static Stream<Object[]> brokerFailures() {
        WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class, FAILS);
        return calls(gateway, Answers.RETURNS_DEFAULTS);
    }

    /** 게이트웨이는 정상이고 저장소 조회가 예외를 던진다. */
    static Stream<Object[]> repositoryFailures() {
        WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);
        return calls(gateway, null);
    }

    private static Stream<Object[]> calls(WebSocketBroadcastGateway gateway, Answers repoAnswer) {
        RunRiderRepository runRiders = repo(RunRiderRepository.class, repoAnswer);
        StudentRepository students = repo(StudentRepository.class, repoAnswer);
        StopRepository stops = repo(StopRepository.class, repoAnswer);
        ChangeRequestRepository changeRequests = repo(ChangeRequestRepository.class, repoAnswer);

        PositionBroadcastListener position = new PositionBroadcastListener(runRiders, gateway);
        RunStartedBroadcastListener started = new RunStartedBroadcastListener(runRiders, gateway);
        RunEndedBroadcastListener ended = new RunEndedBroadcastListener(runRiders, gateway);
        StopArrivedBroadcastListener arrived = new StopArrivedBroadcastListener(runRiders, gateway);
        RiderChangedBroadcastListener rider = new RiderChangedBroadcastListener(students, runRiders, gateway);
        ApprovalRequestedBroadcastListener approval = new ApprovalRequestedBroadcastListener(students, runRiders,
                stops, changeRequests, gateway);
        EmergencyBroadcastListener emergency = new EmergencyBroadcastListener(gateway);

        return Stream.of(
                call("position", () -> position.broadcast(new RunPositionReceivedEvent(1L, BigDecimal.ONE,
                        BigDecimal.ONE, AT, AT, 1L, "정문", AT))),
                call("run_started", () -> started.broadcast(new RunStartedEvent(1L, 1L, AT, 0))),
                call("run_ended", () -> ended.broadcast(new RunEndedEvent(1L, 1L, AT, 0L))),
                call("stop_arrived", () -> arrived.broadcast(new StopArrivedEvent(1L, 1L, 7L, 1, "정문", AT, null))),
                call("rider_changed(status)", () -> rider.broadcast(
                        new RiderStatusChangedEvent(1L, 1L, 2L, 3L, "boarded", AT, false))),
                call("rider_changed(no_show)", () -> rider.broadcast(
                        new RiderMarkedNoShowEvent(1L, 1L, 2L, 3L, 4L, 5L, false, AT))),
                call("approval_requested", () -> approval.broadcast(new ApprovalRequestedEvent(1L, 1L, 1L, 2L, AT))),
                call("emergency_raised", () -> emergency.broadcastRaised(new EmergencyRaisedEvent(1L, 1L, 1L, "1호차",
                        EmergencyType.ACCIDENT, new EmergencyRaisedEvent.RaisedBy("기사", "driver", "010"),
                        new EmergencyRaisedEvent.Position(null, null), 0, AT))),
                call("emergency_acked", () -> emergency.broadcastAcked(new EmergencyAckedEvent(1L, 1L, 1L, "매니저", AT))),
                call("emergency_canceled", () -> emergency.broadcastCanceled(
                        new EmergencyCanceledEvent(1L, 1L, 1L, "1호차", AT))));
    }

    private static <T> T repo(Class<T> type, Answers answer) {
        return answer == null ? mock(type, FAILS) : mock(type, answer);
    }

    private static Object[] call(String name, Runnable body) {
        return new Object[] { name, body };
    }

    @ParameterizedTest(name = "{0} — 방송 송신이 실패해도 예외가 퍼지지 않는다")
    @MethodSource("brokerFailures")
    @DisplayName("방송 송신 실패는 리스너에서 삼킨다")
    void 방송_송신_실패는_삼킨다(String name, Runnable listenerCall) {
        assertThatCode(listenerCall::run).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} — 저장소 조회가 실패해도 예외가 퍼지지 않는다")
    @MethodSource("repositoryFailures")
    @DisplayName("명단·이름 조회 실패도 리스너에서 삼킨다")
    void 저장소_조회_실패도_삼킨다(String name, Runnable listenerCall) {
        assertThatCode(listenerCall::run).doesNotThrowAnyException();
    }
}
