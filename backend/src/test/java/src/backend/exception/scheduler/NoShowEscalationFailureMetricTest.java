package src.backend.exception.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.micrometer.core.instrument.MeterRegistry;

import src.backend.exception.command.NoShowEscalationPersistence;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.repository.NoShowCaseRepository;

/**
 * 스케줄러가 건별로 잡아 삼킨 실패도 {@code schoolbus.scheduler.failures} 에 세어진다(BR-064 · TECH_DECISIONS §13.4).
 *
 * <p>에스컬레이션이 매 틱 전건 실패해도 메서드는 정상 반환해 애스펙트는 "성공" 만 기록한다 — 실패 지표가 0 이면
 * 미승차 3분 초과 미보고 알럿을 만들 재료가 없다.
 */
@SpringBootTest
class NoShowEscalationFailureMetricTest {

    @MockitoBean
    private NoShowCaseRepository noShowCaseRepository;

    @MockitoBean
    private NoShowEscalationPersistence persistence;

    @Autowired
    private NoShowEscalationScheduler scheduler;

    @Autowired
    private MeterRegistry registry;

    @Test
    void 건별_에스컬레이션_실패가_스케줄러_실패_지표에_세어진다() {
        NoShowCase due = mock(NoShowCase.class);
        given(due.getId()).willReturn(1L);
        given(noShowCaseRepository.findDueForEscalation(any(), any())).willReturn(List.of(due));
        given(persistence.escalateOne(any(), any())).willThrow(new IllegalStateException("심은 실패"));
        double before = failures();

        scheduler.escalateDueNoShowCases();

        assertThat(failures() - before).isEqualTo(1.0d);
    }

    private double failures() {
        var counter = registry.find("schoolbus.scheduler.failures").tag("scheduler", "no-show-escalation").counter();
        return counter == null ? 0.0d : counter.count();
    }
}
