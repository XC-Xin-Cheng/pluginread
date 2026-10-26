#!/bin/bash
# fetchseg.sh URL OUT — 串行下载 + HTTP 状态检查 + 429 退避重试
# （maven.fabricmc.net 有速率限制，并行/分段会触发 429，故完全串行）
set -u
URL="$1"; OUT="$2"
for try in 1 2 3 4 5 6 7 8 9 10; do
  CODE=$(curl -s --max-time 600 --connect-timeout 20 -w '%{http_code}' -o "$OUT.tmp" "$URL" 2>/dev/null)
  GOT=$(stat -c%s "$OUT.tmp" 2>/dev/null || echo 0)
  if [ "$CODE" = "200" ] && [ "$GOT" -gt 300 ]; then
    mv -f "$OUT.tmp" "$OUT"
    echo "OK $OUT ($GOT)"
    exit 0
  fi
  if [ "$CODE" = "404" ]; then
    echo "404（不存在）$URL"
    rm -f "$OUT.tmp"
    exit 2
  fi
  echo "  retry$try HTTP=$CODE size=$GOT $URL"
  rm -f "$OUT.tmp"
  sleep $((try * 6))
done
echo "FAIL $URL"
exit 1
