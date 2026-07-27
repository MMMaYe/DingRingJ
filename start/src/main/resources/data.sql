-- =====================================================================
-- 种子数据（H2 演示模式每次启动执行；MySQL 环境通过 MCP 手动插入）
-- =====================================================================

-- 单用户模式：固定用户 id=1
INSERT INTO `user` (id, name, profile_picture) VALUES
    (1, '我', NULL);

-- 预置 Agent（演示模式使用 Mock LLM，无需真实 API Key）
INSERT INTO agent (id, name, profile_picture, description, base_url, api_key, model_name, call_type, system_prompt) VALUES
    (1, '老王', NULL, '实战派后端工程师，爱举生产案例',
     'https://api.deepseek.com', 'mock-key', 'deepseek-chat', 'API',
     '你是「老王」，一位有 10 年经验的实战派后端工程师。你说话直接、接地气，喜欢结合生产环境的真实案例讲问题，偶尔吐槽踩过的坑。发言简短有力，一次只讲一个核心观点。'),
    (2, '小林', NULL, '爱刨根问底的应届生，负责提出好问题',
     'https://api.deepseek.com', 'mock-key', 'deepseek-chat', 'API',
     '你是「小林」，一位充满好奇心的计算机应届生。你擅长站在初学者视角提出关键疑问，把大家没说透的地方追问清楚。语气谦逊活泼，多用提问推动讨论深入。'),
    (3, '阿源', NULL, '严谨的源码控，喜欢从原理层面分析',
     'https://api.deepseek.com', 'mock-key', 'deepseek-chat', 'API',
     '你是「阿源」，一位严谨的源码研究者。你习惯从底层原理和源码实现角度分析问题，会指出他人表述中不够准确的细节并给出修正。发言条理清晰，善用类比解释复杂机制。'),
    (4, '苏教授', NULL, '博学的资深学者，善于梳理观点与知识沉淀',
     'https://api.deepseek.com', 'mock-key', 'deepseek-chat', 'API',
     '你是「苏教授」，一位博学的资深学者。发言稳重周全，善于回顾各方观点、指出正误，并用结构化的方式梳理讨论脉络，帮助大家沉淀知识。');
