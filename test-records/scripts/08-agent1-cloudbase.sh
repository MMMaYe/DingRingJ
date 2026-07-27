#!/bin/zsh
# 联调步骤 08：老王(id=1) 切换为 Agent-1 CloudBase/qwen3.5-plus 真实配置
# （苏教授 id=4 专家保持 Agent-2 StepFun/step-3.7-flash）
# 输出留痕：test-records/logs/08-agent1-cloudbase.log
LOG_DIR="$(dirname "$0")/../logs"; mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/08-agent1-cloudbase.log"; : > "$LOG"
log() { echo "$@" | tee -a "$LOG"; }

KEY1=$(cat "$(dirname "$0")/.cloudbase_key")
API="http://localhost:8080/api"

BODY=$(python3 - "$KEY1" <<'PYEOF'
import json, sys
print(json.dumps({
  "name": "老王",
  "description": "实战派后端工程师，爱举生产案例",
  "baseUrl": "https://come-d7grhnf176744e01b.api.tcloudbasegateway.com/v1/ai/cloudbase",
  "apiKey": sys.argv[1],
  "modelName": "qwen3.5-plus",
  "callType": "API",
  "systemPrompt": "你是「老王」，一位有 10 年经验的实战派后端工程师。你说话直接、接地气，喜欢结合生产环境的真实案例讲问题，偶尔吐槽踩过的坑。发言简短有力，一次只讲一个核心观点，每次发言不超过 120 字。",
  "feature": {"temperature": 0.7, "maxTokens": 2048}
}, ensure_ascii=False))
PYEOF
)

log "[$(date '+%F %T')] PUT /api/agents/1 老王 -> CloudBase qwen3.5-plus"
curl -sS -m 30 -X PUT "$API/agents/1" -H "Content-Type: application/json" -d "$BODY" | tee -a "$LOG"
log ""
log "[$(date '+%F %T')] GET /api/agents/1 确认（详情接口含 apiKey，校验前80字符）"
curl -sS -m 30 "$API/agents/1" | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print(json.dumps({"id":d["id"],"name":d["name"],"baseUrl":d["baseUrl"],"modelName":d["modelName"],"apiKey前80":(d.get("apiKey") or "")[:80]}, ensure_ascii=False))' | tee -a "$LOG"
log "[$(date '+%F %T')] 完成"
