package io.saiden.economyhelper.fx.domain;

import io.saiden.economyhelper.shared.domain.PercentChange;
import io.saiden.economyhelper.shared.domain.Price;
import java.time.Instant;

/**
 * 환율 한 건.
 *
 * <p>{@link FxSource}를 값에 담는 이유는 <b>메시지에서 출처를 밝혀야 하기 때문</b>이다.
 * 폴백이 일어났다는 사실을 사용자가 알 수 없으면, 주말에 며칠 전 값을 받고도 실시간으로 오해한다.
 *
 * @param rate 1 {@code base}당 {@code quote} 금액. USD/KRW면 "1달러 = 1,415원"
 * @param changePercent 전 고시 대비 등락률(%). <b>{@code null}일 수 있다</b> —
 *                      전 고시를 못 찾았다고 환율까지 막지는 않는다
 * @param asOf 기준 시각. 수출입은행은 고시일 00:00(KST)로 채운다 — 시각 정보가 없기 때문이다
 * @param live 지금 움직이는 값인가. 참이면 화면이 시각까지 적고, 거짓이면 날짜와 꼬리표만 적는다.
 *             <b>실시간 출처도 거짓일 수 있다</b> — 주말·휴일에 KIS는 마지막 영업일 종가를 현재가 자리에 준다.
 *             그 값에 읽은 시각을 찍으면 낡은 값을 신선하게 꾸미는 셈이다
 */
public record FxRate(String base, String quote, Price rate, PercentChange changePercent,
                     FxSource source, Instant asOf, boolean live) {

    /** 출처의 성격({@link FxSource#intraday()})대로 실시간 여부를 정한다 — 고시 출처는 늘 거짓이다. */
    public FxRate(String base, String quote, Price rate, PercentChange changePercent,
                  FxSource source, Instant asOf) {
        this(base, quote, rate, changePercent, source, asOf, source.intraday());
    }

    /** 등락률을 모르는 값. 출처가 전 고시를 주지 못할 때 쓴다. */
    public FxRate(String base, String quote, Price rate, FxSource source, Instant asOf) {
        this(base, quote, rate, null, source, asOf);
    }
}
