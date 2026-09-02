package com.dingring.adapter.rest;

import com.dingring.app.dto.request.SubmitQuestionnaireRequest;
import com.dingring.app.dto.response.QuestionnaireDTO;
import com.dingring.app.service.QuestionnaireAppService;
import com.dingring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户问卷 REST API（画像主数据采集）。
 */
@RestController
@RequestMapping("/api/questionnaire")
@RequiredArgsConstructor
public class QuestionnaireController {

    private final QuestionnaireAppService questionnaireAppService;

    /** 问卷 schema + 当前有效答案（未填过返回空 answers） */
    @GetMapping
    public ApiResponse<QuestionnaireDTO> get() {
        return ApiResponse.ok(questionnaireAppService.get());
    }

    /** 提交问卷答案：事实源与画像同事务写入，画像即时生效 */
    @PostMapping
    public ApiResponse<Void> submit(@Validated @RequestBody SubmitQuestionnaireRequest request) {
        questionnaireAppService.submit(request.getAnswers());
        return ApiResponse.ok();
    }
}
