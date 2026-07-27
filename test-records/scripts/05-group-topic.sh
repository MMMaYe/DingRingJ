#!/bin/zsh
# 联调步骤 05：REST 创建群 + 发起讨论主题
# 输出留痕：test-records/logs/05-group-topic.log
LOG_DIR="$(dirname "$0")/../logs"; mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/05-group-topic.log"; : > "$LOG"
log() { echo "$@" | tee -a "$LOG"; }
API="http://localhost:8080/api"

log "[$(date '+%F %T')] POST /api/groups 创建群（成员=老王[1]，专家=苏教授[4]）"
GROUP_RESP=$(curl -sS -m 30 -X POST "$API/groups" -H "Content-Type: application/json" \
  -d '{"name":"联调测试群-0727","agentIds":[1],"expertAgentId":4}')
echo "$GROUP_RESP" | tee -a "$LOG"; log ""
GID=$(echo "$GROUP_RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')
log "==> 群ID GID=$GID"

log "[$(date '+%F %T')] POST /api/groups/$GID/topics 发起讨论主题"
TOPIC_RESP=$(curl -sS -m 30 -X POST "$API/groups/$GID/topics" -H "Content-Type: application/json" \
  -d '{"title":"MySQL 索引失效的常见场景"}')
echo "$TOPIC_RESP" | tee -a "$LOG"; log ""
TID=$(echo "$TOPIC_RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')
log "==> 主题ID TID=$TID"

# 供后续脚本读取
echo "$GID" > "$LOG_DIR/.gid"
echo "$TID" > "$LOG_DIR/.tid"
log "[$(date '+%F %T')] 群与主题创建完成"
