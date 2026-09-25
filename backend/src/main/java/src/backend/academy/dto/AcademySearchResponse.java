package src.backend.academy.dto;

import java.util.List;

import src.backend.academy.entity.Academy;

/** 가입용 학원 검색 응답(API_SPEC §2.1). */
public record AcademySearchResponse(List<Item> items) {

    /** 검색 결과 학원 목록을 §2.1 응답 항목으로 옮긴다. */
    public static AcademySearchResponse from(List<Academy> academies) {
        return new AcademySearchResponse(academies.stream().map(Item::from).toList());
    }

    /** 선택 화면 표기는 {@code "{name} · {region} · {code}"}(API_SPEC §2.1) — 조립은 클라이언트 몫이다. */
    public record Item(String id, String name, String region, String code) {

        /** 학원 1건을 선택 화면 표기용 문자열 4개로 옮긴다 — {@code id}·{@code code} 는 문자열(Ruling 171). */
        public static Item from(Academy academy) {
            return new Item(String.valueOf(academy.getId()), academy.getName(), academy.getRegion(),
                    academy.getCode());
        }
    }
}
