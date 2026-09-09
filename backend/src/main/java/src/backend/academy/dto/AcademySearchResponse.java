package src.backend.academy.dto;

import java.util.List;

import src.backend.academy.entity.Academy;

/** 가입용 학원 검색 응답(API_SPEC §2.1). */
public record AcademySearchResponse(List<Item> items) {

    public static AcademySearchResponse from(List<Academy> academies) {
        return new AcademySearchResponse(academies.stream().map(Item::from).toList());
    }

    /** 선택 화면 표기는 {@code "{name} · {region} · {code}"}(API_SPEC §2.1) — 조립은 클라이언트 몫이다. */
    public record Item(String id, String name, String region, String code) {

        public static Item from(Academy academy) {
            return new Item(String.valueOf(academy.getId()), academy.getName(), academy.getRegion(),
                    academy.getCode());
        }
    }
}
