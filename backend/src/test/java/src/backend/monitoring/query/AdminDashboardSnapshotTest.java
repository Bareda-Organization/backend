package src.backend.monitoring.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 메인 관리자 대시보드(§6.18)의 DB 읽기는 하나의 읽기 전용 스냅숏 안에서 돈다 — 지표마다 따로 읽으면 같은 응답 안에서 합계가 서로 맞지
 * 않을 수 있다(예: 오늘 회차 수와 지표의 회차 수). 시스템 상태는 Redis 를 읽으므로 DB 연결을 쥔 스냅숏 밖에서 읽는다.
 */
@SpringBootTest
class AdminDashboardSnapshotTest {

    @Autowired
    private AdminDashboardQueryService dashboardService;

    @MockitoSpyBean
    private AdminDashboardLoginMetrics loginMetrics;

    @MockitoSpyBean
    private AdminDashboardTodayRuns todayRuns;

    @MockitoSpyBean
    private AdminDashboardAttentionReader attentionReader;

    @MockitoSpyBean
    private AdminDashboardEventReader eventReader;

    @MockitoSpyBean
    private SystemHealthReader healthReader;

    private record Probe(String reader, boolean active, boolean readOnly, Integer isolation) {
    }

    private final List<Probe> probes = new CopyOnWriteArrayList<>();

    @Test
    void 지표_읽기는_반복_읽기_전용_트랜잭션_안에서_돌고_시스템_상태는_그_밖이다() {
        doAnswer(probing("login")).when(loginMetrics).read(any());
        doAnswer(probing("todayRuns")).when(todayRuns).read(any(), any());
        doAnswer(probing("attention")).when(attentionReader).read(any(), any(), any(), any());
        doAnswer(probing("events")).when(eventReader).read(any(), any(), any());
        doAnswer(probing("health")).when(healthReader).read();

        dashboardService.dashboard(7, null);

        assertThat(probes).extracting(Probe::reader).containsExactlyInAnyOrder("login", "todayRuns", "attention",
                "events", "health");
        assertThat(probes).filteredOn(probe -> !probe.reader().equals("health")).allSatisfy(probe -> {
            assertThat(probe.active()).as(probe.reader() + " 트랜잭션").isTrue();
            assertThat(probe.readOnly()).as(probe.reader() + " 읽기 전용").isTrue();
            assertThat(probe.isolation()).as(probe.reader() + " 격리 수준").isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        });
        assertThat(probes).filteredOn(probe -> probe.reader().equals("health")).singleElement()
                .satisfies(probe -> assertThat(probe.active()).as("시스템 상태는 DB 트랜잭션 밖").isFalse());
    }

    private org.mockito.stubbing.Answer<Object> probing(String reader) {
        return (InvocationOnMock invocation) -> {
            probes.add(new Probe(reader, TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()));
            return invocation.callRealMethod();
        };
    }
}
