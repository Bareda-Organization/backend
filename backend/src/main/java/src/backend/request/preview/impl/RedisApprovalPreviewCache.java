package src.backend.request.preview.impl;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.request.preview.spec.ApprovalPreviewCache;

import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ApprovalPreviewCache} 의 Redis 구현 — 인스턴스 2대에서 승인 화면을 연 인스턴스와 결정을 처리하는 인스턴스가 달라도 같은 미리보기를 본다(Ruling 900 C).
 *
 * <p>값은 JSON 이고 키 하나에 {@value #TTL_DAYS}일 만료가 걸린다 — 결정되지 않은 채 남는 승인 건이 Redis 에 쌓이지 않는다. 결정 · 자동 거절은 {@link #evict} 로 지운다(결정의 몫).
 * 시각은 UTC 로 바꾸지 않고 읽어 오프셋을 지킨다(응답에 실리는 ETA 의 표기가 그대로다). <b>Redis 오류는 없음으로 읽는다</b> — 읽기는 빈 값(호출부가 다시 계산한다), 쓰기 · 지우기는 기록만 남기고 지나간다. 캐시가 죽었다고 승인 화면이 5xx 가 되면 안 된다.
 */
@Component
@Slf4j
public class RedisApprovalPreviewCache implements ApprovalPreviewCache {

    private static final int TTL_DAYS = 1;

    static final Duration TTL = Duration.ofDays(TTL_DAYS);

    private static final String KEY_PREFIX = "schoolbus:approval-preview:";

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final ObjectReader reader;

    public RedisApprovalPreviewCache(StringRedisTemplate redis, JsonMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
        this.reader = mapper.readerFor(ApprovalPreview.class).without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }

    @Override
    public Optional<ApprovalPreview> find(Long approvalId) {
        try {
            String json = redis.opsForValue().get(keyOf(approvalId));
            return json == null ? Optional.empty() : Optional.of(reader.readValue(json));
        } catch (RuntimeException e) {
            log.warn("승인 미리보기 읽기 실패 — 없는 것으로 보고 다시 계산한다. approvalId={}", approvalId, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(Long approvalId, ApprovalPreview preview) {
        try {
            redis.opsForValue().set(keyOf(approvalId), mapper.writeValueAsString(preview), TTL);
        } catch (RuntimeException e) {
            log.warn("승인 미리보기 저장 실패 — 저장하지 않고 넘어간다. approvalId={}", approvalId, e);
        }
    }

    @Override
    public void evict(Long approvalId) {
        try {
            redis.delete(keyOf(approvalId));
        } catch (RuntimeException e) {
            log.warn("승인 미리보기 삭제 실패 — 만료가 지운다. approvalId={}", approvalId, e);
        }
    }

    /** 개발용 초기화 전용이라 키 목록을 한 번에 읽는다(ponytail: 운영 경로가 아니다). */
    @Override
    public void clear() {
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException e) {
            log.warn("승인 미리보기 전체 삭제 실패", e);
        }
    }

    private static String keyOf(Long approvalId) {
        return KEY_PREFIX + approvalId;
    }
}
