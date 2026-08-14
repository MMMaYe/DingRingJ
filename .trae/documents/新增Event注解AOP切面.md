# 计划：新增 @Event 注解 AOP 切面

## 概述
在 `dingRing-infrastructure` 模块下新增 AOP 相关包，实现 `@Event` 注解，用于标注在方法上自动打印方法入参和出参日志。

## 当前状态分析
- `infrastructure` 下现有包：`event`、`llm`、`memory`、`persistence`
- 无 AOP 相关依赖：`spring-boot-starter-aop` 未被任何模块引入
- 项目日志工具：`com.dingring.common.util.LogHelper`（common 模块）
- 项目已有 fastjson 版本管理（根 pom 定义 1.2.83），但 infrastructure 未引入

## 变更清单

### 1. 新增文件：`Event.java` — 注解定义
- **路径**: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/aop/Event.java`
- **内容**:
  ```java
  package com.dingring.infrastructure.aop;

  import java.lang.annotation.ElementType;
  import java.lang.annotation.Retention;
  import java.lang.annotation.RetentionPolicy;
  import java.lang.annotation.Target;

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Event {
      /** 事件码，大写蛇形，如 "METHOD_CALL" */
      String eventCode();
      
      /** 事件名称，中文人读，如 "方法调用"，默认取方法名 */
      String eventName() default "";
      
      /** 是否打印入参，默认 true */
      boolean logArgs() default true;
      
      /** 是否打印出参/返回值，默认 true */
      boolean logResult() default true;
  }
  ```

### 2. 新增文件：`EventAspect.java` — 切面实现
- **路径**: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/aop/EventAspect.java`
- **行为**:
  - 拦截所有标注 `@Event` 的方法（不限包）
  - 使用 `@Around` 环绕通知
  - 日志格式：`[ClassName.methodName][eventCode][eventName] request=<JSON>`
  - 正常返回：`[ClassName.methodName][eventCode][eventName] result=<JSON>`（INFO 级别）
  - 异常时：`[ClassName.methodName][eventCode][eventName] error=<异常信息>`（WARN 级别，re-throw）
  - 参数序列化：`com.alibaba.fastjson.JSON.toJSONString()`
  - 不截断，不限制长度
  - 受 `logArgs` / `logResult` 属性控制是否打印对应信息
  - 直接使用 SLF4J `Logger`，不依赖 `LogHelper`

### 3. 修改文件：`dingRing-infrastructure/pom.xml`
- 新增两个依赖：
  - `spring-boot-starter-aop`（版本由 Spring Boot BOM 管理）
  - `com.alibaba:fastjson:1.2.83`（版本由根 pom dependencyManagement 管理）

## 决策记录
| 决策 | 结论 |
|------|------|
| 包名 | `com.dingring.infrastructure.aop` |
| 注解属性 | `eventCode`(必填)、`eventName`(默认方法名)、`logArgs`、`logResult` |
| 拦截范围 | 所有标注 `@Event` 的方法，不限包 |
| 切面方式 | `@Around` 环绕通知 |
| 异常处理 | 捕获后打 WARN 日志再 re-throw |
| 序列化工具 | fastjson |
| 日志格式 | `[ClassName.methodName][eventCode][eventName] request/result/error=<JSON>` |
| 参数截断 | 不做 |
| 类级别注解 | 不支持 |
| 依赖位置 | `spring-boot-starter-aop` + `fastjson` 加在 infrastructure/pom.xml |

## 验证步骤
1. 执行 `mvn compile -pl dingRing-infrastructure -am` 确认编译通过
2. 执行 `mvn test -pl dingRing-infrastructure -am` 确认现有测试不受影响