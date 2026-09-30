package src.backend.global.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * {@code PATCH} 본문의 <b>키가 있다</b>는 사실과 그 값(Ruling 390) — 요청 레코드의 항목을 이 타입으로 받으면 키가 없는 것과
 * 명시적 {@code null} 을 가를 수 있다. 항목이 {@code null} 이면 키가 없는 것(유지)이고, {@code Patch} 가 있으면 키가 있는 것이며
 * 그 {@link #value()} 가 {@code null} 이면 명시적 {@code null} 이다.
 *
 * <p>{@code Optional} 로 받지 않는 이유 — Jackson 3 는 키가 없을 때도, {@code null} 일 때도 {@code Optional.empty()} 를 넣어
 * 둘을 가를 수 없다(실측 — {@code @JsonSetter(nulls = SET)} 을 달아도 같다).
 *
 * <p>규칙 — 키 없음은 유지 · 선택(○) 항목의 {@code null}·빈 문자열은 지움 · 필수(●) 항목의 {@code null}·빈 문자열은
 * {@code 422 VALIDATION_FAILED}. 다른 {@code PATCH} 에 넓힐 때도 이 타입을 쓴다.
 *
 * <p>Bean Validation 은 {@code Patch<@Size(max = 30) String>} 처럼 안쪽 값에 걸리고(추출기 {@link PatchValueExtractor}),
 * Swagger 는 {@code @Schema(implementation = …)} 로 안쪽 타입을 알려 준다.
 */
@JsonDeserialize(using = Patch.Deserializer.class)
public final class Patch<T> {

    private final T value;

    private Patch(T value) {
        this.value = value;
    }

    /** 키가 있고 값이 {@code value} 인 항목({@code null} 이면 명시적 {@code null}). */
    public static <T> Patch<T> of(T value) {
        return new Patch<>(value);
    }

    /** 보낸 값 — 명시적 {@code null} 이면 {@code null} 이다. */
    public T value() {
        return value;
    }

    /** 필수(●) 항목 — 키가 없으면 {@code current}, 명시적 {@code null}·빈 문자열은 {@code 422}, 그 외는 보낸 값. */
    public static <T> T required(Patch<T> field, T current) {
        if (field == null) {
            return current;
        }
        if (field.value == null || isBlankText(field.value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return field.value;
    }

    /** 선택(○) 항목 — 키가 없으면 {@code current}, 명시적 {@code null}·빈 문자열은 {@code null}(지움), 그 외는 보낸 값. */
    public static <T> T optional(Patch<T> field, T current) {
        if (field == null) {
            return current;
        }
        return clears(field) ? null : field.value;
    }

    /** 선택(○) 항목이 지우라는 뜻인가 — 키는 있는데 값이 {@code null} 이거나 빈 문자열이다. */
    public static boolean clears(Patch<?> field) {
        return field != null && (field.value == null || isBlankText(field.value));
    }

    private static boolean isBlankText(Object value) {
        return value instanceof String text && text.isBlank();
    }

    /**
     * {@code Patch<T>} 를 읽는다 — 안쪽 값은 {@code T} 의 역직렬화기에 맡기고, JSON {@code null} 은 {@code Patch.of(null)} 로,
     * 키가 없는 것은 {@code null} 로 돌려준다(그래서 둘이 갈린다).
     */
    static final class Deserializer extends ValueDeserializer<Patch<?>> {

        private final ValueDeserializer<Object> inner;

        /** Jackson 이 어노테이션으로 만드는 자리 — 안쪽 타입을 모르는 상태라 {@link #createContextual} 이 다시 만든다. */
        Deserializer() {
            this(null);
        }

        private Deserializer(ValueDeserializer<Object> inner) {
            this.inner = inner;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
            JavaType valueType = property.getType().containedTypeOrUnknown(0);
            return new Deserializer(ctxt.findContextualValueDeserializer(valueType, property));
        }

        @Override
        public Patch<?> deserialize(JsonParser parser, DeserializationContext ctxt) {
            return Patch.of(inner.deserialize(parser, ctxt));
        }

        @Override
        public Object getNullValue(DeserializationContext ctxt) {
            return Patch.of(null);
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }
}
