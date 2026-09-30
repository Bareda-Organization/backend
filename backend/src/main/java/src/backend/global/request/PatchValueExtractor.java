package src.backend.global.request;

import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.ValueExtractor;

/**
 * {@code Patch<@Size(max = 30) String>} 의 안쪽 값에 Bean Validation 을 건다 — {@code META-INF/services} 에 등록돼 검증기가
 * 자동으로 찾는다. 이 추출기가 없으면 {@code Patch} 안의 제약은 조용히 무시돼 길이 초과가 DB 오류(500)로 샌다.
 */
public class PatchValueExtractor implements ValueExtractor<Patch<@ExtractedValue ?>> {

    @Override
    public void extractValues(Patch<?> originalValue, ValueReceiver receiver) {
        receiver.value(null, originalValue.value());
    }
}
