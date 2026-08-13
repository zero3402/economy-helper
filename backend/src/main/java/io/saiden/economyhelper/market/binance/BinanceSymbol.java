package io.saiden.economyhelper.market.binance;

import java.util.Locale;
import java.util.Optional;

/**
 * 업비트 마켓 코드에서 바이낸스 심볼을 유도한다.
 *
 * <pre>
 * KRW-BTC  → BTCUSDT
 * KRW-ETH  → ETHUSDT
 * KRW-USDT → (없음)    ← USDTUSDT는 존재하지 않는다
 * </pre>
 *
 * <p><b>매핑 표를 두지 않는다.</b> 원화 마켓이 280개가 넘고 상장이 계속 바뀌는데 표를 손으로
 * 관리하면 반드시 낡는다. 기준 자산 코드는 두 거래소가 같은 티커를 쓰므로 유도로 충분하고,
 * 유도한 심볼이 바이낸스에 없으면 그 코인만 빠질 뿐이다.
 *
 * <p><b>USDT를 반드시 걸러야 한다.</b> 없는 심볼이 하나라도 섞이면 요청 <b>전체</b>가 400이라
 * (실측: {@code {"code":-1121,"msg":"Invalid symbol."}}) 브리핑의 코인 시세가 통째로 사라진다.
 * 브리핑 기본 설정에 {@code KRW-USDT}가 들어 있어 실제로 밟는 길이다.
 */
public final class BinanceSymbol {

    private static final String KRW_PREFIX = "KRW-";
    private static final String QUOTE = "USDT";

    private BinanceSymbol() {
    }

    /** @return 바이낸스 심볼. 유도할 수 없으면 {@link Optional#empty()} */
    public static Optional<String> of(String upbitMarket) {
        if (upbitMarket == null || !upbitMarket.startsWith(KRW_PREFIX)) {
            return Optional.empty();
        }
        String base = upbitMarket.substring(KRW_PREFIX.length()).toUpperCase(Locale.ROOT);
        if (base.isEmpty() || base.equals(QUOTE)) {
            return Optional.empty();
        }
        return Optional.of(base + QUOTE);
    }
}
