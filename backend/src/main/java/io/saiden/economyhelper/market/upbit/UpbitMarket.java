package io.saiden.economyhelper.market.upbit;

import io.saiden.economyhelper.text.QueryNormalizer;

/**
 * 업비트 원화 마켓 하나.
 *
 * <p>정규화한 이름 셋을 <b>미리 계산해 들고 있다.</b> 검색할 때마다 282건을 다시 정규화하면
 * 같은 일을 반복한다 — 종목 쪽(3,875건)에서 이 차이가 3.5배로 측정됐다.
 *
 * @param market  {@code KRW-BTC}
 * @param symbol  {@code btc} — 마켓 코드의 뒷부분. {@code /crypto btc}가 여기 걸린다
 */
public record UpbitMarket(String market, String koreanName,
                          String symbol, String normalizedKorean, String normalizedEnglish) {

    /**
     * 영문명은 <b>정규화한 것만 남긴다.</b> 원문은 화면에도 매칭에도 쓰지 않는데, 담아 두면
     * 282건이 6시간 캐시(Redis)에 그대로 실려 다닌다.
     */
    public static UpbitMarket of(String market, String koreanName, String englishName) {
        String symbol = market.substring(market.indexOf('-') + 1);
        return new UpbitMarket(market, koreanName,
                QueryNormalizer.normalize(symbol),
                QueryNormalizer.normalize(koreanName),
                QueryNormalizer.normalize(englishName));
    }

    /** 심볼·한글명·영문명 중 하나라도 정확히 같은가. */
    public boolean matchesExactly(String normalizedQuery) {
        return symbol.equals(normalizedQuery)
                || normalizedKorean.equals(normalizedQuery)
                || normalizedEnglish.equals(normalizedQuery);
    }

    public boolean startsWith(String normalizedQuery) {
        return normalizedKorean.startsWith(normalizedQuery)
                || normalizedEnglish.startsWith(normalizedQuery)
                || symbol.startsWith(normalizedQuery);
    }

    public boolean contains(String normalizedQuery) {
        return normalizedKorean.contains(normalizedQuery)
                || normalizedEnglish.contains(normalizedQuery);
    }
}
