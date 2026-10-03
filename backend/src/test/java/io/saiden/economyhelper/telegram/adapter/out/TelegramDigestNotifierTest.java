package io.saiden.economyhelper.telegram.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.digest.application.port.out.DigestMessage;
import io.saiden.economyhelper.digest.application.port.out.DigestNotifier.Delivery;
import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.fx.domain.FxSource;
import io.saiden.economyhelper.news.domain.NewsItem;
import io.saiden.economyhelper.shared.domain.DailyBar;
import io.saiden.economyhelper.shared.domain.Price;
import io.saiden.economyhelper.testsupport.RecordingTelegram;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelegramDigestNotifierTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final List<DailyBar> BARS = List.of(
            new DailyBar(LocalDate.of(2026, 8, 17), new BigDecimal("6801.20")),
            new DailyBar(LocalDate.of(2026, 8, 18), new BigDecimal("6869.83")));

    @Test
    @DisplayName("차트 한 장이 실패해도 남은 차트는 나가고 통은 성공이다 — 보충은 폴백이 아니다")
    void oneFailedChartDoesNotFailTheSection() {
        RecordingTelegram telegram = new RecordingTelegram() {
            @Override
            public void sendPhoto(byte[] png, String caption) {
                if (caption.contains("둘째")) {
                    throw new IllegalStateException("telegram 500");
                }
                super.sendPhoto(png, caption);
            }
        };
        DigestMessage message = new DigestMessage.Fx(
                new FxRate("USD", "KRW", new Price(new BigDecimal("1415")), FxSource.KEXIM, NOW));
        List<DigestMessage.Chart> charts = List.of(
                new DigestMessage.Chart("첫째", null, () -> BARS),
                new DigestMessage.Chart("둘째", null, () -> BARS),
                new DigestMessage.Chart("셋째", null, () -> BARS));

        Delivery delivery = new TelegramDigestNotifier(telegram).send(message, charts, () -> { });

        assertThat(delivery.delivered()).isTrue();
        assertThat(delivery.failure()).isNull();
        assertThat(telegram.captions).hasSize(2);
        assertThat(telegram.captions.get(0)).contains("첫째");
        assertThat(telegram.captions.get(1)).contains("셋째");
    }

    @Test
    @DisplayName("뉴스 한 통이 거절돼도 뒤의 기사는 나간다 — 실패는 결과에 남는다")
    void oneRejectedNewsItemDoesNotDropTheRest() {
        RecordingTelegram telegram = new RecordingTelegram("둘째 기사");
        DigestMessage message = new DigestMessage.News(List.of(
                news("첫째 기사"), news("둘째 기사"), news("셋째 기사")));
        int[] delivered = {0};

        Delivery delivery = new TelegramDigestNotifier(telegram).send(message, List.of(), () -> delivered[0]++);

        assertThat(telegram.sent).hasSize(2);
        assertThat(telegram.sent.get(1)).contains("셋째 기사");
        assertThat(delivered[0]).isEqualTo(2);
        assertThat(delivery.delivered()).isTrue();
        assertThat(delivery.failure()).as("나간 것과 빠진 것을 함께 알린다").isNotNull();
    }

    private static NewsItem news(String title) {
        return new NewsItem("CNBC", title, "본문", "https://example.com/" + title.hashCode(), NOW, true);
    }
}
