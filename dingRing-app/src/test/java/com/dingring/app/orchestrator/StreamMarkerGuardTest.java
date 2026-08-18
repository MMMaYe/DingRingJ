package com.dingring.app.orchestrator;

import com.dingring.common.constant.CollaborationMarkers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StreamMarkerGuard} 流式标记护栏单元测试。
 */
@DisplayName("StreamMarkerGuard 标记护栏")
class StreamMarkerGuardTest {

    @Test
    @DisplayName("普通文本原样透传")
    void plainTextShouldPassThrough() {
        StreamMarkerGuard guard = new StreamMarkerGuard();
        assertThat(guard.onChunk("大家好，")).isEqualTo("大家好，");
        assertThat(guard.onChunk("我说两句。")).isEqualTo("我说两句。");
        assertThat(guard.flush()).isEmpty();
    }

    @Test
    @DisplayName("单 chunk 内完整标记直接剥离")
    void completeMarkerInSingleChunkShouldBeStripped() {
        StreamMarkerGuard guard = new StreamMarkerGuard();
        assertThat(guard.onChunk("可以定了" + CollaborationMarkers.CONCLUDE_MARKER + "谢谢"))
                .isEqualTo("可以定了谢谢");
        assertThat(guard.flush()).isEmpty();
    }

    @Test
    @DisplayName("标记跨 chunk 拆分不泄漏")
    void markerSplitAcrossChunksShouldNotLeak() {
        StreamMarkerGuard guard = new StreamMarkerGuard();
        assertThat(guard.onChunk("前文[[CONC")).isEqualTo("前文");
        assertThat(guard.onChunk("LUDE]]后文")).isEqualTo("后文");
        assertThat(guard.flush()).isEmpty();
    }

    @Test
    @DisplayName("纯 PASS 标记逐字符流入：全程零下发")
    void passMarkerCharByCharShouldEmitNothing() {
        StreamMarkerGuard guard = new StreamMarkerGuard();
        StringBuilder emitted = new StringBuilder();
        for (char c : CollaborationMarkers.PASS_MARKER.toCharArray()) {
            emitted.append(guard.onChunk(String.valueOf(c)));
        }
        emitted.append(guard.flush());
        assertThat(emitted.toString()).isEmpty();
    }

    @Test
    @DisplayName("疑似标记前缀被证伪后放行")
    void falseMarkerPrefixShouldBeReleased() {
        StreamMarkerGuard guard = new StreamMarkerGuard();
        assertThat(guard.onChunk("他说[[")).isEqualTo("他说");
        assertThat(guard.onChunk("不是标记]]")).isEqualTo("[[不是标记]]");
        assertThat(guard.flush()).isEmpty();
    }

    @Test
    @DisplayName("流结束时残缺前缀由 flush 放行")
    void danglingPrefixShouldBeReleasedOnFlush() {
        StreamMarkerGuard guard = new StreamMarkerGuard();
        assertThat(guard.onChunk("结尾[[PAS")).isEqualTo("结尾");
        assertThat(guard.flush()).isEqualTo("[[PAS");
    }
}
