package src.backend.observability.aspect;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import lombok.RequiredArgsConstructor;

import src.backend.observability.metrics.SchedulerHealthMetrics;

/**
 * {@code @Scheduled} 메서드를 감싸 성공·실패를 계측한다.
 *
 * <p>계측 호출을 스케줄러 코드 안에 넣지 않는 이유는 두 가지다. 도메인이 관측 모듈을 참조하지 않게 하고,
 * 스케줄러가 늘어도 계측이 자동으로 따라붙게 하려는 것이다.
 *
 * <p>태그 값은 선언 클래스명에서 유도한다 — {@code ConnectionLossScheduler} 는 {@code connection-loss} 다.
 * 이름 목록을 상수로 들고 있으면 스케줄러가 추가될 때 함께 고쳐야 하는데, 그 갱신을 잊으면 계측이 조용히 빠진다.
 *
 * <p>같은 이유로 {@code schoolbus.scheduler.failures} 카운터도 기동 시 0으로 선등록해야 하는데(관측
 * 시험이 이벤트 없이 이름 존재를 확인한다), 이름 목록을 상수로 만들면 위 원칙과 정면으로 충돌한다.
 *
 * <p>선등록은 {@link ApplicationContext} 가 가진 빈 정의 전부를 훑어 {@code @Scheduled} 가 붙은
 * 메서드를 가진 빈을 찾는 방식으로 한다({@code SchedulerLockConventionTest} 가 소스를 훑는 것과
 * 같은 탐지 방식, 대상만 컴파일된 빈이라는 차이). 처음에는
 * {@code ScheduledAnnotationBeanPostProcessor#getScheduledTasks()} 로 시도했으나, Spring Framework 7
 * 부터 그 결과의 실행체가 {@code Task$OutcomeTrackingRunnable}(기동 성과 추적, 신규 기능)로 한 겹
 * 더 감싸져 있어 {@code ScheduledMethodRunnable} 로 바로 캐스팅되지 않았다(실측: 실제
 * {@code /actuator/prometheus} 스크레이프에서 이 경로로 선등록한 카운터가 전혀 노출되지 않았다) —
 * 그 래퍼는 package-private 이고 감싼 대상을 꺼내는 공개 접근자가 없어 공개 API 만으로는 벗겨낼
 * 방법이 없었다. 빈 정의를 직접 훑는 이 방식은 그 내부 표현에 기대지 않는다.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class ScheduledTaskMetricsAspect implements ApplicationListener<ContextRefreshedEvent> {

    private final SchedulerHealthMetrics metrics;

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object measure(ProceedingJoinPoint joinPoint) throws Throwable {
        String scheduler = schedulerName(joinPoint.getTarget().getClass());
        try {
            Object result = joinPoint.proceed();
            metrics.recordSuccess(scheduler);
            return result;
        } catch (Throwable t) {
            // 계측만 하고 그대로 다시 던진다. 삼키면 스케줄러의 기존 예외 처리 동작이 바뀐다.
            metrics.recordFailure(scheduler);
            throw t;
        }
    }

    /**
     * 기동이 끝나는 시점에 컨텍스트가 가진 빈 정의 전부를 훑어, {@code @Scheduled} 메서드를 가진
     * 빈마다 게이지·카운터를 0 으로 선등록한다.
     *
     * <p>{@code ContextRefreshedEvent} 는 모든 싱글톤 초기화가 끝난 뒤에만 발행되므로, 이 시점엔
     * 빈 목록이 항상 최종 상태다.
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        ApplicationContext context = event.getApplicationContext();
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> beanType = context.getType(beanName);
            if (beanType == null) {
                continue;
            }
            Class<?> targetClass = ClassUtils.getUserClass(beanType);
            if (hasScheduledMethod(targetClass)) {
                metrics.preRegister(schedulerName(targetClass));
            }
        }
    }

    private boolean hasScheduledMethod(Class<?> targetClass) {
        for (Method method : targetClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Scheduled.class)) {
                return true;
            }
        }
        return false;
    }

    /** {@code ConnectionLossScheduler} → {@code connection-loss}. 접미사 Scheduler 를 떼고 케밥으로 바꾼다. */
    private String schedulerName(Class<?> targetClass) {
        String simpleName = ClassUtils.getUserClass(targetClass).getSimpleName();
        String base = simpleName.endsWith("Scheduler")
                ? simpleName.substring(0, simpleName.length() - "Scheduler".length())
                : simpleName;
        return base.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }
}
