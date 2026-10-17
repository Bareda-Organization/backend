package testsupport.db;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 풀에서 연결을 얻은 횟수 · 동시에 쥔 연결 수 · 준비한 SQL 문장을 센다(DA-6 · DA-8) — {@code @Import} 한 시험 클래스의 컨텍스트에서만 {@link DataSource} 빈을
 * 감싼다. 쿼리 수가 아니라 <b>연결 획득 수</b>가 풀 고갈을 부르는 값이라 따로 센다.
 */
@TestConfiguration
public class ConnectionCountingConfig {

    private static final AtomicInteger ACQUIRED = new AtomicInteger();

    private static final AtomicInteger OPEN = new AtomicInteger();

    private static final AtomicInteger MAX_OPEN = new AtomicInteger();

    private static final List<String> PREPARED = new CopyOnWriteArrayList<>();

    /** 센 값을 0 으로 되돌린다 — 측정할 호출 직전에 부른다. */
    public static void reset() {
        ACQUIRED.set(0);
        MAX_OPEN.set(OPEN.get());
        PREPARED.clear();
    }

    /** {@link #reset} 뒤 JDBC 로 준비한 SQL 원문(준비한 순서 · 소문자) — Hibernate 통계에 안 잡히는 JdbcTemplate 문장도 센다. */
    public static List<String> preparedStatements() {
        return List.copyOf(PREPARED);
    }

    /** {@link #reset} 뒤 풀에서 연결을 얻은 횟수. */
    public static int acquired() {
        return ACQUIRED.get();
    }

    /** {@link #reset} 뒤 한 순간에 동시에 쥔 연결 수의 최댓값. */
    public static int maxOpen() {
        return MAX_OPEN.get();
    }

    @Bean
    static BeanPostProcessor connectionCountingPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource dataSource && !(bean instanceof CountingDataSource)
                        ? new CountingDataSource(dataSource)
                        : bean;
            }
        };
    }

    private static final class CountingDataSource extends DelegatingDataSource {

        CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return counted(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return counted(super.getConnection(username, password));
        }

        private static Connection counted(Connection connection) {
            ACQUIRED.incrementAndGet();
            MAX_OPEN.accumulateAndGet(OPEN.incrementAndGet(), Math::max);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if ("prepareStatement".equals(method.getName()) && args != null && args[0] instanceof String sql) {
                            PREPARED.add(sql.toLowerCase(java.util.Locale.ROOT));
                        }
                        try {
                            Object result = method.invoke(connection, args);
                            if ("close".equals(method.getName())) {
                                OPEN.decrementAndGet();
                            }
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
