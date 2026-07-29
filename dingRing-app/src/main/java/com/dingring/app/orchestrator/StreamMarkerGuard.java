package com.dingring.app.orchestrator;

/**
 * 流式输出协作标记护栏：防止 [[CONCLUDE]] / [[PASS]] 被拆到多个 chunk 后泄漏给前端。
 * <p>策略：完整标记直接剥离；缓冲区尾部若可能是某标记的前缀则暂扣不下发，
 * 等后续 chunk 拼出完整标记（剥离）或证伪（放行）。非线程安全，单次流式会话内使用。
 */
public class StreamMarkerGuard {

    private static final String[] MARKERS = {ContextBuilder.CONCLUDE_MARKER, ContextBuilder.PASS_MARKER};

    private final StringBuilder buf = new StringBuilder();

    /**
     * 收到一个原始 chunk，返回当前可安全下发的文本（可能为空串）。
     */
    public String onChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        buf.append(chunk);
        String s = stripCompleteMarkers(buf.toString());
        int hold = holdLength(s);
        String emit = s.substring(0, s.length() - hold);
        buf.setLength(0);
        buf.append(s, s.length() - hold, s.length());
        return emit;
    }

    /**
     * 流结束收尾：返回缓冲区剩余可下发文本（残缺的标记前缀已证伪，原样放行）。
     */
    public String flush() {
        String s = stripCompleteMarkers(buf.toString());
        buf.setLength(0);
        return s;
    }

    private static String stripCompleteMarkers(String s) {
        for (String marker : MARKERS) {
            s = s.replace(marker, "");
        }
        return s;
    }

    /** 尾部与任一标记前缀重合的最大长度（需暂扣的字符数） */
    private static int holdLength(String s) {
        int max = 0;
        for (String marker : MARKERS) {
            int limit = Math.min(marker.length() - 1, s.length());
            for (int k = limit; k > max; k--) {
                if (s.regionMatches(s.length() - k, marker, 0, k)) {
                    max = k;
                    break;
                }
            }
        }
        return max;
    }
}
