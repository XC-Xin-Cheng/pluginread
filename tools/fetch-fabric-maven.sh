#!/bin/bash
# 串行预取 fabricmc 构件到 local-fabric-maven/（该源站有速率限制，禁止并行）
set -u
ROOT="/c/Users/qhad/.zcode/workspace/draft-c180d6c3-4cd1-4de1-9d16-e986777e8ad4"
REPO="$ROOT/plugin-read/local-fabric-maven"
BASE="https://maven.fabricmc.net"
LOOM=1.19.3
LOADER=0.20.1
FAPI="0.145.1+26.1"
FAPI_ENC="0.145.1%2B26.1"

# 清理上次的分段残留与 404/429 错误页
find "$REPO" -name "*.seg*" -delete 2>/dev/null
while IFS= read -r f; do
  head -c 2 "$f" 2>/dev/null | grep -q "PK" && continue
  head -c 5 "$f" 2>/dev/null | grep -q "<?xml" && continue
  echo "清理无效文件: ${f#$REPO/} ($(stat -c%s "$f")B)"
  rm -f "$f"
done < <(find "$REPO" -type f \( -name "*.jar" -o -name "*.pom" \) 2>/dev/null)
# POM 截断检查（必须以 </project> 结尾）
while IFS= read -r p; do
  tail -c 40 "$p" 2>/dev/null | grep -q '</project>' || { echo "清理截断POM: ${p#$REPO/} ($(stat -c%s "$p")B)"; rm -f "$p"; }
done < <(find "$REPO" -type f -name "*.pom" 2>/dev/null)
# jar 完整性检查（坏 jar 删除，稍后重下）
if command -v unzip >/dev/null 2>&1; then
  while IFS= read -r j; do
    unzip -tqq "$j" >/dev/null 2>&1 || { echo "清理无效jar: ${j#$REPO/}"; rm -f "$j"; }
  done < <(find "$REPO" -type f -name "*.jar" 2>/dev/null)
fi

get() { # GROUP_PATH ARTIFACT VERSION EXT...
  local dir="$REPO/$1/$2/$3"
  mkdir -p "$dir"
  local base="$BASE/$1/$2/$3/$2-$3"
  for ext in "${@:4}"; do
    local out="$dir/$2-$3.$ext"
    if [ -s "$out" ]; then echo "SKIP ${1#net/fabricmc}/$2/$3.$ext"; continue; fi
    if bash "$ROOT/tools/fetchseg.sh" "$base.$ext" "$out"; then
      sleep 2
    else
      rm -f "$out" "$out.tmp"
      if [ "$ext" = "module" ]; then
        echo "  （module 不存在，跳过: $2）"
      else
        echo "FAIL ${1}/$2/$3.$ext"
        return 1
      fi
    fi
  done
  return 0
}

echo "=== loom ==="
get net/fabricmc fabric-loom "$LOOM" pom jar module || echo "loom 部分失败，继续"
get net/fabricmc/fabric-loom "net.fabricmc.fabric-loom.gradle.plugin" "$LOOM" pom module || true

echo "=== loader ==="
get net/fabricmc fabric-loader "$LOADER" pom jar || exit 1

echo "=== fabric-api 主构件 ==="
FAPI_DIR="$REPO/net/fabricmc/fabric-api/fabric-api/$FAPI"
mkdir -p "$FAPI_DIR"
if [ ! -s "$FAPI_DIR/fabric-api-$FAPI.pom" ]; then
  bash "$ROOT/tools/fetchseg.sh" "$BASE/net/fabricmc/fabric-api/fabric-api/$FAPI_ENC/fabric-api-$FAPI_ENC.pom" "$FAPI_DIR/fabric-api-$FAPI.pom" || exit 1
  sleep 2
fi
if [ ! -s "$FAPI_DIR/fabric-api-$FAPI.jar" ]; then
  bash "$ROOT/tools/fetchseg.sh" "$BASE/net/fabricmc/fabric-api/fabric-api/$FAPI_ENC/fabric-api-$FAPI_ENC.jar" "$FAPI_DIR/fabric-api-$FAPI.jar" || exit 1
  sleep 2
fi

echo "=== fabric-api Gradle 模块元数据（真实依赖列表）==="
FAMOD="$FAPI_DIR/fabric-api-$FAPI.module"
if [ ! -s "$FAMOD" ]; then
  bash "$ROOT/tools/fetchseg.sh" "$BASE/net/fabricmc/fabric-api/fabric-api/$FAPI_ENC/fabric-api-$FAPI_ENC.module" "$FAMOD" || echo "（module 拉取失败，退回 POM 列表）"
  sleep 2
fi
if [ -s "$FAMOD" ]; then
  grep -o '"module": *"fabric-[^"]*"' "$FAMOD" | sed 's/.*"module": *"//;s/"//' | sort -u | grep -v '^fabric-api$' > /tmp/fapi-modules.txt
else
  grep -o '<artifactId>[^<]*</artifactId>' "$FAPI_DIR/fabric-api-$FAPI.pom" \
    | sed 's/<[^>]*>//g' | sort -u | grep '^fabric-' | grep -v '^fabric-api$' > /tmp/fapi-modules.txt
fi
echo "子模块数: $(wc -l < /tmp/fapi-modules.txt)"
head -5 /tmp/fapi-modules.txt

FAILS=""
while read -r m; do
  get net/fabricmc/fabric-api "$m" "$FAPI" pom jar || FAILS="$FAILS $m"
done < /tmp/fapi-modules.txt

echo "=== 校验 jar 完整性 ==="
BAD=0
if command -v unzip >/dev/null 2>&1; then
  while IFS= read -r j; do
    if ! unzip -tqq "$j" >/dev/null 2>&1; then
      echo "无效 jar: ${j#$REPO/}"
      rm -f "$j"; BAD=$((BAD+1))
    fi
  done < <(find "$REPO" -name "*.jar" -type f)
else
  while IFS= read -r j; do
    [ "$(head -c 2 "$j")" = "PK" ] || { echo "无效 jar: ${j#$REPO/}"; rm -f "$j"; BAD=$((BAD+1)); }
  done < <(find "$REPO" -name "*.jar" -type f)
fi

TOTAL=$(find "$REPO" -type f | wc -l)
echo "=== 完成: 共 $TOTAL 个文件, 无效jar $BAD, 失败模块:${FAILS:-无} ==="
[ "$BAD" -eq 0 ] && [ -z "$FAILS" ]
