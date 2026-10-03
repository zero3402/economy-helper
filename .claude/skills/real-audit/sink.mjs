// 실물 감사용 텔레그램 싱크 — 아무 POST에 성공으로 답하고 보낸 글(text·caption)을 파일에 적는다.
// 쓰임: node sink.mjs <logfile> [port=19999]
// RestClient가 chunked로 보내므로 Content-Length를 믿지 않고 스트림 끝까지 읽는다.
import http from "node:http";
import fs from "node:fs";

const [, , log = "audit-sink.log", port = "19999"] = process.argv;

http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const raw = Buffer.concat(chunks);
    const method = req.url.split("/").pop();
    let body = "";
    try {
      const json = JSON.parse(raw.toString("utf8"));
      body = json.text ?? json.caption ?? "";
    } catch {
      // sendPhoto는 multipart다 — caption 파트만 꺼낸다
      const m = /name="caption"\r\n(?:[^\r\n]*\r\n)*?\r\n([\s\S]*?)\r\n--/.exec(raw.toString("utf8"));
      body = m ? m[1] : `(본문 ${raw.length}바이트, 글 없음)`;
    }
    fs.appendFileSync(log, `=== ${new Date().toISOString()} ${method}\n${body}\n\n`);
    res.writeHead(200, { "content-type": "application/json" });
    res.end('{"ok":true,"result":{"message_id":1}}');
  });
}).listen(Number(port), "127.0.0.1", () => console.log(`sink :${port} → ${log}`));
