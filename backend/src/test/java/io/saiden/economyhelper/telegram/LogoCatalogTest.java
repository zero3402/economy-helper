package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 아이콘을 못 찾는 것은 <b>조용한 실패</b>다 — 답은 그대로 나가고 그림만 공용으로 떨어져서,
 * 실기기에서 눈으로 보기 전에는 아무도 모른다. 그래서 키 대응을 여기서 못 박는다.
 */
class LogoCatalogTest {

    private final LogoCatalog catalog = new LogoCatalog();

    @Test
    @DisplayName("코인 티커·종목코드·미국 티커가 각각 제 파일로 간다")
    void findsByIdentifier() {
        assertThat(catalog.find("BTC")).get().extracting(LogoCatalog.Logo::name).isEqualTo("btc");
        assertThat(catalog.find("005930")).get().extracting(LogoCatalog.Logo::name)
                .as("국내 종목은 종목코드가 곧 키다").isEqualTo("005930");
        assertThat(catalog.find("nvda")).get().extracting(LogoCatalog.Logo::name).isEqualTo("nvda");
    }

    @Test
    @DisplayName("지수는 미국이 ^IXIC, 국내가 이름 — 둘 다 파일명으로 옮겨진다")
    void findsIndices() {
        assertThat(catalog.find("^IXIC")).get().extracting(LogoCatalog.Logo::name)
                .as("파일명에 ^를 두지 않는다").isEqualTo("ixic");
        assertThat(catalog.find("코스피")).get().extracting(LogoCatalog.Logo::name)
                .as("국내 지수는 종목코드가 없어 이름이 유일한 식별자다").isEqualTo("kospi");
    }

    @Test
    @DisplayName("목록에 없으면 공용 아이콘 — 283개를 다 채우면 쫓아다녀야 할 목록이 하나 는다")
    void fallsBackToGenericIcon() {
        assertThat(catalog.find("없는코인zzz")).get().extracting(LogoCatalog.Logo::name)
                .isEqualTo(LogoCatalog.FALLBACK);
        assertThat(catalog.find(null)).get().extracting(LogoCatalog.Logo::name)
                .isEqualTo(LogoCatalog.FALLBACK);
    }

    @Test
    @DisplayName("파일 이름은 png다 — 텔레그램이 확장자로 형식을 읽는다")
    void namesFileWithExtension() {
        assertThat(catalog.find("BTC")).get().extracting(LogoCatalog.Logo::fileName)
                .isEqualTo("btc.png");
        assertThat(catalog.find("BTC")).get()
                .satisfies(logo -> assertThat(logo.bytes()).isNotEmpty());
    }
}
