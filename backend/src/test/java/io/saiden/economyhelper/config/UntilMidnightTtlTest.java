package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 전망 캐시는 <b>그 시장의 자정을 넘기지 않는다</b> — 담긴 값이 「오늘」로 이미 잘려 있어서다
 * ({@code Dividend.nextOf}·실적발표일). 12시간을 그대로 두면 기준일 당일 밤에 담긴 값이 다음 날 아침
 * 브리핑에 「지난」 없이 나간다.
 */
class UntilMidnightTtlTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    @DisplayName("기준일 밤 22시(KST)에 담으면 두 시간만 산다 — 다음 날 09시 브리핑은 다시 고른다")
    void endsAtTheMarketsMidnight() {
        Clock at = Clock.fixed(Instant.parse("2026-09-30T13:00:00Z"), ZoneOffset.UTC); // KST 22:00

        assertThat(new UntilMidnightTtl(Duration.ofHours(12), SEOUL, at).getTimeToLive("005930", null))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("자정이 멀면 원래 수명이다 — 한도를 지키는 12시간은 그대로다")
    void keepsTheCapWhenMidnightIsFar() {
        Clock at = Clock.fixed(Instant.parse("2026-09-30T01:00:00Z"), ZoneOffset.UTC); // KST 10:00

        assertThat(new UntilMidnightTtl(Duration.ofHours(12), SEOUL, at).getTimeToLive("005930", null))
                .isEqualTo(Duration.ofHours(12));
    }

    @Test
    @DisplayName("미국은 뉴욕 자정이다 — KST로 자르면 오전 9시 브리핑이 어제(현지)를 오늘로 쓴다")
    void usesTheMarketsOwnCalendar() {
        Clock at = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC); // EDT 20:00

        assertThat(new UntilMidnightTtl(Duration.ofHours(12), NEW_YORK, at).getTimeToLive("AAPL", null))
                .isEqualTo(Duration.ofHours(4));
    }

    @Test
    @DisplayName("자정 직전에도 0이 아니다 — 1ms 미만이면 PX 0이 되어 담기 자체가 실패한다")
    void neverRoundsDownToZero() {
        Clock at = Clock.fixed(Instant.parse("2026-09-30T14:59:59.999900Z"), ZoneOffset.UTC); // KST 23:59:59.9999

        assertThat(new UntilMidnightTtl(Duration.ofHours(12), SEOUL, at).getTimeToLive("005930", null))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }
}
