package src.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.util.ClassUtils;

/**
 * 앱의 스케줄러 배선이 기본 계측({@code tasks.scheduled.execution})까지 이어져 있는지 확인한다 — 경보가 이 지표를 읽는다(Ruling 903 D).
 * ① 앱이 쓰는 실제 등록기로 도는 작업(이 시험 전용 더미 {@code @Scheduled} 빈)이 기본 계측을 남긴다: 등록기와 관측 등록기의 연결이 끊어지면 실패한다.
 * ② 스케줄러 클래스마다 지표 태그가 클래스 이름으로 나온다(아래 설명).
 *
 * <p>스케줄러가 스프링 기본 계측({@code tasks.scheduled.execution})으로 Prometheus 에 노출되는지 확인한다 —
 * 마지막 성공 경과·실패 경보가 이 지표를 읽는다(Ruling 903 D).
 *
 * <p>배경 실행은 시험 동안 미뤄져 있으므로 등록된 작업을 프레임워크 래퍼로 한 번씩 돌린다 — 기본 계측은
 * 스케줄러가 호출할 때만 붙기 때문이다. 예외로 끝나도 {@code outcome="error"} 로 노출되므로 작업 결과는 보지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SchedulerBuiltInMetricsTest.DummySchedulerConfig.class)
class SchedulerBuiltInMetricsTest {

    /** 운영 스케줄러 12개 + 로컬 프로파일의 데모 시뮬레이터(시험 컨텍스트에서는 함께 뜬다). */
    private static final int MIN_SCHEDULER_COUNT = 12;

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    /** 앱의 등록기가 직접 돌리는 더미 작업이 기본 계측 타이머를 남긴다 — DB 를 건드리지 않는 작업이라 실제 등록기 경로 전체를 안전하게 지난다. */
    @Test
    void 실제_등록기로_도는_스케줄_작업이_기본_계측을_남긴다() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(meterRegistry.find("tasks.scheduled.execution")
                        .tag("code.namespace", DummyScheduler.class.getCanonicalName()).timer())
                        .as("앱의 스케줄 등록기가 관측 등록기와 이어져 있지 않으면 작업이 돌아도 기본 계측이 없다").isNotNull());
    }

    @Test
    void 스케줄러_전부_기본_계측으로_노출된다() {
        Set<String> expected = schedulerClassNames();
        assertThat(expected).as("@Scheduled 를 가진 빈").hasSizeGreaterThanOrEqualTo(MIN_SCHEDULER_COUNT);

        runEveryScheduledMethodOnce();

        String body = new TestRestTemplate().getForObject("http://localhost:" + port + "/actuator/prometheus",
                String.class);
        for (String schedulerClass : expected) {
            assertThat(body)
                    .as("tasks_scheduled_execution 의 code_namespace 에 %s 가 있어야 한다", schedulerClass)
                    .contains("tasks_scheduled_execution_seconds_count{")
                    .containsPattern("tasks_scheduled_execution_seconds_count\\{[^}]*code_namespace=\""
                            + schedulerClass.replace(".", "\\.") + "\"");
        }
    }

    /**
     * 스케줄러가 쓰는 래퍼({@code ScheduledMethodRunnable})를 컨텍스트의 관측 등록기로 만들어 돌린다. 대상은 실제 빈이 아니라 목이다 — 실제 빈을 돌리면
     * 일일 회차 생성 · 보존 정리 같은 작업이 공유 DB 를 바꿔 다른 시험(예: 학생 회차 목록)의 기대 건수를 흔든다. 인라인 목은 클래스 자체라 지표의
     * {@code code_namespace} 가 실제 클래스 이름으로 나온다.
     */
    private void runEveryScheduledMethodOnce() {
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(beanName);
            if (type == null) {
                continue;
            }
            for (Method method : ClassUtils.getUserClass(type).getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    new ScheduledMethodRunnable(Mockito.mock(ClassUtils.getUserClass(type)), method, null, () -> observationRegistry).run();
                }
            }
        }
    }

    private Set<String> schedulerClassNames() {
        Set<String> names = new TreeSet<>();
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(beanName);
            if (type == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);
            if (Arrays.stream(userClass.getDeclaredMethods()).anyMatch(m -> m.isAnnotationPresent(Scheduled.class))) {
                names.add(userClass.getCanonicalName());
            }
        }
        return names;
    }

    /** 1초마다 도는 더미 작업 — 시험 전용. */
    static class DummyScheduler {

        @Scheduled(fixedDelay = 1000)
        public void tick() {
            // 계측만 보는 작업이라 하는 일이 없다
        }
    }

    static class DummySchedulerConfig {

        @Bean
        DummyScheduler dummyScheduler() {
            return new DummyScheduler();
        }
    }
}
