package src.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.util.ClassUtils;

/**
 * 스케줄러가 스프링 기본 계측({@code tasks.scheduled.execution})으로 Prometheus 에 노출되는지 확인한다 —
 * 마지막 성공 경과·실패 경보가 이 지표를 읽는다(Ruling 903 D).
 *
 * <p>배경 실행은 시험 동안 미뤄져 있으므로 등록된 작업을 프레임워크 래퍼로 한 번씩 돌린다 — 기본 계측은
 * 스케줄러가 호출할 때만 붙기 때문이다. 예외로 끝나도 {@code outcome="error"} 로 노출되므로 작업 결과는 보지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulerBuiltInMetricsTest {

    /** 운영 스케줄러 12개 + 로컬 프로파일의 데모 시뮬레이터(시험 컨텍스트에서는 함께 뜬다). */
    private static final int MIN_SCHEDULER_COUNT = 12;

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ObservationRegistry observationRegistry;

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

    /** 크론 작업은 등록기가 돌려주는 목록에 없을 수 있어, 스케줄러가 쓰는 래퍼를 컨텍스트의 관측 등록기로 직접 만들어 돌린다. */
    private void runEveryScheduledMethodOnce() {
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(beanName);
            if (type == null) {
                continue;
            }
            for (Method method : ClassUtils.getUserClass(type).getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    new ScheduledMethodRunnable(context.getBean(beanName), method, null, () -> observationRegistry).run();
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
                names.add(userClass.getName());
            }
        }
        return names;
    }
}
