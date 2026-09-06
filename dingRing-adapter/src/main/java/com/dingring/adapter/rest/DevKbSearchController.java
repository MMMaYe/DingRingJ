package com.dingring.adapter.rest;

import com.dingring.app.service.KnowledgeBaseAppService;
import com.dingring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.debug.rag-search.enabled", havingValue = "true")
public class DevKbSearchController {

    private final KnowledgeBaseAppService knowledgeBaseAppService;

    @PostMapping("/search")
    public ApiResponse<String> search(@RequestParam("query") String query,
                                      @RequestParam(value = "groupId", required = false) Long groupId) {
        return ApiResponse.ok(knowledgeBaseAppService.search(query, groupId));
    }
}
