package io.saiden.economyhelper.market.binance;

import java.util.Locale;
import java.util.Optional;

/**
 * 업비트 마켓 코드에서 바이낸스 심볼을 유도한다.
 *
 * <pre>
 * KRW-BTC  → BTCUSDT
 * KRW-ETH  → ETHUSDT
 * KRW-USDT → USDTUSD    ← 테더만 호가가 USD다
 * </pre>
 *
 * <p><b>매핑 표를 두지 않는다.</b> 원화 마켓이 280개가 넘고 상장이 계속 바뀌는데 표를 손으로
 * 관리하면 반드시 낡는다. 기준 자산 코드는 두 거래소가 같은 티커를 쓰므로 유도로 충분하고,
 * 유도한 심볼이 바이낸스에 없으면 그 코인만 빠질 뿐이다.
 *
 * <p><b>테더는 {@code USDTUSDT}가 아니라 {@code USDTUSD}다.</b> 없는 심볼이 하나라도 섞이면
 * 요청 <b>전체</b>가 400이라(실측: {@code USDTUSDT} → {@code {"code":-1121,"msg":"Invalid
 * symbol."}}) 예전에는 테더를 통째로 걸러 냈는데, 그러면 화면에 <b>미상장</b>이 찍힌다.
 * 실제로는 상장돼 있다 — 2026-08-15 실측으로 {@code USDTUSD}는 200이고
 * {@code {"symbol":"USDTUSD","price":"0.99906000"}}을 준다. 없는 것이 아니라 우리가 안 물었던
 * 것이고, 브리핑 기본 설정에 {@code KRW-USDT}가 들어 있어 매일 밟던 길이다.
 *
 * <p>그래서 <b>호가 자산도 여기서 알려 준다</b>({@link #quoteAssetOf}). 화면에 찍을 단위가
 * 테더만 {@code USD}로 갈리는데, 그 판단이 심볼 유도와 다른 곳에 있으면 언젠가 서로 어긋나
 * {@code 0.99906 USDT} 같은 거짓말이 나간다.
 */
public final class BinanceSymbol {

    private static final String KRW_PREFIX = "KRW-";

    /** 대부분의 코인이 이걸로 호가된다. */
    private static final String USDT = "USDT";

    /** 테더 자신의 호가 자산. {@code USDTUSDT}는 없고 {@code USDTUSD}가 있다. */
    private static final String USD = "USD";

    private BinanceSymbol() {
    }

    /** @return 바이낸스 심볼. 유도할 수 없으면 {@link Optional#empty()} */
    public static Optional<String> of(String upbitMarket) {
        return base(upbitMarket).map(base -> base + quoteAssetOf(base));
    }

    /**
     * 티커 하나의 값이 <b>어느 통화로 매겨졌는지</b> — 화면에 찍을 단위다.
     *
     * <p>원화 환산은 어느 쪽이든 환율(USD/KRW)을 곱한다. 보통 코인은 1 USDT를 1 USD로 보는
     * 김치 프리미엄의 통용 정의를 따르는 것이고, 테더는 애초에 USD 호가라 그대로 맞다.
     *
     * @param ticker 기준 자산 코드({@code BTC}·{@code USDT}) — 마켓 코드가 아니다.
     *               업비트 미상장 코인은 마켓 코드 자체가 없어 티커로만 물을 수 있다
     */
    public static String quoteAssetOf(String ticker) {
        return USDT.equalsIgnoreCase(ticker) ? USD : USDT;
    }

    /** @return 기준 자산 코드({@code BTC}). 원화 마켓이 아니면 {@link Optional#empty()} */
    private static Optional<String> base(String upbitMarket) {
        if (upbitMarket == null || !upbitMarket.startsWith(KRW_PREFIX)) {
            return Optional.empty();
        }
        String base = upbitMarket.substring(KRW_PREFIX.length()).toUpperCase(Locale.ROOT);
        return base.isEmpty() ? Optional.empty() : Optional.of(base);
    }
}
