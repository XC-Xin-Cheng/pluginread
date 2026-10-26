#!/bin/bash
# 按本地仓库实际存在的版本重新生成 fabric-api umbrella 的 GMM .module
set -u
REPO="/c/Users/qhad/.zcode/workspace/draft-c180d6c3-4cd1-4de1-9d16-e986777e8ad4/plugin-read/local-fabric-maven/net/fabricmc/fabric-api"
VER="0.144.0+26.1"
DEPS=""
N=0
for d in "$REPO"/*/; do
  a=$(basename "$d")
  [ "$a" = "fabric-api" ] && continue
  v=""
  # 优先嵌套独立版本
  for cand in "$d"*/; do
    vv=$(basename "$cand")
    case "$vv" in 0.145.1+26.1) continue ;; esac
    if [ -s "$cand/$a-$vv.jar" ]; then v="$vv"; break; fi
  done
  # 回退 0.145.1+26.1
  if [ -z "$v" ] && [ -s "$d/0.145.1+26.1/$a-0.145.1+26.1.jar" ]; then
    v="0.145.1+26.1"
  fi
  if [ -z "$v" ]; then
    echo "跳过（无 jar）: $a"
    continue
  fi
  DEPS="$DEPS{\"group\":\"net.fabricmc.fabric-api\",\"module\":\"$a\",\"version\":{\"requires\":\"$v\"}},"
  N=$((N+1))
done
DEPS=${DEPS%,}
cat > "$REPO/fabric-api/$VER/fabric-api-$VER.module" <<EOF
{
  "formatVersion": 1,
  "variants": [
    {
      "name": "api",
      "attributes": {"org.gradle.category": "library", "org.gradle.usage": "java-api", "org.gradle.libraryelements": "jar"},
      "dependencies": [$DEPS]
    },
    {
      "name": "runtime",
      "attributes": {"org.gradle.category": "library", "org.gradle.usage": "java-runtime", "org.gradle.libraryelements": "jar"},
      "dependencies": [$DEPS]
    }
  ]
}
EOF
echo "依赖模块数: $N"
python -m json.tool "$REPO/fabric-api/$VER/fabric-api-$VER.module" >/dev/null 2>&1 && echo "JSON 校验通过" || echo "JSON 校验失败（python 不可用则忽略）"
touch "$(dirname "$REPO/fabric-api/$VER")"/*/* 2>/dev/null
echo OK
