#!/bin/bash
# 从 loom 的 .module 解析依赖（美化 JSON，group/module/version 各占一行），逐个补齐
set -u
ROOT="/c/Users/qhad/.zcode/workspace/draft-c180d6c3-4cd1-4de1-9d16-e986777e8ad4"
REPO="$ROOT/plugin-read/local-fabric-maven"
LOOMMOD="$REPO/net/fabricmc/fabric-loom/1.19.3/fabric-loom-1.19.3.module"

awk '
  /"group":/    { line=$0; sub(/.*"group": *"/,"",line); sub(/".*/,"",line); g=line }
  /"module":/   { line=$0; sub(/.*"module": *"/,"",line); sub(/".*/,"",line); a=line }
  /"requires":/ { line=$0; sub(/.*"requires": *"/,"",line); sub(/".*/,"",line); print g "|" a "|" line }
' "$LOOMMOD" > /tmp/loomdeps.txt
echo "依赖条目: $(wc -l < /tmp/loomdeps.txt)"

MISSING=""
while IFS='|' read -r g a v; do
  [ -z "${g:-}" ] || [ -z "${a:-}" ] || [ -z "${v:-}" ] && continue
  case "$a" in fabric-loom|\*) continue;; esac
  GP=$(echo "$g" | tr '.' '/')
  ALIYUN="https://maven.aliyun.com/repository/public/$GP/$a/$v/$a-$v.jar"
  if curl -sI --max-time 15 "$ALIYUN" | head -1 | grep -q " 200"; then
    echo "central: $a-$v"
    continue
  fi
  D="$REPO/$GP/$a/$v"
  mkdir -p "$D"
  if [ -s "$D/$a-$v.jar" ] && unzip -tqq "$D/$a-$v.jar" >/dev/null 2>&1; then
    echo "已有: $a-$v"
    continue
  fi
  echo "补齐: $g:$a:$v"
  curl -sL --max-time 120 -o "$D/$a-$v.jar" "https://bmclapi2.bangbang93.com/maven/$GP/$a/$v/$a-$v.jar" 2>/dev/null
  if [ -s "$D/$a-$v.jar" ] && head -c 2 "$D/$a-$v.jar" | grep -q PK; then
    echo "  BMCLAPI OK ($(stat -c%s "$D/$a-$v.jar")B)"
  else
    rm -f "$D/$a-$v.jar"
    echo "  BMCLAPI 无，涓流 fabricmc..."
    bash "$ROOT/tools/fetchseg.sh" "https://maven.fabricmc.net/$GP/$a/$v/$a-$v.jar" "$D/$a-$v.jar" || MISSING="$MISSING $g:$a:$v"
  fi
  if [ ! -s "$D/$a-$v.pom" ]; then
    curl -s --max-time 60 -o "$D/$a-$v.pom" "https://bmclapi2.bangbang93.com/maven/$GP/$a/$v/$a-$v.pom" 2>/dev/null
    if ! head -c 5 "$D/$a-$v.pom" 2>/dev/null | grep -q "<?xml"; then
      rm -f "$D/$a-$v.pom"
      bash "$ROOT/tools/fetchseg.sh" "https://maven.fabricmc.net/$GP/$a/$v/$a-$v.pom" "$D/$a-$v.pom" || true
    fi
  fi
  sleep 1
done < /tmp/loomdeps.txt
echo "=== 缺失: ${MISSING:-无} ==="
echo "=== 本地仓库文件数: $(find "$REPO" -type f | wc -l) ==="
