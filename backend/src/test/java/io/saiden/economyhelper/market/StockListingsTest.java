package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.kis.KisMasterClient.Listing;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 색인의 두 규칙을 고정한다 — <b>정확 일치가 먼저, 아니면 토큰 전부를 품는 후보 중 시가총액 1위.</b>
 * 아래 상장은 2026-08-28 마스터 파일의 실물이다(시가총액은 억).
 */
class StockListingsTest {

    private static final Listing SAMSUNG = new Listing("005930", "삼성전자", "ST", 15551101);
    private static final Listing SAMSUNG_PREFERRED = new Listing("005935", "삼성전자우", "ST", 1200000);
    private static final Listing NAVER = new Listing("035420", "NAVER", "ST", 400000);
    private static final Listing TIGER_NASDAQ = new Listing("133690", "TIGER 미국나스닥100", "EF", 115458);
    private static final Listing KODEX_NASDAQ = new Listing("379810", "KODEX 미국나스닥100", "EF", 93294);
    private static final Listing TIME_NASDAQ = new Listing("426030", "TIME 미국나스닥100액티브", "EF", 24944);
    private static final Listing TIME_NASDAQ_BOND =
            new Listing("0019K0", "TIME 미국나스닥100채권혼합50액티브", "EF", 5597);

    private static final StockListings LISTINGS = new StockListings(() -> List.of(
            SAMSUNG, SAMSUNG_PREFERRED, NAVER, TIGER_NASDAQ, KODEX_NASDAQ, TIME_NASDAQ, TIME_NASDAQ_BOND));

    @Test
    @DisplayName("정확 일치가 포함 일치를 이긴다 — '삼성전자'가 시총 큰 다른 후보에 밀리면 안 된다")
    void exactNameBeatsContainingNames() {
        assertThat(LISTINGS.find("삼성전자")).contains(SAMSUNG);
        assertThat(LISTINGS.find("TIME 미국나스닥100액티브")).contains(TIME_NASDAQ);
    }

    @Test
    @DisplayName("띄어쓰기·대소문자는 무시한다 — LLM이 상장명을 조금 다르게 적어도 걸린다")
    void ignoresSpacingAndCase() {
        assertThat(LISTINGS.find("time미국나스닥100액티브")).contains(TIME_NASDAQ);
        assertThat(LISTINGS.find("Time 미국 나스닥100 액티브")).contains(TIME_NASDAQ);
    }

    @Test
    @DisplayName("토큰 전부를 품는 후보 중 시총 1위 — 'TIME 나스닥100 액티브'는 채권혼합이 아니라 본 ETF다")
    void picksTheLargestAmongCandidatesContainingEveryToken() {
        assertThat(LISTINGS.find("TIME 나스닥100 액티브")).contains(TIME_NASDAQ);
        assertThat(LISTINGS.find("나스닥100")).as("브랜드 없이 물으면 시총 1위 TIGER").contains(TIGER_NASDAQ);
        assertThat(LISTINGS.find("삼성")).as("공공데이터포털 이름 검색과 같은 규칙 — 우선주가 아니라 본주").contains(SAMSUNG);
    }

    @Test
    @DisplayName("토큰 하나라도 없으면 후보가 아니다 — '타임나스닥100'은 LLM 없이는 빈손이다(타임 ≠ TIME)")
    void requiresEveryToken() {
        assertThat(LISTINGS.find("타임나스닥100")).isEmpty();
        assertThat(LISTINGS.find("네이버")).as("상장명이 NAVER라 한글로는 안 걸린다 — LLM의 코드가 메운다").isEmpty();
        assertThat(LISTINGS.find("")).isEmpty();
        assertThat(LISTINGS.find(null)).isEmpty();
    }

    @Test
    @DisplayName("코드는 대소문자를 가리지 않는다 — 정규화가 0019K0을 0019k0으로 내린다")
    void findsByCodeIgnoringCase() {
        assertThat(LISTINGS.byCode("0019k0")).contains(TIME_NASDAQ_BOND);
        assertThat(LISTINGS.byCode("999999")).isEmpty();
        assertThat(LISTINGS.byCode(null)).isEmpty();
    }

    @Test
    @DisplayName("코드와 이름이 같은 종목인가 — 삼성전자↔삼성전자는 맞고, KODEX 코드에 TIME 이름은 다른 종목이다")
    void tellsWhetherACodeAndANameAgree() {
        assertThat(StockListings.agrees(SAMSUNG, "삼성전자")).isTrue();
        assertThat(StockListings.agrees(TIME_NASDAQ, "TIME 나스닥100")).isTrue();
        assertThat(StockListings.agrees(KODEX_NASDAQ, "TIME 미국나스닥100액티브")).isFalse();
        assertThat(StockListings.agrees(NAVER, "네이버")).as("통칭은 상장명과 다르다 — 그때는 코드가 답한다").isFalse();
        assertThat(StockListings.agrees(SAMSUNG, "")).isFalse();
    }
}
