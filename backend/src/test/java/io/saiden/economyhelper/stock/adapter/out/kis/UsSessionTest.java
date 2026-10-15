package io.saiden.economyhelper.stock.adapter.out.kis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 미국 종목 시세에는 날짜가 없어 이 달력이 「실시간인가 종가인가」를 가른다 — 틀리면 주말에 금요일
 * 종가가 실시간으로, 또는 장중 값이 종가로 나간다.
 */
class UsSessionTest {

    @Test
    @DisplayName("정규장은 월~금 09:30~16:00(뉴욕)이다 — 09:30은 열렸고 16:00은 닫혔다")
    void opensAtNineThirtyAndClosesAtFour() {
        assertThat(UsSession.isOpen(newYork("2026-08-19T09:29"))).isFalse();
        assertThat(UsSession.isOpen(newYork("2026-08-19T09:30"))).isTrue();
        assertThat(UsSession.isOpen(newYork("2026-08-19T15:59"))).isTrue();
        assertThat(UsSession.isOpen(newYork("2026-08-19T16:00"))).isFalse();
        assertThat(UsSession.isOpen(newYork("2026-08-22T12:00"))).as("토요일 정오").isFalse();
        assertThat(UsSession.isOpen(newYork("2026-08-23T12:00"))).as("일요일 정오").isFalse();
    }

    @Test
    @DisplayName("서머타임이 끝나도 뉴욕 시각으로 센다 — 11월의 09:30은 UTC 14:30이다")
    void followsNewYorkDaylightSaving() {
        // 2026-11-01에 서머타임이 끝난다. 8월의 09:30 EDT는 13:30Z였다
        assertThat(UsSession.isOpen(Instant.parse("2026-11-02T14:30:00Z"))).isTrue();
        assertThat(UsSession.isOpen(Instant.parse("2026-11-02T14:29:00Z"))).isFalse();
        assertThat(UsSession.isOpen(Instant.parse("2026-08-19T13:30:00Z"))).isTrue();
    }

    private static Instant newYork(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(UsSession.NEW_YORK).toInstant();
    }
}
