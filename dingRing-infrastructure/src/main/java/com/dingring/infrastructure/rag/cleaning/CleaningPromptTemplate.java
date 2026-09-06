package com.dingring.infrastructure.rag.cleaning;

/**
 * 清洗约束模板（3.1）：内置执行方 system prompt 与外部 MCP 工具描述共用。
 */
public final class CleaningPromptTemplate {

    public static final String VERSION = "llm-clean-v1";

    public static final String CONSTRAINTS = """
            你是文档清洗器。对用户提供的 Markdown 全文执行清洗，输出清洗后的 Markdown 全文。

            清洗目标：
            - 去除目录、分页导航、重复页眉页脚和无信息免责声明；
            - 修复 Markdown 格式问题，段落划分清晰，面向检索与生成友好；
            - 保留全部实质内容：正文、代码、表格、URL、数字、配置项不得缺失或改变语义。

            硬性约束：
            - 必须包含原文全部内容，禁止概括、缩写或省略任何章节；
            - 代码块、命令、配置键、版本号、错误码原样保留；
            - 不添加原文没有的结论或注释。

            输出格式：仅输出一个 JSON 对象：
            {"documentTitle": "文档标题", "cleanMarkdown": "# 清洗后的全文\\n..."}
            documentTitle 取文档主标题；不要输出 JSON 以外的任何文字。""";

    /**
     * MCP 工具描述（外部 Agent 用）：静态约束拼接为编译期常量，
     * 供 @Tool 注解直接引用（注解属性只接受常量表达式），与内置执行方共用 CONSTRAINTS。
     */
    public static final String MCP_TOOL_DESCRIPTION =
            "提交清洗后的知识库文档全文。提交前你必须按以下约束完成清洗：\n"
                    + CONSTRAINTS
                    + "\n提交 JSON：{\"documentTitle\": \"...\", \"cleanMarkdown\": \"...\"}";

    private CleaningPromptTemplate() {
    }

    /** 用户消息：原文全文 */
    public static String userMessage(String rawMarkdown) {
        return "请清洗以下 Markdown 全文：\n\n" + rawMarkdown;
    }
}
