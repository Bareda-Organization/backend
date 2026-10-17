package src.backend.global.config;

import java.util.Optional;

import org.springframework.scheduling.support.DefaultScheduledTaskObservationConvention;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationDocumentation.LowCardinalityKeyNames;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.GlobalObservationConvention;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

/**
 * ShedLock 락을 못 얻어 건너뛴 스케줄 실행을 기본 계측({@code tasks.scheduled.execution})에서 {@code outcome="SUCCESS"} 가 아니라 {@code outcome="SKIPPED"} 로 세게 한다(Ruling 915 B).
 * 기본 계측은 메서드가 예외 없이 돌아오면 성공으로 세는데 ShedLock 은 락을 못 얻으면 메서드를 건너뛰고 정상 반환한다 — 그대로 두면 락을 가진 인스턴스가 멈춰도 다른 인스턴스의 건너뜀이 성공 건수를
 * 올려 "마지막 성공이 오래됨" 경보({@code *Stalled})가 영영 켜지지 않는다.
 *
 * <p>락 제공자를 감싸 락을 못 얻은 순간 지금 돌고 있는 관측(스케줄 실행)의 문맥에 표지를 남기고({@link SkipMarkingLockProvider}), 관측 규약이 결과를 정할 때 그 표지를 본다.
 */
public final class LockSkipAwareObservation {

    private static final String SKIPPED_KEY = "shedlock.skipped";

    private LockSkipAwareObservation() {
    }

    /** 락을 못 얻은 실행의 결과를 {@code SKIPPED} 로 바꾸는 전역 규약 — 빈으로 등록하면 Boot 가 관측 등록기에 건다. */
    public static final class Convention extends DefaultScheduledTaskObservationConvention
            implements GlobalObservationConvention<ScheduledTaskObservationContext> {

        @Override
        protected KeyValue outcome(ScheduledTaskObservationContext context) {
            return context.get(SKIPPED_KEY) != null
                    ? KeyValue.of(LowCardinalityKeyNames.OUTCOME, "SKIPPED")
                    : super.outcome(context);
        }
    }

    /** 락을 못 얻으면 현재 관측에 건너뜀 표지를 남기는 락 제공자. */
    public static final class SkipMarkingLockProvider implements LockProvider {

        private final LockProvider delegate;
        private final ObservationRegistry observationRegistry;

        public SkipMarkingLockProvider(LockProvider delegate, ObservationRegistry observationRegistry) {
            this.delegate = delegate;
            this.observationRegistry = observationRegistry;
        }

        @Override
        public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
            Optional<SimpleLock> lock = delegate.lock(lockConfiguration);
            if (lock.isEmpty()) {
                Observation current = observationRegistry.getCurrentObservation();
                if (current != null && current.getContextView() instanceof Observation.Context context) {
                    context.put(SKIPPED_KEY, Boolean.TRUE);
                }
            }
            return lock;
        }
    }
}
