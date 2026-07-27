#!/bin/zsh
# 联调步骤 01：直连验证两个 LLM 端点（OpenAI 兼容 /chat/completions）
# 输出留痕：test-records/logs/01-llm-endpoint-check.log

LOG_DIR="$(dirname "$0")/../logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/01-llm-endpoint-check.log"
: > "$LOG"

log() { echo "$@" | tee -a "$LOG"; }

# 密钥从未入库的本地文件读取（已加入 .gitignore，避免明文入库）
KEY1=$(cat "$(dirname "$0")/.cloudbase_key")
URL1="https://come-d7grhnf176744e01b.api.tcloudbasegateway.com/v1/ai/cloudbase"
MODEL1="qwen3.5-plus"

KEY2=$(cat "$(dirname "$0")/.stepfun_key")
URL2="https://api.stepfun.com/step_plan/v1"
MODEL2="step-3.7-flash"

test_endpoint() {
  local name="$1" url="$2" key="$3" model="$4"
  log "=============================================="
  log "[$(date '+%F %T')] 测试端点: $name"
  log "URL: $url/chat/completions"
  log "Model: $model"
  local body
  body=$(printf '{"model":"%s","messages":[{"role":"user","content":"请只回复两个字：收到"}],"max_tokens":64}' "$model")
  local resp http_code
  resp=$(curl -sS -w "\n__HTTP_CODE__:%{http_code}" -m 60 \
    -X POST "$url/chat/completions" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $key" \
    -d "$body" 2>&1)
  http_code="${resp##*__HTTP_CODE__:}"
  log "HTTP 状态码: $http_code"
  log "响应体:"
  echo "${resp%$'\n'__HTTP_CODE__:*}" | tee -a "$LOG"
  log ""
}

test_endpoint "Agent-1 腾讯云 CloudBase 网关 (qwen3.5-plus)" "$URL1" "$KEY1" "$MODEL1"
test_endpoint "Agent-2 阶跃星辰 StepFun (step-3.7-flash)" "$URL2" "$KEY2" "$MODEL2"

log "[$(date '+%F %T')] 端点连通性测试完成"
