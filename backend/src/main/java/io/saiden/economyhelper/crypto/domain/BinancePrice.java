package io.saiden.economyhelper.crypto.domain;

import io.saiden.economyhelper.shared.domain.PercentChange;
import io.saiden.economyhelper.shared.domain.Price;

/**
 * 바이낸스 한 심볼의 시세.
 *
 * @param symbol    {@code BTCUSDT}
 * @param lastPrice USDT 기준 현재가. 없거나 0이면 {@code null}
 * @param change    24시간 등락률(%). 모르면 {@code null}
 */
public record BinancePrice(String symbol, Price lastPrice, PercentChange change) {}
