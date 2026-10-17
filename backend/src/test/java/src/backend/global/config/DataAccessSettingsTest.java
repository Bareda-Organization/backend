package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import jakarta.persistence.EntityManagerFactory;

import src.backend.global.retention.RetentionCleanupScheduler;
import src.backend.notification.repository.NotificationLogRepository;

/**
 * DA-4 · DA-5(Ruling 899 L) — 데이터 접근 설정이 <b>실제 연결 · 실제 영속성 단위</b>에서 효력을 갖는다. 설정 문자열이 아니라
 * 연결에 {@code SHOW} 를 쏴서 본다(드라이버가 모르는 속성은 조용히 무시된다).
 */
@SpringBootTest
class DataAccessSettingsTest {

    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;
    @Autowired private RetentionCleanupScheduler retentionCleanupScheduler;
    @MockitoSpyBean private NotificationLogRepository notificationLogRepository;

    @Test
    @DisplayName("DA-5 — IN 절 파라미터 패딩이 켜져 있다(IN 목록 길이마다 새 쿼리 계획이 생기지 않는다)")
    void IN_절_파라미터_패딩이_켜져_있다() {
        assertThat(String.valueOf(entityManagerFactory.getProperties().get("hibernate.query.in_clause_parameter_padding")))
                .isEqualTo("true");
    }

    @Test
    @DisplayName("DA-4 — 일반 연결의 statement_timeout 은 30초다")
    void 일반_연결의_statement_timeout_은_30초다() {
        assertThat(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("30s");
    }

    @Test
    @DisplayName("DA-4 — Flyway 는 풀과 별개의 연결을 쓰고 그 연결에는 statement_timeout 이 없다(긴 마이그레이션이 30초에 잘리지 않는다)")
    void Flyway_는_자기_연결을_쓰고_시간_상한이_없다() throws Exception {
        DataSource flywayDataSource = flyway.getConfiguration().getDataSource();

        assertThat(flywayDataSource).isNotSameAs(dataSource);
        try (Connection connection = flywayDataSource.getConnection();
                ResultSet result = connection.createStatement().executeQuery("SHOW statement_timeout")) {
            result.next();
            assertThat(result.getString(1)).isEqualTo("0");
        }
    }

    @Test
    @DisplayName("DA-4 — 보존 정리 트랜잭션 안의 statement_timeout 은 0 이다")
    void 보존_정리_트랜잭션_안의_statement_timeout_은_0이다() {
        List<String> seen = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            seen.add(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class));
            return new ArrayList<Long>();
        }).when(notificationLogRepository).findIdsForRetentionCleanup(any(), any());

        retentionCleanupScheduler.cleanUp();

        assertThat(seen).as("정리 조회가 돈 트랜잭션 안에서 본 statement_timeout").isNotEmpty().containsOnly("0");
    }
}
