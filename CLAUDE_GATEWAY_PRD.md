# Claude API Gateway 升级版完整需求文档（PRD + 技术规格）

版本：V3  
状态：可直接用于 Codex / 工程实现  
目标读者：后端工程、架构、运维、产品负责人

---

## 1. 项目背景

### 1.1 背景问题
- Claude 官方 API / Claude Code 在国内存在：
  - 网络不稳定
  - 账号/支付门槛高
  - 企业无法统一管控 API Key
- 客户希望：
  - 使用 **接近原生 Claude / Claude Code 的方式**
  - 但 **不直接接触 Anthropic 官方账号**
  - 能统一计费、限流、审计

### 1.2 项目目标
构建一个 **Anthropic-compatible API Gateway**：

- 上游：Anthropic Claude 官方 API
- 中台：你们的 Gateway
- 下游：客户（SDK / curl / Claude CLI / Claude Code）

---

## 2. 总体目标（What & Why）

### 2.1 核心目标
- 封装 Claude 官方 API Key
- 对客户提供 **你们自己的 API Key**
- 最大程度保持 Claude 原生 API 体验
- 支持 **Streaming（SSE）**
- 支持 **多租户 / 限流 / Key Pool / 计量**

### 2.2 非目标（明确不做）
- 不做 Anthropic 账号体系
- 不承诺 Claude Code 100% 兼容
- 不改写 Claude 返回内容
- 不做模型调优

---

## 3. 系统架构

```
Client (SDK / curl / Claude CLI)
  |
  | Authorization: Bearer <customer_key>
  |
Claude API Gateway（WebFlux）
  |
  | x-api-key: <anthropic_key>
  |
Anthropic Claude API
```

---

## 4. 技术选型

| 组件 | 技术 |
|---|---|
| 语言 | Java 21 |
| 框架 | Spring Boot 3.x |
| Web | Spring WebFlux |
| HTTP Client | WebClient |
| 构建 | Maven |
| 序列化 | Jackson |
| 流式 | SSE（DataBuffer 透传） |

---

## 5. 鉴权与租户模型

### 5.1 客户 API Key

- Header：
  ```
  Authorization: Bearer <customer_api_key>
  ```

### 5.2 TenantContext

| 字段 | 说明 |
|---|---|
| tenantId | 企业/租户 |
| userId | 用户 |
| plan | 套餐类型 |

- 存储于 Reactor Context
- Key = `TENANT_CTX`

---

## 6. 限流策略

### 6.1 MVP 策略
- 维度：tenantId
- 算法：Token Bucket
- 默认：defaultRpm（配置）
- 超限返回：HTTP 429

---

## 7. 上游 Claude Key Pool

### 7.1 设计目标
- 多个 Claude 官方 API Key
- 避免单 Key 429 / 封禁
- 可轮询、可扩展

### 7.2 行为
- Round-Robin
- 单 Key 失败 → 切换下一个（V3 允许预留接口）

---

## 8. API 设计（Anthropic-compatible）

### 8.1 Messages 接口

#### Endpoint
```
POST /anthropic/v1/messages
```

#### Headers（下游）
```
Authorization: Bearer <customer_key>
Content-Type: application/json
```

#### Body（透传）
- 完全遵循 Anthropic Messages API
- 不做 DTO 校验

---

## 9. Streaming 自动识别（升级重点）

### 9.1 规则
- 从 JSON body 中解析：
  ```json
  { "stream": true }
  ```
- 若不存在或 false → 非流式
- 不依赖 query param

### 9.2 实现约束
- 使用 Jackson Tree Model
- 不反序列化为完整对象
- Body 必须原样转发

---

## 10. 上游转发规则

### 10.1 Headers
```
x-api-key: <anthropic_key>
anthropic-version: <config>
Content-Type: application/json
Accept:
  - text/event-stream (stream=true)
  - application/json (stream=false)
```

### 10.2 Response
- 非流式：JSON 原样返回
- 流式：SSE 原样透传
- 禁止聚合、禁止阻塞

---

## 11. Controller 行为规范

- 使用 `@RequestBody byte[]`
- 使用 `ServerHttpResponse.writeWith(Flux<DataBuffer>)`
- 禁止：
  - block()
  - collectList()
  - bodyToMono(String)

---

## 12. 配置项

```yaml
gw:
  upstreamBaseUrl: https://api.anthropic.com
  anthropicVersion: 2023-06-01
  upstreamApiKeys:
    - KEY_1
    - KEY_2
  defaultRpm: 60
```

---

## 13. 可观测性（预留）

### 13.1 必须记录
- requestId
- tenantId
- 是否 stream
- HTTP 状态码

### 13.2 后续可扩展
- usage 统计
- token 计费
- SLA

---

## 14. 安全要求

- 不向客户端暴露 Anthropic Key
- API Key 可撤销
- 禁止 curl | bash 安装脚本
- 不记录请求正文（默认）

---

## 15. 验收标准

- mvn package 成功
- 非流式/流式均可用
- Claude SDK 可直连
- Claude CLI 基础命令可用
- Key Pool 可轮询

---

## 16. 后续演进路线

- V4：usage 计费 & tenant 账单
- V5：多模型路由
- V6：企业控制台

---
