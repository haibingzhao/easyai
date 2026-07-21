# EasyAI Observability 使用指南

## 快速开始

### 1. 添加依赖

在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.easy</groupId>
    <artifactId>easyai-observability</artifactId>
</dependency>

<!-- Micrometer Tracing Bridge -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>

<!-- OpenTelemetry Exporter (选择一种) -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-zipkin</artifactId>
</dependency>
```

### 2. 配置 application.yml

```yaml
easyai:
  observability:
    enabled: true
    service-name: easyai-app
    max-attribute-length: 4000
    trace-agent-events: true
    trace-tool-calls: true
    trace-llm-calls: true
    mdc-propagation: true
    metrics-enabled: true

management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0  # 100% 采样
  
  # Zipkin 配置
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
  
  # Prometheus 配置（可选）
  endpoints:
    web:
      exposure:
        include: prometheus, health, metrics
```

### 3. 在 Agent 中启用 Observability

#### 方式 A：自动配置（推荐）

Spring Boot 会自动配置所有监听器，只需在 Agent 创建时附加：

```kotlin
@Component
class MyAgentService(
    private val agent: Agent,
    private val tracingEventListener: TracingEventListener,
    private val metricsEventListener: MetricsEventListener,
    private val mdcPropagationListener: MdcPropagationListener
) {
    init {
        // 附加所有监听器
        agent.withObservability(
            tracingEventListener,
            metricsEventListener,
            mdcPropagationListener
        )
    }
    
    fun chat(message: String): EventStream<AgentEvent, List<AssistantMessage>> {
        return agent.prompt(message)
    }
}
```

#### 方式 B：手动配置

```kotlin
val agent = Agent(config, chatModel)

// 只启用追踪
agent.withTracing(tracingEventListener)

// 或启用所有功能
agent.withObservability(tracing, metrics, mdc)
```

### 4. 运行 Zipkin

```bash
docker run -d -p 9411:9411 openzipkin/zipkin
```

访问 http://localhost:9411 查看追踪数据。

### 5. 查看指标

如果使用 Prometheus：

```bash
curl http://localhost:8080/actuator/prometheus
```

## 核心功能

### 分布式追踪

自动追踪以下事件：
- ✅ Agent 会话生命周期（开始/结束）
- ✅ Turn 执行（每轮对话）
- ✅ Message 生成（流式更新）
- ✅ Tool 调用（输入/输出/错误）
- ✅ LLM 调用（prompt/completion/token usage）

追踪层级示例：
```
Agent Session: chat-123
├── Turn: 1
│   ├── Message: msg-abc
│   │   ├── Tool: read
│   │   └── Tool: write
│   └── Message: msg-def
└── Turn: 2
    └── Message: msg-ghi
```

### 业务指标

自动收集以下指标：

| 指标名称 | 类型 | 描述 |
|---------|------|------|
| `easyai.agent.active` | Gauge | 当前活跃会话数 |
| `easyai.agent.sessions.total` | Counter | 总会话数 |
| `easyai.agent.duration` | Timer | 会话时长 |
| `easyai.turns.total` | Counter | 总轮次数 |
| `easyai.messages.total` | Counter | 总消息数 |
| `easyai.tool.calls.total` | Counter | Tool 调用数（按 tool 标签） |
| `easyai.tool.errors.total` | Counter | Tool 错误数 |
| `easyai.llm.tokens.total` | Counter | LLM Token 消耗（input/output） |

### MDC 日志关联

自动将 Agent 上下文注入 SLF4J MDC：

```yaml
# Logback 配置
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} [session=%X{easyai.session.id}] - %msg%n</pattern>
```

输出示例：
```
14:23:45.123 [main] INFO  c.e.MyService [session=chat-123] - Processing request
```

MDC Keys：
- `easyai.session.id` - 会话 ID
- `easyai.message.id` - 当前消息 ID
- `easyai.tool.call_id` - 当前 Tool 调用 ID

### @Tracked 自定义追踪

标记需要追踪的方法：

```kotlin
@Component
class CustomerService {
    
    @Tracked(value = "enrichCustomer", type = TrackType.PROCESSING)
    fun enrich(input: Customer): Customer {
        // 业务逻辑
        return enrichedCustomer
    }
    
    @Tracked(value = "callPaymentApi", type = TrackType.EXTERNAL_CALL)
    fun processPayment(order: Order): PaymentResult {
        // 外部 API 调用
        return paymentResult
    }
}
```

**注意**：@Tracked 使用 Spring AOP，内部方法调用不会被拦截。解决方法：
1. 提取到单独的 Bean（推荐）
2. 自注入：`@Autowired private lateinit var self: MyService`

## 支持的导出器

### Zipkin
```yaml
management:
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

### OTLP (Jaeger, Grafana Tempo)
```yaml
management:
  otlp:
    tracing:
      endpoint: http://localhost:4317
```

### Langfuse（LLM 专用）
需要额外依赖：
```xml
<dependency>
    <groupId>com.quantpulsar</groupId>
    <artifactId>opentelemetry-exporter-langfuse</artifactId>
    <version>0.4.0</version>
</dependency>
```

```yaml
management:
  langfuse:
    enabled: true
    endpoint: https://cloud.langfuse.com/api/public/otel
    public-key: pk-lf-...
    secret-key: sk-lf-...
```

### Prometheus
```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus
```

## 配置选项

| 配置项 | 默认值 | 说明 |
|-------|--------|------|
| `easyai.observability.enabled` | `true` | 启用/禁用可观测性 |
| `easyai.observability.service-name` | `easyai` | 服务名称 |
| `easyai.observability.max-attribute-length` | `4000` | 最大属性长度 |
| `easyai.observability.trace-agent-events` | `true` | 追踪 Agent 事件 |
| `easyai.observability.trace-tool-calls` | `true` | 追踪 Tool 调用 |
| `easyai.observability.trace-llm-calls` | `true` | 追踪 LLM 调用 |
| `easyai.observability.mdc-propagation` | `true` | MDC 传播 |
| `easyai.observability.metrics-enabled` | `true` | 启用指标收集 |
| `easyai.observability.trace-tracked-operations` | `true` | 启用 @Tracked |

## OpenTelemetry GenAI 语义规范

所有 Span 遵循 OpenTelemetry GenAI 语义规范：

- `gen_ai.operation.name` - 操作类型（chat / execute_tool / agent_session）
- `gen_ai.request.model` - 模型名称
- `gen_ai.request.temperature` - 温度参数
- `gen_ai.request.max_tokens` - 最大 Token 数
- `gen_ai.usage.input_tokens` - 输入 Token 数
- `gen_ai.usage.output_tokens` - 输出 Token 数
- `gen_ai.prompt` - 用户提示词
- `gen_ai.completion` - LLM 响应
- `gen_ai.tool.name` - Tool 名称

## 故障排查

### 问题：没有看到追踪数据

1. 检查配置：`easyai.observability.enabled=true`
2. 检查导出器配置是否正确
3. 确认 Zipkin/OTLP 服务正在运行
4. 检查日志中是否有 "Configuring EasyAI tracing" 信息

### 问题：@Tracked 不生效

1. 确认添加了 `spring-boot-starter-aop` 依赖
2. 确认方法是 public 的
3. 确认不是内部调用（同一类中的方法调用）
4. 检查日志中是否有 "Configuring @Tracked annotation aspect"

### 问题：MDC 中没有 session id

1. 检查配置：`easyai.observability.mdc-propagation=true`
2. 确认在 Agent 事件流上下文中调用
3. 检查 Logback pattern 是否包含 `%X{easyai.session.id}`

## 完整示例

参考 `easyai-example` 模块中的示例代码。

## 参考资料

- [OpenTelemetry GenAI Spans](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
- [Spring Boot Observation](https://docs.spring.io/spring-boot/reference/actuator/observability.html)
- [Micrometer Tracing](https://micrometer.io/docs/tracing)
