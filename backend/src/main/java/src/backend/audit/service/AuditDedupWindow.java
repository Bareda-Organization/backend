package src.backend.audit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import src.backend.global.common.logging.RateLimitedWarn;
import src.backend.global.redis.LocalFallbackMemory;

/**
 * L3 조회 감사의 반복 조회 묶기 기록 — 같은 행위자가 같은 학생·필드 묶음을 {@link AuditRecorder#DATA_ACCESS_DEDUP_WINDOW}
 * 안에 다시 조회했는지 판정(claim)하고, 행을 못 쓴 조회의 기록을 물리는(release) 일만 맡는다.
 * 저장소는 Redis 이고, Redis 를 못 쓰는 동안은 이 인스턴스 안의 기억이 같은 창을 대신한다.
 * {@link AuditRecorder} 가 생성자에서 직접 만들어 쓰는 내부 협력 객체라 빈이 아니다.
 */
final class AuditDedupWindow {

    /** 감사 기록의 로그는 {@link AuditRecorder} 이름으로 남긴다 — 운영에서 감사 로그를 한 이름으로 거른다. */
    private static final Logger LOG = LoggerFactory.getLogger("src.backend.audit.service.AuditRecorder");

    /** {@code detail} 에서 응답에 실린 학생 id 를 담는 키 — 묶기 키(행위자·학생)의 학생 쪽이다. */
    private static final String STUDENT_IDS_KEY = "student_ids";

    /** {@code detail} 에서 응답에 실린 L3 필드명 목록을 담는 키 — 묶기 키의 필드 쪽이다. */
    private static final String FIELDS_KEY = "fields";

    private static final String DEDUP_KEY_PREFIX = "schoolbus:audit-dedup:";

    private static final int DEDUP_FALLBACK_MAX_ENTRIES = 10_000;

    /** 묶기 키 — 행위자·학생·<b>조회한 필드 묶음</b>. 필드가 다르면 다른 접근이라(명단의 사진·특이사항 vs 보호자 원번호) 서로를 가리지 않는다(Ruling 521). */
    record ViewedStudent(Long actorAccountId, String studentId, String fields) {
    }

    /** 묶기 기록 저장소 — 인스턴스가 여러 대여도 같은 창을 쓰도록 Redis 에 둔다(Ruling 900 E). 값은 행을 쓴 시각(epoch ms)이다. */
    private final StringRedisTemplate redis;

    /** Redis 를 못 쓰는 동안의 묶기 기록 — 반복 조회가 조회마다 감사 행(새 트랜잭션 · 새 커넥션)을 쓰지 않게 한다. 인스턴스 1대 기준의 같은 창이다(Ruling 919 B ⑤). */
    private final LocalFallbackMemory<Boolean> whileRedisIsDown;

    /** 판정과 되돌리기는 서로 다른 실패라 경고 제한을 따로 둔다 — 한쪽이 다른 쪽 로그를 가리지 않는다. */
    private final RateLimitedWarn claimFailure = RateLimitedWarn.perMinute(LOG);

    private final RateLimitedWarn releaseFailure = RateLimitedWarn.perMinute(LOG);

    AuditDedupWindow(Clock clock, StringRedisTemplate redis) {
        this.redis = redis;
        this.whileRedisIsDown = new LocalFallbackMemory<>(clock, DEDUP_FALLBACK_MAX_ENTRIES);
    }

    /**
     * {@code detail} 에 실린 학생 중 창 안에 이미 기록한 학생을 뺀다 — 새로 기록할 학생이 하나도 없으면 {@code null}.
     * 새로 기록하기로 한 학생은 그 자리에서 {@code claimed} 에 담고 시각을 남긴다(동시에 들어온 같은 조회가 둘 다
     * "새 학생" 으로 판정하지 않게 원자적으로). 학생 목록이 없는 {@code detail} 은 묶지 않고 그대로 쓴다.
     */
    Map<String, Object> claimNewStudents(Long actorAccountId, Map<String, Object> detail, Instant now,
            List<ViewedStudent> claimed) {
        if (!(detail.get(STUDENT_IDS_KEY) instanceof List<?> studentIds) || studentIds.isEmpty()) {
            return detail;
        }
        List<String> fresh = claimEach(actorAccountId, detail, studentIds, now, claimed);
        if (fresh.isEmpty()) {
            return null;
        }
        if (fresh.size() == studentIds.size()) {
            return detail;
        }
        Map<String, Object> narrowed = new LinkedHashMap<>(detail);
        narrowed.put(STUDENT_IDS_KEY, fresh);
        return narrowed;
    }

    /** 학생마다 묶기 판정을 맡아 새로 기록할 학생 id 만 모으고, 맡은 항목은 {@code claimed} 에 담는다. */
    private List<String> claimEach(Long actorAccountId, Map<String, Object> detail, List<?> studentIds, Instant now,
            List<ViewedStudent> claimed) {
        List<String> fresh = new ArrayList<>();
        for (Object studentId : studentIds) {
            ViewedStudent viewed = new ViewedStudent(actorAccountId, String.valueOf(studentId),
                    String.valueOf(detail.get(FIELDS_KEY)));
            boolean isNew = claim(viewed, now);
            if (isNew) {
                fresh.add(viewed.studentId());
                claimed.add(viewed);
            }
        }
        return fresh;
    }

    /**
     * 이 행위자·학생·필드 묶음을 창 안에 처음 보는 조회인지 Redis 로 판정해 맡는다 — {@code SET NX EX} 한 번이라 인스턴스가 여럿이고 호출이 동시여도 정확히 하나만 참을 받는다.
     * 키가 남아 있어도 값의 시각이 창을 넘었으면(Redis 만료와 앱 시계가 어긋날 때) 새 조회로 보고 시각을 새로 쓴다. Redis 오류는 이 인스턴스 안의 기억으로 같은 창을 판정한다 — 첫 조회는 기록되므로 행이 빠지지 않는다.
     */
    private boolean claim(ViewedStudent viewed, Instant now) {
        String key = keyOf(viewed);
        String nowMillis = String.valueOf(now.toEpochMilli());
        try {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, nowMillis, AuditRecorder.DATA_ACCESS_DEDUP_WINDOW))) {
                return true;
            }
            String last = redis.opsForValue().get(key);
            if (last != null && now.isBefore(Instant.ofEpochMilli(Long.parseLong(last)).plus(AuditRecorder.DATA_ACCESS_DEDUP_WINDOW))) {
                return false;
            }
            redis.opsForValue().set(key, nowMillis, AuditRecorder.DATA_ACCESS_DEDUP_WINDOW);
            return true;
        } catch (RuntimeException e) {
            claimFailure.warn(e, "감사 묶기 기록을 Redis 에서 판정하지 못했다 — 이 인스턴스 안의 기억으로 판정한다. actor={} student={}",
                    viewed.actorAccountId(), viewed.studentId());
            return whileRedisIsDown.claim(key, Boolean.TRUE, AuditRecorder.DATA_ACCESS_DEDUP_WINDOW);
        }
    }

    /** 행을 못 쓴 조회의 묶기 기록을 물린다. */
    void releaseClaim(ViewedStudent viewed) {
        whileRedisIsDown.remove(keyOf(viewed));
        try {
            redis.delete(keyOf(viewed));
        } catch (RuntimeException e) {
            releaseFailure.warn(e, "감사 묶기 기록을 되돌리지 못했다 — 창이 끝날 때까지 같은 조회가 기록에서 빠질 수 있다. actor={} student={}",
                    viewed.actorAccountId(), viewed.studentId());
        }
    }

    private static String keyOf(ViewedStudent viewed) {
        return DEDUP_KEY_PREFIX + viewed.actorAccountId() + ":" + viewed.studentId() + ":" + viewed.fields();
    }
}
