package src.backend.audit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import src.backend.account.repository.AccountRepository;
import src.backend.audit.repository.AuditLogRepository;
import testsupport.redis.Outage;
import testsupport.redis.OutageRedis;
import testsupport.redis.OutageRedisContext;

/**
 * Redis 가 멈추거나 답하지 않는 동안에도 감사 행은 빠지지 않고(첫 조회는 반드시 기록), 같은 행위자 · 학생 · 필드 묶음의 반복 조회는 이 인스턴스 안의 기억으로 10분 창에 묶여
 * 폴링 화면이 DB 쓰기(조회마다 새 트랜잭션 · 새 커넥션)를 늘리지 않는다(Ruling 900 E · 919). 감사 묶기는 보안 판정이 아니라 기록 횟수를 줄이는 장치라
 * 닫히는 쪽(조회 거절)이 아니라 기록하는 쪽으로 내려간다.
 */
class AuditDedupOutageTest {

    private static OutageRedis redis;

    @BeforeAll
    static void startRedis() {
        redis = OutageRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        redis.close();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("Redis 장애 중 같은 학생을 반복 조회해도 감사 행은 1개 — 첫 조회는 기록되고 조회는 항상 성공한다")
    void 장애_중에도_반복_조회는_감사_행_1개(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
            AuditRecorder recorder = new AuditRecorder(auditLogRepository, mock(AccountRepository.class), Clock.systemUTC(),
                    mock(PlatformTransactionManager.class), context.getBean(StringRedisTemplate.class));
            outage.begin(redis);
            try {
                for (int i = 0; i < 8; i++) {
                    recorder.recordDataAccessRead(1L, 7L, "run_roster", 77L,
                            Map.of("student_ids", List.of("5"), "fields", List.of("note")));
                }

                verify(auditLogRepository, times(1)).saveAll(any());
            } finally {
                outage.end(redis);
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("Redis 장애 중 다른 학생·다른 필드 묶음은 각자 기록된다")
    void 장애_중에도_다른_학생은_각자_기록된다(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
            AuditRecorder recorder = new AuditRecorder(auditLogRepository, mock(AccountRepository.class), Clock.systemUTC(),
                    mock(PlatformTransactionManager.class), context.getBean(StringRedisTemplate.class));
            outage.begin(redis);
            try {
                recorder.recordDataAccessRead(1L, 7L, "run_roster", 77L, Map.of("student_ids", List.of("5"), "fields", List.of("note")));
                recorder.recordDataAccessRead(1L, 7L, "run_roster", 77L, Map.of("student_ids", List.of("6"), "fields", List.of("note")));
                recorder.recordDataAccessRead(1L, 7L, "run_roster", 77L, Map.of("student_ids", List.of("5"), "fields", List.of("guardian_phone")));

                verify(auditLogRepository, times(3)).saveAll(any());
            } finally {
                outage.end(redis);
            }
        });
    }
}
