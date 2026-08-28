package io.saiden.economyhelper.market.kis;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.saiden.economyhelper.market.kis.KisMasterClient.Listing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 마스터 파일의 <b>형식은 실측으로 확정했다</b>(2026-08-28) — 공식 파이썬 예제의 꼬리 길이(228/222)와
 * <b>한 글자</b> 다르다. 여기 픽스처의 행은 실물 행을 같은 자리·같은 길이로 다시 만든 것이다.
 * 코드·이름·그룹·시가총액은 그날 파일의 값 그대로다.
 */
class KisMasterClientTest {

    private static final Charset CP949 = Charset.forName("MS949");

    /** 클래스당 하나다 — 테스트마다 띄우고 내리면 포트 재활용 창이 열린다(ARCHITECTURE.md §6). */
    private static WireMockServer server;
    private KisMasterClient client;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetAndBuild() {
        server.resetAll();
        client = new KisMasterClient(RestClient.builder(), server.baseUrl());
    }

    @Test
    @DisplayName("꼬리를 227/221로 잘라야 그룹코드와 시가총액이 제자리다 — 228/222면 한 칸 밀린다")
    void parsesBothMarketsAtTheMeasuredTailLengths() {
        stub(KisMasterClient.KOSPI_FILE, zip(
                row("005930", "삼성전자", "ST", 15551101, KisMasterClient.KOSPI_TAIL),
                row("426030", "TIME 미국나스닥100액티브", "EF", 24944, KisMasterClient.KOSPI_TAIL)));
        stub(KisMasterClient.KOSDAQ_FILE, zip(
                row("247540", "에코프로비엠", "ST", 115439, KisMasterClient.KOSDAQ_TAIL)));

        List<Listing> listings = client.listings();

        assertThat(listings).containsExactly(
                new Listing("005930", "삼성전자", "ST", 15551101),
                new Listing("426030", "TIME 미국나스닥100액티브", "EF", 24944),
                new Listing("247540", "에코프로비엠", "ST", 115439));
    }

    @Test
    @DisplayName("6자 단축코드만 남는다 — 수익증권(9자)·ETN(7자)은 KIS 국내 시세가 받지 않는 모양이다")
    void keepsOnlySixCharacterCodes() {
        stub(KisMasterClient.KOSPI_FILE, zip(
                row("F70100030", "한투한미핵심성장포커스1(A)", "BC", 0, KisMasterClient.KOSPI_TAIL),
                row("Q500093", "신한 블룸버그 레버리지 WTI원유선물 ETN", "EN", 0, KisMasterClient.KOSPI_TAIL),
                row("0019K0", "TIME 미국나스닥100채권혼합50액티브", "EF", 5597, KisMasterClient.KOSPI_TAIL)));
        stub(KisMasterClient.KOSDAQ_FILE, zip());

        assertThat(client.listings()).extracting(Listing::code)
                .as("영숫자 6자(0019K0)는 남고 9자·7자는 빠진다")
                .containsExactly("0019K0");
    }

    @Test
    @DisplayName("한글명을 cp949로 읽는다 — UTF-8로 읽으면 이름이 전부 깨진다")
    void decodesNamesAsCp949() {
        stub(KisMasterClient.KOSPI_FILE, zip(row("069500", "KODEX 200", "EF", 255103, KisMasterClient.KOSPI_TAIL)));
        stub(KisMasterClient.KOSDAQ_FILE, zip(row("900110", "딥커머스", "ST", 100, KisMasterClient.KOSDAQ_TAIL)));

        assertThat(client.listings()).extracting(Listing::name).containsExactly("KODEX 200", "딥커머스");
    }

    @Test
    @DisplayName("파일 주소 끝에 슬래시가 없다 — 308을 부르고 파싱이 죽는다(CoinDesk 피드 사고)")
    void requestsTheFilesWithoutATrailingSlash() {
        stub(KisMasterClient.KOSPI_FILE, zip(row("005930", "삼성전자", "ST", 1, KisMasterClient.KOSPI_TAIL)));
        stub(KisMasterClient.KOSDAQ_FILE, zip());

        client.listings();

        server.verify(getRequestedFor(urlEqualTo("/kospi_code.mst.zip")));
        server.verify(getRequestedFor(urlEqualTo("/kosdaq_code.mst.zip")));
    }

    @Test
    @DisplayName("한쪽 파일이 죽으면 던진다 — 반쪽 색인을 6시간 굳히는 것보다 다음 수(공공데이터포털)가 낫다")
    void throwsWhenAFileCannotBeDownloaded() {
        stub(KisMasterClient.KOSPI_FILE, zip(row("005930", "삼성전자", "ST", 1, KisMasterClient.KOSPI_TAIL)));
        server.stubFor(get(urlPathEqualTo(KisMasterClient.KOSDAQ_FILE)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(client::listings).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("리다이렉트 본문(15바이트)은 zip이 아니다 — 조용히 0건이 아니라 던진다")
    void throwsOnARedirectBody() {
        server.stubFor(get(urlPathEqualTo(KisMasterClient.KOSPI_FILE))
                .willReturn(aResponse().withStatus(200).withBody("Redirecting...")));
        server.stubFor(get(urlPathEqualTo(KisMasterClient.KOSDAQ_FILE))
                .willReturn(aResponse().withStatus(200).withBody("Redirecting...")));

        assertThatThrownBy(client::listings).isInstanceOf(RuntimeException.class);
    }

    // --- 픽스처 ---

    private static void stub(String file, byte[] body) {
        server.stubFor(get(urlPathEqualTo(file)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/zip").withBody(body)));
    }

    /**
     * 실물 행과 같은 자리 배치. 꼬리는 「그룹코드(2) · 채움 · 시가총액(9) · 그룹사코드(3) ·
     * 신용한도초과(1) · 담보대출(1) · 대주(1)」이고 길이가 시장별로 다르다.
     */
    private static String row(String code, String name, String group, long marketCap, int tailLength) {
        String tailEnd = String.format("%09d", marketCap) + "000NNN";
        String tail = group + " ".repeat(tailLength - group.length() - tailEnd.length()) + tailEnd;
        return pad(code, 9) + pad("KR7" + pad(code, 6) + "003", 12) + name + tail;
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value.substring(0, width) : value + " ".repeat(width - value.length());
    }

    private static byte[] zip(String... rows) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("code.mst"));
            zip.write(String.join("\n", rows).getBytes(CP949));
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
