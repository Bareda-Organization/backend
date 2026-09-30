package testsupport.concurrency;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.repository.Repository;

/**
 * 저장소 조회가 끝난 <b>직후</b>에 시험이 정한 동작을 끼워 넣는다 — "읽고 나서 쓰기 전" 경합 창을 시험이 강제하는 수단이다.
 *
 * <p>스프링 데이터 저장소는 인터페이스 프록시라 Mockito 스파이로는 원본을 부를 수 없다({@code callRealMethod()} 가
 * "abstract real method" 로 실패). 그래서 저장소 빈을 한 겹 더 감싸 메서드 이름으로 훅을 찾는다. 이 설정을 {@code @Import}
 * 한 시험 클래스만 그 컨텍스트를 갖는다. 훅은 시험이 {@link #afterRead} 로 등록하고 뒷정리에서 {@link #clear} 로 비운다.
 */
@TestConfiguration
public class RepositoryReadHooks {

    private static final Map<String, Runnable> AFTER_READ = new ConcurrentHashMap<>();

    /** 그 이름의 저장소 메서드가 결과를 돌려준 직후 {@code hook} 을 그 스레드에서 실행한다. */
    public static void afterRead(String repositoryMethodName, Runnable hook) {
        AFTER_READ.put(repositoryMethodName, hook);
    }

    public static void clear() {
        AFTER_READ.clear();
    }

    @Bean
    static BeanPostProcessor repositoryReadHook() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof Repository<?, ?>)) {
                    return bean;
                }
                ProxyFactory factory = new ProxyFactory(bean);
                factory.addAdvice((MethodInterceptor) invocation -> {
                    Object result = invocation.proceed();
                    Runnable hook = AFTER_READ.get(invocation.getMethod().getName());
                    if (hook != null) {
                        hook.run();
                    }
                    return result;
                });
                return factory.getProxy();
            }
        };
    }
}
