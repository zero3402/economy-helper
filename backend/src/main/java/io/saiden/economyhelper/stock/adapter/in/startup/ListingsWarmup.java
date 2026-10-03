package io.saiden.economyhelper.stock.adapter.in.startup;

import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.stock.application.StockListings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동하면 종목 색인을 한 번 데운다 — 잠들었다 깬 뒤 첫 {@code /stock}이 마스터 두 파일을 기다리지 않게.
 *
 * <p>가상 스레드에서 돌고 실패는 삼킨다 — 색인은 조회 때 다시 읽으므로 데우기는 보충이다.
 * 테스트는 {@code economy-helper.warmup.enabled=false}로 끈다(실제 파일 호스트를 부르지 않게).
 */
@Component
public class ListingsWarmup {

    private static final Logger log = LoggerFactory.getLogger(ListingsWarmup.class);

    private final StockListings listings;
    private final boolean enabled;

    public ListingsWarmup(StockListings listings,
                          @Value("${economy-helper.warmup.enabled:true}") boolean enabled) {
        this.listings = listings;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        if (enabled) {
            Thread.ofVirtual().name("listings-warmup").start(this::preload);
        }
    }

    void preload() {
        try {
            listings.preload();
        } catch (RuntimeException e) {
            log.warn("[stock] 종목 색인을 미리 못 읽었습니다 — 첫 조회가 읽습니다: {}", FailureReason.of(e));
        }
    }
}
