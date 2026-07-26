package com.dingring.adapter.rest;

import com.dingring.app.dto.response.KnowledgeCardDTO;
import com.dingring.app.dto.response.ReviewCardDTO;
import com.dingring.app.service.CardAppService;
import com.dingring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识卡片 REST API。
 */
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardAppService cardAppService;

    /** 卡片列表（category 为空查全部） */
    @GetMapping
    public ApiResponse<List<KnowledgeCardDTO>> list(@RequestParam(required = false) String category) {
        return ApiResponse.ok(cardAppService.list(category));
    }

    /** 复习卡片（order: sequential / random） */
    @GetMapping("/review")
    public ApiResponse<ReviewCardDTO> review(@RequestParam(required = false) String category,
                                             @RequestParam(defaultValue = "sequential") String order) {
        return ApiResponse.ok(cardAppService.review(category, order));
    }

    /** 全部分类（筛选面板用） */
    @GetMapping("/categories")
    public ApiResponse<List<String>> categories() {
        return ApiResponse.ok(cardAppService.categories());
    }
}
