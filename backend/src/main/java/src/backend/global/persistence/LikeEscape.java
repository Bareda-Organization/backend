package src.backend.global.persistence;

/** 사용자 입력을 LIKE 패턴에 넣기 전에 와일드카드를 리터럴로 되돌린다. */
public final class LikeEscape {

    private LikeEscape() {
    }

    /**
     * {@code %}·{@code _} 를 이스케이프한다 — 각각 "0개 이상"·"1개" 와일드카드라 사용자 입력에 그대로
     * 들어가면 {@code q="%"} 하나가 전체 매칭이 된다. 호출부의 쿼리는 {@code ESCAPE '\'} 를 함께 선언해야
     * 이 이스케이프가 해석된다.
     *
     * <p>{@code \} 부터 먼저 이스케이프해야 뒤이어 붙이는 {@code \%}·{@code \_} 가 다시 이스케이프되지 않는다.
     *
     * <p>한 곳에 모아 둔 이유는 복제되면 한쪽만 고쳐지기 때문이다 — 검색 엔드포인트가 늘 때마다
     * 이 세 줄을 옮겨 적으면, 다음에 회피 형태를 하나 더 막을 때 옮겨 적은 쪽이 남는다.
     */
    public static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
