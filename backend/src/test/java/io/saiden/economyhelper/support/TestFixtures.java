package io.saiden.economyhelper.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 클래스패스 픽스처를 문자열로 읽는다.
 *
 * <p>같은 여섯 줄이 네 테스트에 따로 있었고({@code FeedFetcherTest}·
 * {@code GoogleNewsFeedClientTest}·{@code RssFeedClientTest}·{@code KmaWeatherClientTest})
 * <b>넷 중 하나는 선행 슬래시가 없어 모양도 혼자 달랐다</b> — 그런 차이가 「왜 여기만 못
 * 읽나」를 만든다.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /**
     * @param name {@code src/test/resources} 기준 경로. 선행 슬래시는 있어도 없어도 된다
     * @throws AssertionError 그런 픽스처가 없을 때 — 조용히 {@code null}을 흘리지 않는다
     */
    public static String text(String name) {
        try (InputStream stream = stream(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 스트림으로 읽는 파서(SAX·XML)가 쓴다 — 닫는 것은 호출부 몫이다. */
    public static Reader reader(String name) {
        return new InputStreamReader(stream(name), StandardCharsets.UTF_8);
    }

    private static InputStream stream(String name) {
        String path = name.startsWith("/") ? name : "/" + name;
        InputStream stream = TestFixtures.class.getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("픽스처를 찾지 못했습니다: " + path);
        }
        return stream;
    }
}
