package io.saiden.economyhelper.market.chart;

/**
 * 보낼 차트 하나 — <b>그림과 그 설명.</b>
 *
 * <p>둘이 한 몸인 이유는 <b>사진 홀로는 무엇의 그림인지 알 수 없기 때문</b>이다.
 * {@link ChartRenderer}가 글자를 안 그리므로(배포 컨테이너에 폰트가 없으면 두부가 된다)
 * 설명이 반드시 따라가야 한다. 한쪽만 들고 다니면 caption 없는 사진이 나가는 길이 생긴다.
 *
 * @param png     그림 바이트. 비어 있으면 {@code TelegramClient}가 아무것도 보내지 않는다
 * @param caption 무엇의 그림인가. 텍스트라서 <b>골든이 이것을 덮는다</b>
 */
public record ChartImage(byte[] png, String caption) {
}
