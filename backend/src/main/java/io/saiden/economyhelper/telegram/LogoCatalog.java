package io.saiden.economyhelper.telegram;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 시세 답에 붙일 아이콘.
 *
 * <p><b>왜 서버에 두는가.</b> 외부 이미지 URL을 쓰면 그 호스트가 죽는 순간 시세 답이 함께
 * 느려지고, 텔레그램이 그 URL을 대신 받아오므로 우리 타임아웃·서킷브레이커가 닿지 않는다.
 * 저장소에 넣으면 외부 호출이 <b>한 번도</b> 없다.
 *
 * <p><b>왜 전부가 아니라 열 개인가.</b> 업비트 원화 마켓만 283개고 상장이 계속 바뀐다.
 * 전부 채우면 손으로 쫓아다녀야 하는 목록이 하나 더 생긴다. 시총 상위 열 개가 조회의
 * 대부분이고, 나머지는 공용 아이콘으로 충분하다 — 아이콘은 정보가 아니라 시선의 표식이다.
 *
 * <p><b>주식·지수는 공용 아이콘만 쓴다.</b> 기업 로고는 상표라 저장소에 담을 수 없다.
 * 상표권 없는 대체물을 억지로 만들면 그건 그 회사가 아니게 되므로 아예 두지 않는다.
 *
 * <p>파일은 {@code cryptocurrency-icons}(CC0 1.0, 퍼블릭 도메인)에서 가져왔다 —
 * 저작권 표시 의무가 없고 상표를 주장하지 않는 재도안 아이콘이다.
 */
@Component
public class LogoCatalog {

    private static final Logger log = LoggerFactory.getLogger(LogoCatalog.class);

    /** 어느 심볼에도 걸리지 않을 때. */
    static final String FALLBACK = "generic";

    /** 60KB 남짓이라 통째로 들고 있어도 된다. 파일을 매번 여는 것보다 단순하다. */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /**
     * @param symbol 코인 티커({@code BTC}) 또는 종목코드. {@code null}이어도 된다
     * @return 붙일 아이콘. 파일이 없으면 공용 아이콘이고, 그것마저 없으면 비어 있다
     */
    public java.util.Optional<Logo> find(String symbol) {
        String name = symbol == null ? FALLBACK : symbol.trim().toLowerCase(Locale.ROOT);
        byte[] bytes = read(name);
        if (bytes == null) {
            bytes = read(FALLBACK);
            name = FALLBACK;
        }
        return bytes == null ? java.util.Optional.empty() : java.util.Optional.of(new Logo(name, bytes));
    }

    private byte[] read(String name) {
        byte[] cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("logo/" + name + ".png")) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readAllBytes();
            cache.put(name, bytes);
            return bytes;
        } catch (IOException e) {
            // 아이콘이 없다고 시세를 막지 않는다. 이건 장식이다
            log.warn("[logo] {} 읽기 실패: {}", name, e.toString());
            return null;
        }
    }

    /** @param name 텔레그램에 넘길 파일 이름의 뿌리 */
    public record Logo(String name, byte[] bytes) {

        public String fileName() {
            return name + ".png";
        }
    }
}
