package io.saiden.economyhelper.stock.adapter.in.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.stock.application.StockListings;
import io.saiden.economyhelper.stock.domain.Listing;
import io.saiden.economyhelper.testsupport.TestProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ListingsWarmupTest {

    @Test
    @DisplayName("데우면 마스터를 한 번 읽는다 — 첫 /stock이 그 3초를 기다리지 않는다")
    void readsTheMasterOnce() {
        AtomicInteger reads = new AtomicInteger();
        StockListings listings = new StockListings(() -> {
            reads.incrementAndGet();
            return List.of(new Listing("005930", "삼성전자", "ST", 1));
        });

        new ListingsWarmup(listings, enabled(true)).preload();

        assertThat(reads).hasValue(1);
    }

    @Test
    @DisplayName("못 읽어도 기동을 막지 않는다 — 데우기는 보충이다")
    void swallowsAFailure() {
        StockListings dead = new StockListings(() -> {
            throw new IllegalStateException("마스터가 비었다");
        });

        assertThatCode(() -> new ListingsWarmup(dead, enabled(true)).preload()).doesNotThrowAnyException();
    }

    private static EconomyHelperProperties enabled(boolean on) {
        return TestProperties.builder().warmup(on).build();
    }
}
