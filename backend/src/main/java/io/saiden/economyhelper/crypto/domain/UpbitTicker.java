package io.saiden.economyhelper.crypto.domain;

import io.saiden.economyhelper.shared.domain.PercentChange;
import io.saiden.economyhelper.shared.domain.Price;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 업비트 한 마켓의 시세 — 단위는 이미 우리 것이다(어댑터가 옮긴다).
 *
 * @param tradePrice       현재가 — 화면에 나가는 유일한 값. 없거나 0이면 {@code null}
 * @param accTradePrice24h 24시간 누적 거래대금. <b>화면용이 아니라</b> 동명 후보를 가르는 신호다.
 *                         {@code 비트}는 비트코인·비트코인캐시·비트텐서에 모두 걸리는데,
 *                         거래대금이 47배 차이라 이걸로 갈리면 LLM을 부를 필요가 없다
 * @param change           전일 종가 대비 등락률(%). 모르면 {@code null}
 * @param tradedAt         체결 시각. 응답에 없으면 {@code null}
 */
public record UpbitTicker(String market, Price tradePrice, BigDecimal accTradePrice24h,
                          PercentChange change, Instant tradedAt) {}
