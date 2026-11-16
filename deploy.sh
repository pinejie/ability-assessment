#!/usr/bin/env bash
# 能力测评系统 —— 生产发布脚本
#
# 用法：
#   ./deploy.sh            只发布（要求产物已构建，做法见 --build）
#   ./deploy.sh --build    先构建前端 dist + 后端 jar，再发布
#   ./deploy.sh --build-only  只构建，不动服务
#
# 说明：本机用 user service 托管（Linger=yes），不是 system unit。
#      操作前必须设 XDG_RUNTIME_DIR，脚本内已处理。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MVN=/home/wgd/apache-maven-3.9.16/bin/mvn
NODE_BIN=/home/wgd/.nvm/versions/node/v20.20.2/bin
export XDG_RUNTIME_DIR=/run/user/1000
export PATH="$NODE_BIN:/usr/local/bin:/usr/bin:/bin"

DO_BUILD=0
BUILD_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --build) DO_BUILD=1 ;;
    --build-only) DO_BUILD=1; BUILD_ONLY=1 ;;
    *) echo "未知参数: $arg"; exit 1 ;;
  esac
done

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

if [[ $DO_BUILD -eq 1 ]]; then
  # 前置检查：.env.production 被 .gitignore 排除、新机器上不存在。
  # 缺它时 Vite 会把 import.meta.env.VITE_API_BASE_URL 替成字面量 undefined，
  # 构建照常成功、dist 也照常产出，但线上 SSO 跳转会变成 undefined/auth/sso/login。
  # 这种坏包不报错，只会在用户点登录时暴露，所以在构建前就拦住。
  if [[ ! -f "$ROOT/frontend/.env.production" ]]; then
    cat <<'EOF'
❌ 缺少 frontend/.env.production —— 已中止构建

   这个文件被 .gitignore 排除，需要每台机器自行创建一次：
     printf 'VITE_API_BASE_URL=/api/v1\n' > frontend/.env.production

   不建也能构建成功，但产物里 SSO 跳转会指向 undefined/auth/sso/login。
EOF
    exit 1
  fi
  # 只校验变量存在与否；值本身允许按环境覆盖（例如独立域名部署）。
  if ! grep -q '^[[:space:]]*VITE_API_BASE_URL[[:space:]]*=' "$ROOT/frontend/.env.production"; then
    echo "❌ frontend/.env.production 存在但未定义 VITE_API_BASE_URL，已中止构建"
    exit 1
  fi

  log "构建前端 dist"
  (cd "$ROOT/frontend" && pnpm build)

  # 构建后校验产物：把 undefined 跳转挡在发布之前。
  if grep -rqs 'undefined/auth/sso/login' "$ROOT/frontend/dist"; then
    echo "❌ dist 中检测到 undefined/auth/sso/login，说明 VITE_API_BASE_URL 未生效，已中止发布"
    exit 1
  fi

  log "构建后端 jar（跳过测试，测试请单独跑 ./deploy.sh --test）"
  (cd "$ROOT/backend" && "$MVN" -q package -DskipTests)
fi

if [[ $BUILD_ONLY -eq 1 ]]; then
  log "仅构建完成，未重启服务"
  exit 0
fi

# 产物存在性检查：缺失就明确报错，不要含糊
[[ -f "$ROOT/frontend/dist/index.html" ]] || { echo "❌ 缺少 frontend/dist/index.html，先跑 ./deploy.sh --build"; exit 1; }
[[ -f "$ROOT/backend/target/ability-assessment-1.0.0.jar" ]] || { echo "❌ 缺少后端 jar，先跑 ./deploy.sh --build"; exit 1; }

log "重启后端 ability-backend"
systemctl --user restart ability-backend

# 等后端真正起来（最多 90 秒），失败就打印日志并退出，别假装成功
for i in $(seq 1 45); do
  if curl -s -o /dev/null --max-time 2 http://127.0.0.1:8080/api/v1/ability/category/list; then
    echo "   后端已就绪（${i}00ms 内）"
    break
  fi
  if [[ $i -eq 45 ]]; then
    echo "❌ 后端 90 秒内未就绪，最近日志："
    journalctl --user -u ability-backend -n 40 --no-pager
    exit 1
  fi
  sleep 2
done

log "重启前端 ability-frontend"
systemctl --user restart ability-frontend
sleep 3

log "发布后自检"
FAIL=0
check() { # $1=描述 $2=期望码 $3=url
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$3" || echo 000)
  if [[ "$code" == "$2" ]]; then
    printf '   ✅ %-28s %s\n' "$1" "$code"
  else
    printf '   ❌ %-28s 期望 %s 实际 %s\n' "$1" "$2" "$code"
    FAIL=1
  fi
}
check "首页"            200 "http://127.0.0.1:3000/"
check "SSO 回调页深链"   200 "http://127.0.0.1:3000/sso/callback"
check "接口鉴权生效"     401 "http://127.0.0.1:3000/api/v1/ability/category/list"
check "源码不可读"       404 "http://127.0.0.1:3000/src/main.ts"
check "在线文档已关闭"   401 "http://127.0.0.1:8080/v3/api-docs"

if [[ $FAIL -ne 0 ]]; then
  echo -e "\n❌ 自检存在失败项，请检查 journalctl --user -u ability-backend / ability-frontend"
  exit 1
fi
echo -e "\n✅ 发布完成"
echo "   日志: journalctl --user -u ability-backend -f"
echo "         journalctl --user -u ability-frontend -f"
