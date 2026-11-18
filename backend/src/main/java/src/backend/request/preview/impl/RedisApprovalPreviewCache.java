package src.backend.request.preview.impl;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import src.backend.global.common.logging.RateLimitedWarn;
import src.backend.global.redis.LocalFallbackMemory;
import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.request.preview.spec.ApprovalPreviewCache;

import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ApprovalPreviewCache} 의 Redis 구현 — 인스턴스 2대에서 승인 화면을 연 인스턴스와 결정을 처리하는 인스턴스가 달라도 같은 미리보기를 본다(Ruling 900 C).
 *
 * <p>값은 JSON 이고 키 하나에 {@value #TTL_DAYS}일 만료가 걸린다 — 결정되지 않은 채 남는 승인 건이 Redis 에 쌓이지 않는다. 결정 · 자동 거절은 {@link #evict} 로 지운다(결정의 몫).
 * 시각은 UTC 로 바꾸지 않고 읽어 오프셋을 지킨다(응답에 실리는 ETA 의 표기가 그대로다). <b>Redis 오류로 승인 화면이 5xx 가 되면 안 된다</b> — Redis 를 못 쓰는 동안은 쓰기를 이 인스턴스 안의 기억({@link LocalFallbackMemory})이 받고 읽기도 거기서 읽어, 같은 입력의
 * 반복 조회가 지도 API 를 매번 부르지 않는다(Ruling 919 B ⑤). Redis 가 다시 답해도 키가 없으면 그 기억을 읽어, 멈춘 동안 저장한 미리보기가 승인 때 사라지지 않는다. 거기에도 없으면 빈 값이라 호출부가 다시 계산하고, 지우기는 둘 다 지운다. Redis 실패 경고는 한 간격에 한 건이다.
 */
@Component
public class RedisApprovalPreviewCache implements ApprovalPreviewCache {

    private static final int TTL_DAYS = 1;

    static final Duration TTL = Duration.ofDays(TTL_DAYS);

    /** Redis 를 못 쓰는 동안 이 인스턴스가 미리보기를 들고 있는 시간 — 같은 입력의 반복 조회가 지도 API 를 다시 부르지 않게 한다(Ruling 919 B ⑤). */
    private static final Duration FALLBACK_TTL = Duration.ofMinutes(30);

    private static final int FALLBACK_MAX_ENTRIES = 1_000;

    private static final String KEY_PREFIX = "schoolbus:approval-preview:";

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    private final LocalFallbackMemory<ApprovalPreview> whileRedisIsDown;
    private final RateLimitedWarn redisFailure = RateLimitedWarn.perMinute(
            org.slf4j.LoggerFactory.getLogger(RedisApprovalPreviewCache.class));

    @Autowired
    public RedisApprovalPreviewCache(StringRedisTemplate redis, JsonMapper mapper, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.whileRedisIsDown = new LocalFallbackMemory<>(clock, FALLBACK_MAX_ENTRIES);
        this.reader = mapper.readerFor(ApprovalPreview.class).without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }

    @Override
    public Optional<ApprovalPreview> find(Long approvalId) {
        try {
            String json = redis.opsForValue().get(keyOf(approvalId));
            if (json != null) {
                return Optional.of(reader.readValue(json));
            }
        } catch (RuntimeException e) {
            redisFailure.warn(e, "승인 미리보기 읽기 실패 — 이 인스턴스 안의 기억을 본다. approvalId={}", approvalId);
        }
        return whileRedisIsDown.get(keyOf(approvalId));
    }

    @Override
    public void put(Long approvalId, ApprovalPreview preview) {
        try {
            redis.opsForValue().set(keyOf(approvalId), mapper.writeValueAsString(preview), TTL);
            whileRedisIsDown.remove(keyOf(approvalId));
        } catch (RuntimeException e) {
            redisFailure.warn(e, "승인 미리보기 저장 실패 — 이 인스턴스 안에 {} 동안 들고 있는다. approvalId={}", FALLBACK_TTL, approvalId);
            whileRedisIsDown.put(keyOf(approvalId), preview, FALLBACK_TTL);
        }
    }

    @Override
    public void evict(Long approvalId) {
        whileRedisIsDown.remove(keyOf(approvalId));
        try {
            redis.delete(keyOf(approvalId));
        } catch (RuntimeException e) {
            redisFailure.warn(e, "승인 미리보기 삭제 실패 — 만료가 지운다. approvalId={}", approvalId);
        }
    }

    /** 개발용 초기화 전용이라 키 목록을 한 번에 읽는다(ponytail: 운영 경로가 아니다). */
    @Override
    public void clear() {
        whileRedisIsDown.clear();
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException e) {
            redisFailure.warn(e, "승인 미리보기 전체 삭제 실패");
        }
    }

    private static String keyOf(Long approvalId) {
        return KEY_PREFIX + approvalId;
    }
}
