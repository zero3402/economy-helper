package io.saiden.economyhelper.stock.domain;

/**
 * LLM이 검색어에서 짚어 낸 종목 — 실재는 시세 출처와 색인이 확정한다.
 *
 * @param market {@code "US"}면 미국 경로, 그 외(기본 KR)는 국내 경로. <b>이중화 사슬이 갈리는 축</b>이다
 * @param kind {@code "INDEX"}면 지수, 그 외는 개별 종목. 조회할 API가 갈린다
 * @param code 종목코드·티커. 국내 지수이거나 LLM이 확신하지 못하면 {@code null}일 수 있다
 * @param name 정식 종목명·지수명. code가 빗나갔을 때의 2차 단서이자 국내 지수의 유일한 단서다
 */
public record ResolvedStock(String market, String kind, String code, String name) {

    private static final String INDEX = "INDEX";
    private static final String US = "US";

    /** 지수는 종목코드가 없어 이름으로만 찾는다 — 조회 경로가 통째로 다르다. */
    public boolean isIndex() {
        return INDEX.equalsIgnoreCase(kind);
    }

    /**
     * 미국 종목·지수인가.
     *
     * <p>비어 있으면 국내로 본다 — 이 봇은 한국어로 쓰이고 국내가 기본이다.
     * LLM이 market을 빠뜨려도 기존 국내 경로로 안전하게 떨어진다.
     */
    public boolean isUs() {
        return US.equalsIgnoreCase(market);
    }

    public boolean hasCode() {
        return !blank(code);
    }

    public boolean hasName() {
        return !blank(name);
    }

    /**
     * ⚠️ LLM은 빈 값을 {@code "null"} 문자열로도 준다 — 그것도 비었다고 본다.
     * {@code LlmJson.blank}와 같은 판단이다(도메인이 인프라를 모르므로 여기 둔다).
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
    }
}
