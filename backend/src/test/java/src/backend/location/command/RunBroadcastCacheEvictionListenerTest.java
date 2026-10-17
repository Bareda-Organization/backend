package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import src.backend.global.location.RunRidersChangedEvent;
import src.backend.location.infrastructure.RunBroadcastCache;
import src.backend.run.event.RunCanceledEvent;
import src.backend.run.event.RunEndedEvent;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.event.StopArrivedEvent;

/**
 * RT-5 — 위치 방송 캐시를 비우는 이벤트 6종이 리스너에 닿는다(도착 · 탑승자 변경은 실제 발행, 나머지 넷은 처리기 직접 호출 + 커밋 뒤 리스너 등록 확인). 두 목록을 데운 뒤 이벤트를 내보내고
 * 다음 조회가 다시 읽는지 센다. 어느 처리기를 지우거나 애너테이션을 빼면 그 줄의 시험이 실패한다.
 */
@SpringBootTest
class RunBroadcastCacheEvictionListenerTest {

    private static final long RUN_ID = 424_242L;

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-04-01T09:00:00+09:00");

    @Autowired private RunBroadcastCache cache;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private RunBroadcastCacheEvictionListener listener;

    static Stream<Arguments> events() {
        return Stream.of(
                Arguments.of("RunStarted", new RunStartedEvent(RUN_ID, 1L, NOW, 0), true, true),
                Arguments.of("RunEnded", new RunEndedEvent(RUN_ID, 1L, NOW, 0L), true, true),
                Arguments.of("RunCanceled", new RunCanceledEvent(RUN_ID, 1L, NOW), true, true),
                Arguments.of("RunRouteConfirmed", new RunRouteConfirmedEvent(RUN_ID, 1L, 1L, NOW), true, true),
                Arguments.of("StopArrived", new StopArrivedEvent(RUN_ID, 1L, 1L, 1, "정류장", NOW, null), true, false),
                Arguments.of("RunRidersChanged", new RunRidersChangedEvent(RUN_ID), false, true));
    }

    @ParameterizedTest(name = "{0} 뒤 다음 조회가 다시 읽는 목록: 정차 {2} · 학생 {3}")
    @MethodSource("events")
    @DisplayName("RT-5 — 이벤트가 나오면 해당 목록만 비워져 다음 위치가 다시 읽는다")
    void 이벤트_뒤_다음_조회가_다시_읽는다(String name, Object event, boolean stopsReloaded, boolean studentsReloaded) {
        cache.evict(RUN_ID);
        AtomicInteger stopLoads = new AtomicInteger();
        AtomicInteger studentLoads = new AtomicInteger();
        읽는다(stopLoads, studentLoads);
        읽는다(stopLoads, studentLoads);
        assertThat(stopLoads.get() + studentLoads.get()).as("데워진 뒤에는 다시 읽지 않는다").isEqualTo(2);

        내보낸다(event);
        읽는다(stopLoads, studentLoads);

        assertThat(stopLoads.get()).as("정차 목록 읽은 횟수").isEqualTo(stopsReloaded ? 2 : 1);
        assertThat(studentLoads.get()).as("학생 목록 읽은 횟수").isEqualTo(studentsReloaded ? 2 : 1);
        cache.evict(RUN_ID);
    }

    /**
     * 운행 시작 · 종료 · 취소 · 노선 확정 이벤트에는 트랜잭션이 필수인 다른 동기 리스너가 있어(없는 회차를 가리키면 실패한다) 여기서 발행하지 않고 처리기를
     * 직접 부른다. 대신 처리기가 커밋 뒤({@code AFTER_COMMIT}) 리스너로 등록돼 있는지를 따로 확인한다. 도착 · 탑승자 변경은 실제로 발행한다.
     */
    private void 내보낸다(Object event) {
        if (event instanceof StopArrivedEvent || event instanceof RunRidersChangedEvent) {
            publisher.publishEvent(event);
            return;
        }
        assertAfterCommitListenerExists(event.getClass());
        if (event instanceof RunStartedEvent started) {
            listener.onRunStarted(started);
        } else if (event instanceof RunEndedEvent ended) {
            listener.onRunEnded(ended);
        } else if (event instanceof RunCanceledEvent canceled) {
            listener.onRunCanceled(canceled);
        } else if (event instanceof RunRouteConfirmedEvent confirmed) {
            listener.onRouteConfirmed(confirmed);
        }
    }

    private static void assertAfterCommitListenerExists(Class<?> eventType) {
        boolean registered = java.util.Arrays.stream(RunBroadcastCacheEvictionListener.class.getDeclaredMethods())
                .filter(method -> method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == eventType)
                .map(method -> method.getAnnotation(TransactionalEventListener.class))
                .anyMatch(annotation -> annotation != null && annotation.phase() == TransactionPhase.AFTER_COMMIT
                        && annotation.fallbackExecution());
        assertThat(registered).as("%s 를 커밋 뒤에 받는 리스너", eventType.getSimpleName()).isTrue();
    }

    private void 읽는다(AtomicInteger stopLoads, AtomicInteger studentLoads) {
        cache.stops(RUN_ID, () -> {
            stopLoads.incrementAndGet();
            return List.of();
        });
        cache.students(RUN_ID, () -> {
            studentLoads.incrementAndGet();
            return List.of();
        });
    }
}
