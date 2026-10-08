package io.saiden.economyhelper.telegram.adapter.out;

import io.saiden.economyhelper.digest.application.port.out.DigestMessage;
import io.saiden.economyhelper.digest.application.port.out.DigestNotifier;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.telegram.presentation.ChartImage;
import io.saiden.economyhelper.telegram.presentation.Charts;
import io.saiden.economyhelper.telegram.presentation.CryptoFormatter;
import io.saiden.economyhelper.telegram.presentation.FxFormatter;
import io.saiden.economyhelper.telegram.presentation.NewsFormatter;
import io.saiden.economyhelper.telegram.presentation.StockFormatter;
import io.saiden.economyhelper.telegram.presentation.WeatherFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 정기 발송(아침 브리핑·날씨 알람)을 텔레그램 통으로 적어 보낸다.
 *
 * <p>같은 방에 초당 한 통은 {@link TelegramClient}가 방마다 지킨다 — 여기서는 순서만 정한다.
 */
@Component
public class TelegramDigestNotifier implements DigestNotifier {

    /** 차트 로그 앞머리 — 검색 경로({@code webhook})와 어느 쪽에서 빠졌는지 로그가 가른다. */
    private static final String CHART_TAG = "digest";

    private static final Logger log = LoggerFactory.getLogger(TelegramDigestNotifier.class);

    private final TelegramClient telegram;

    public TelegramDigestNotifier(TelegramClient telegram) {
        this.telegram = telegram;
    }

    @Override
    public Delivery send(DigestMessage message, List<DigestMessage.Chart> charts, Runnable onDelivered) {
        boolean delivered = false;
        RuntimeException failure = null;
        try {
            // 뉴스 통만 미리보기를 켠다 — 링크가 있는 통이 여기뿐이다
            boolean preview = message instanceof DigestMessage.News;
            // ⚠️ 글 하나가 실패해도 다음 글로 간다 — 뉴스 셋째 기사가 거절됐다고 넷째·다섯째까지 버리지 않는다.
            //    실패는 첫 것만 결과에 남긴다(잡이 그 이름으로 실패를 기록한다)
            for (String text : textsOf(message)) {
                try {
                    telegram.send(text, preview);
                } catch (RuntimeException e) {
                    failure = failure == null ? e : failure;
                    continue;
                }
                delivered = true;
                onDelivered.run();
            }
            // ⚠️ 글이 하나도 안 나갔으면 사진도 보내지 않는다 — 무엇의 그림인지 모르고, 잡이 슬롯을 풀어
            //    다음 틱에 통째로 다시 보내면 사진만 두 번 간다
            if (!delivered) {
                return new Delivery(false, failure);
            }
            // 사진은 글 다음에 종목마다 한 장씩. 못 그린 것은 그 한 장만 빠진다 — 보충이지 폴백이 아니다
            for (DigestMessage.Chart chart : charts) {
                Charts.of(CHART_TAG, chart.subject(), chart.unit(), chart.bars())
                        .ifPresent(image -> sendChartQuietly(chart.subject(), image));
            }
            return new Delivery(delivered, failure);
        } catch (RuntimeException e) {
            return new Delivery(delivered, failure == null ? e : failure);
        }
    }

    /**
     * 차트 한 장 — <b>실패해도 그 장만 빠진다.</b> 글은 이미 나갔으므로 여기서 던지면 남은 차트가
     * 사라지고 통이 실패로 기록된다. 검색 경로의 {@code TelegramWebhookController.sendChartQuietly}와 같은 자리다.
     */
    private void sendChartQuietly(String subject, ChartImage image) {
        try {
            telegram.sendPhoto(image.png(), image.caption());
        } catch (RuntimeException e) {
            log.warn("[{}] {} 차트 발송 실패 — 글은 이미 나갔습니다: {}", CHART_TAG, subject, FailureReason.of(e));
        }
    }

    /**
     * 통의 본문들. 뉴스만 여러 통이다 — 텔레그램이 미리보기 카드를 메시지 맨 아래에 하나만 붙여,
     * 여러 건을 묶으면 첫 기사 카드가 마지막 기사 것처럼 보인다.
     */
    private static List<String> textsOf(DigestMessage message) {
        return switch (message) {
            case DigestMessage.Fx fx -> List.of(FxFormatter.format(fx.rate()));
            case DigestMessage.Stocks stocks ->
                    List.of(StockFormatter.format(stocks.quotes(), stocks.fx(), stocks.outlooks()));
            case DigestMessage.Coins coins -> List.of(CryptoFormatter.format(coins.quotes(), coins.fx()));
            case DigestMessage.News news -> NewsFormatter.formatAll(news.items());
            // 알람은 물어본 사람이 없다 — 제목에 검색어를 적지 않는다
            case DigestMessage.WeatherAlarm weather -> List.of(WeatherFormatter.format(weather.places()));
        };
    }
}
