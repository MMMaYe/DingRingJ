package com.dingring.infrastructure.rag.cleaning;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.dingring.domain.knowledgebase.CleaningSubmission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 清洗提交校验器（3.2 v1）：
 * JSON schema 与 cleanMarkdown 非空、输出大小上限、单文件 token 上限（按字符保守折算）。
 * hash 与 run 状态校验在 CleaningSubmissionService 执行（需查库）。
 */
@Component
public class CleaningSubmissionValidator {

    private final long maxChars;

    public CleaningSubmissionValidator(
            @Value("${dingring.rag.cleaning.max-output-chars:500000}") long maxChars) {
        this.maxChars = maxChars;
    }

    /** 解析并校验提交 JSON；非法返回 null（由调用方决定拒绝原因） */
    public CleaningSubmission parseAndValidate(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JSONObject obj;
        try {
            obj = JSON.parseObject(json);
        } catch (JSONException e) {
            return null;
        }
        if (obj == null) {
            return null;
        }
        String cleanMarkdown = obj.getString("cleanMarkdown");
        if (cleanMarkdown == null || cleanMarkdown.isBlank()) {
            return null;
        }
        if (cleanMarkdown.length() > maxChars) {
            throw new IllegalArgumentException("清洗结果超过大小上限: " + cleanMarkdown.length());
        }
        String title = obj.getString("documentTitle");
        return new CleaningSubmission(title, cleanMarkdown);
    }

    public long maxChars() {
        return maxChars;
    }
}
