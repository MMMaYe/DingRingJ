#!/bin/zsh
# 联调步骤 04：通过后端 REST API 更新真实 Agent 配置
# - 老王(id=1)   讨论成员：StepFun step-3.7-flash（真实 key）
# - 苏教授(id=4) 专家：    StepFun step-3.7-flash（真实 key）
# （Agent-1 CloudBase/qwen3.5-plus 的完整 apikey 待补发后替换到老王或新建 Agent）
# 输出留痕：test-records/logs/04-agent-setup.log

LOG_DIR="$(dirname "$0")/../logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/04-agent-setup.log"
: > "$LOG"
log() { echo "$@" | tee -a "$LOG"; }

API="http://localhost:8080/api"
# 密钥从未入库的本地文件读取（已加入 .gitignore，避免明文入库）
KEY_STEPFUN=$(cat "$(dirname "$0")/.stepfun_key")

log "[$(date '+%F %T')] PUT /api/agents/1 老王 -> StepFun 真实配置"
curl -sS -m 30 -X PUT "$API/agents/1" -H "Content-Type: application/json" -d '{
  "name": "老王",
  "description": "实战派后端工程师，爱举生产案例",
  "baseUrl": "https://api.stepfun.com/step_plan/v1",
  "apiKey": "'"$KEY_STEPFUN"'",
  "modelName": "step-3.7-flash",
  "callType": "API",
  "systemPrompt": "你是「老王」，一位有 10 年经验的实战派后端工程师。你说话直接、接地气，喜欢结合生产环境的真实案例讲问题，偶尔吐槽踩过的坑。发言简短有力，一次只讲一个核心观点，每次发言不超过 120 字。",
  "feature": {"temperature": 0.7, "maxTokens": 2048}
}' | tee -a "$LOG"
log ""

log "[$(date '+%F %T')] PUT /api/agents/4 苏教授(专家) -> StepFun 真实配置"
curl -sS -m 30 -X PUT "$API/agents/4" -H "Content-Type: application/json" -d '{
  "name": "苏教授",
  "description": "领域专家，负责总结陈词与知识沉淀",
  "baseUrl": "https://api.stepfun.com/step_plan/v1",
  "apiKey": "'"$KEY_STEPFUN"'",
  "modelName": "step-3.7-flash",
  "callType": "API",
  "systemPrompt": "你是「苏教授」，本群的领域专家。平时不参与普通讨论，只在讨论结束时进行总结陈词：全面回顾各方观点，指出正误，并按 STAR 法则输出结构化结论，帮助大家沉淀知识。",
  "feature": {"temperature": 0.5, "maxTokens": 3072}
}' | tee -a "$LOG"
log ""

log "[$(date '+%F %T')] GET /api/agents 确认列表"
curl -sS -m 30 "$API/agents" | tee -a "$LOG"
log ""
log "[$(date '+%F %T')] Agent 配置完成"
