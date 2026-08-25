package src.backend.global.common.converter;

import jakarta.persistence.AttributeConverter;

/**
 * enum 상수를 소문자 snake_case DB 값으로 잇는 공용 변환기 — 이 스키마의 CHECK 제약은 전부 소문자
 * 값을 요구하는데 {@code @Enumerated(EnumType.STRING)} 은 {@code Enum.name()}(대문자)을 그대로
 * 내보내 그 제약을 전건 위반한다(Ruling 32).
 *
 * <p>JPA {@code AttributeConverter} 는 제네릭 인스턴스화가 불가해 enum 마다 구상 클래스가 있어야
 * 한다 — 별도 파일을 늘리지 않도록, 이 클래스는 각 enum 파일 안에 {@code Db} 라는 이름의 중첩
 * {@code @Converter} 정적 클래스로 상속해서 쓴다(예: {@code Role.Db}).
 *
 * @param <E> 변환 대상 enum 타입
 */
public abstract class LowerCaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> enumType;

    protected LowerCaseEnumConverter(Class<E> enumType) {
        this.enumType = enumType;
    }

    /** 상수를 소문자 snake_case 문자열로 바꾼다. {@code null} 은 그대로 통과시킨다. */
    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    /**
     * DB 값을 대문자로 올려 상수로 복원한다. {@code null} 은 그대로 통과시킨다.
     *
     * <p>enum 에 없는 값은 {@link IllegalArgumentException} 으로 실패시킨다 — 조용히 {@code null} 을
     * 돌려주면 CHECK 제약 밖의 값이 DB 에 들어갔을 때 애플리케이션이 정상으로 보인다.
     */
    @Override
    public E convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Enum.valueOf(enumType, dbData.toUpperCase());
    }
}
