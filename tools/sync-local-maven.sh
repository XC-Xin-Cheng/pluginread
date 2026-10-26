#!/bin/bash
# 总装本地 maven 仓库：
# 1) BMCLAPI 拉 loader jar
# 2) Modrinth fabric-api jar 提取全部嵌套模块 jar + 生成极简 POM + 生成 GMM .module
# 3) loom 依赖中 fabricmc 独有的构件：阿里云 → BMCLAPI → fabricmc 涓流
set -u
ROOT="/c/Users/qhad/.zcode/workspace/draft-c180d6c3-4cd1-4de1-9d16-e986777e8ad4"
REPO="$ROOT/plugin-read/local-fabric-maven"
FAPI_VER="0.144.0+26.1"
FAPI_ENC="0.144.0%2B26.1"

pom_template() { # ART VER
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>net.fabricmc.fabric-api</groupId>
  <artifactId>$1</artifactId>
  <version>$2</version>
  <packaging>jar</packaging>
</project>
EOF
}

echo "=== 1) loader jar (BMCLAPI) ==="
LDIR="$REPO/net/fabricmc/fabric-loader/0.20.1"
mkdir -p "$LDIR"
if ! unzip -tqq "$LDIR/fabric-loader-0.20.1.jar" >/dev/null 2>&1; then
  cp /tmp/loader-bmcl.jar "$LDIR/fabric-loader-0.20.1.jar" 2>/dev/null || curl -sL --max-time 60 -o "$LDIR/fabric-loader-0.20.1.jar" "https://bmclapi2.bangbang93.com/maven/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar"
  echo "loader jar 就位: $(stat -c%s "$LDIR/fabric-loader-0.20.1.jar")B"
else
  echo "loader jar 已有效"
fi

echo "=== 2) fabric-api (Modrinth 提取) ==="
FAPI_DIR="$REPO/net/fabricmc/fabric-api/fabric-api/$FAPI_VER"
mkdir -p "$FAPI_DIR" /tmp/fapi-extract
rm -rf /tmp/fapi-extract/*
cd /tmp/fapi-extract
unzip -qq /tmp/fapi-modrinth.jar "META-INF/jars/*.jar" 2>/dev/null
cp /tmp/fapi-modrinth.jar "$FAPI_DIR/fabric-api-$FAPI_VER.jar"
pom_template fabric-api "$FAPI_VER" > "$FAPI_DIR/fabric-api-$FAPI_VER.pom"

# 生成 GMM .module（api + runtime 两个变体，依赖 = 全部嵌套模块）
DEPS_API=""
DEPS_RT=""
N=0
for j in META-INF/jars/*.jar; do
  base=$(basename "$j" .jar)
  ver="${base##*-}"
  art="${base%-*}"
  [ "$art" = "fabric-api" ] && continue
  # 落位模块 jar
  MD="$REPO/net/fabricmc/fabric-api/$art/$ver"
  mkdir -p "$MD"
  cp "$j" "$MD/$art-$ver.jar"
  pom_template "$art" "$ver" > "$MD/$art-$ver.pom"
  DEPS_API="$DEPS_API{\"group\":\"net.fabricmc.fabric-api\",\"module\":\"$art\",\"version\":{\"requires\":\"$ver\"}},"
  N=$((N+1))
done
DEPS_RT="$DEPS_API"
cat > "$FAPI_DIR/fabric-api-$FAPI_VER.module" <<EOF
{
  "formatVersion": 1,
  "variants": [
    {
      "name": "api",
      "attributes": {"org.gradle.category": "library", "org.gradle.usage": "java-api", "org.gradle.libraryelements": "jar"},
      "dependencies": [${DEPS_API%,}]
    },
    {
      "name": "runtime",
      "attributes": {"org.gradle.category": "library", "org.gradle.usage": "java-runtime", "org.gradle.libraryelements": "jar"},
      "dependencies": [${DEPS_RT%,}]
    }
  ]
}
EOF
echo "提取模块数: $N"

echo "=== 3) loom 依赖补齐 ==="
LOOMMOD="$REPO/net/fabricmc/fabric-loom/1.19.3/fabric-loom-1.19.3.module"
tr '{' '\n' < "$LOOMMOD" | grep '"module"' | sed 's/^[^{]*//' > /tmp/loomdeps.txt
MISSING=""
while IFS= read -r line; do
  g=$(echo "$line" | sed -n 's/.*"group": *"\([^"]*\)".*/\1/p')
  a=$(echo "$line" | sed -n 's/.*"module": *"\([^"]*\)".*/\1/p')
  v=$(echo "$line" | sed -n 's/.*"requires": *"\([^"]*\)".*/\1/p')
  [ -z "$g" ] || [ -z "$a" ] || [ -z "$v" ] && continue
  case "$a" in fabric-loom|\*) continue;; esac
  GP=$(echo "$g" | tr '.' '/')
  ALIYUN="https://maven.aliyun.com/repository/public/$GP/$a/$v/$a-$v.jar"
  if curl -sI --max-time 15 "$ALIYUN" | head -1 | grep -q " 200"; then
    echo "central: $a-$v （Gradle 直接走阿里云）"
    continue
  fi
  # fabricmc 独有：先 BMCLAPI，再涓流
  D="$REPO/$GP/$a/$v"
  mkdir -p "$D"
  if [ -s "$D/$a-$v.jar" ] && unzip -tqq "$D/$a-$v.jar" >/dev/null 2>&1; then
    echo "已有: $a-$v"; continue
  fi
  echo "补齐: $g:$a:$v"
  curl -sL --max-time 120 -o "$D/$a-$v.jar" "https://bmclapi2.bangbang93.com/maven/$GP/$a/$v/$a-$v.jar" 2>/dev/null
  if [ -s "$D/$a-$v.jar" ] && head -c 2 "$D/$a-$v.jar" | grep -q PK; then
    echo "  BMCLAPI OK: $a-$v ($(stat -c%s "$D/$a-$v.jar")B)"
  else
    rm -f "$D/$a-$v.jar"
    echo "  BMCLAPI 无，涓流 fabricmc（慢）..."
    bash "$ROOT/tools/fetchseg.sh" "https://maven.fabricmc.net/$GP/$a/$v/$a-$v.jar" "$D/$a-$v.jar" || MISSING="$MISSING $g:$a:$v"
  fi
  # POM
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
