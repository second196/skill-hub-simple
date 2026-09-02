---
title: TypeScript 最佳实践规范
description: TypeScript 类型设计、函数实现、模块组织、错误处理和编码边界规范
audience:
  - product-development
owner: product-development
status: active
lastReviewed: 2026-05-22
sourceType: manual
---

# TypeScript 最佳实践规范

本文面向产品开发人员，用于约束 TypeScript 代码在类型设计、实现方式和公共契约维护上的一致性。

## 1. 基本原则

- 先表达业务语义，再追求类型技巧。
- 优先复用当前模块、当前工程或相邻领域中已经稳定的类型、服务和工具函数。
- 公共导出类型必须稳定、可读、可演进，不把内部实现细节暴露给调用方。
- 类型约束应帮助调用方正确使用 API，不应为了“类型炫技”制造理解成本。
- 对外契约变更必须评估调用方、扩展方、集成方和其他下游消费者的影响。

## 2. 类型设计

### 2.1 优先使用精确类型

推荐：

```typescript
type ExtensionHost = 'web' | 'desktop'

type PrintConfig = {
  serviceUrl?: string
  clientConvertBatchSize?: number
}
```

不推荐：

```typescript
type ExtensionHost = string

type PrintConfig = Record<string, any>
```

如果值域固定，优先使用联合字面量、枚举或明确对象结构。只有当对象确实来自未知外部输入，才使用宽泛类型。

### 2.2 避免随意使用 `any`

优先级如下：

1. 已有业务类型
2. 新增明确类型
3. `unknown`
4. `any`

`any` 只允许用于以下场景：

- 对接历史代码，短期无法补齐类型。
- 三方库类型缺失，且本次任务不适合补充声明。
- 需要保持旧 API 兼容。

使用 `any` 时应把范围限制在最小位置，不让 `any` 穿透公共接口。

### 2.3 外部输入先用 `unknown`

外部接口、JSON、远程扩展、浏览器事件和反序列化数据进入系统时，优先使用 `unknown`，再做校验或收窄。

```typescript
function readMessage(input: unknown): string | undefined {
  if (typeof input !== 'object' || input === null) {
    return undefined
  }

  const record = input as { message?: unknown }
  return typeof record.message === 'string' ? record.message : undefined
}
```

不要把未经校验的外部数据直接断言为业务模型。

### 2.4 区分可选、空值和默认值

- 字段可能不存在时使用 `?`。
- 字段明确允许为空时使用 `null`。
- 运行时默认值在读取边界处理，不散落在业务流程中。

推荐：

```typescript
type AutoSaveConfig = {
  enabled?: boolean
  time?: number
}

const enabled = config.enabled ?? false
const time = config.time ?? 1000 * 60 * 10
```

不推荐：

```typescript
const enabled = config.enabled || false
const time = config.time || 1000 * 60 * 10
```

数值、空字符串和 `false` 都可能是合法值，默认值判断优先使用 `??`。

## 3. 函数与控制流

### 3.1 函数只承担一个清晰职责

函数应能用一句话说清楚职责。若函数同时做读取、校验、转换、渲染和副作用，应拆分。

推荐：

```typescript
function normalizeServiceUrl(serviceUrl: string): string {
  return serviceUrl.endsWith('/') ? serviceUrl : `${serviceUrl}/`
}
```

不推荐把 URL 处理、请求、UI 提示和状态更新写在同一个工具函数里。

### 3.2 优先早返回

复杂条件分支优先使用早返回，减少嵌套。

```typescript
if (!serviceUrl) {
  notification.warning('服务地址未配置')
  return
}

await requestService(serviceUrl)
```

### 3.3 异步函数必须有明确失败语义

异步函数要明确失败时的行为：

- 抛出异常
- 返回 `undefined`
- 返回结构化结果
- 触发统一错误处理

不要一边吞异常，一边让调用方以为执行成功。

推荐：

```typescript
type LoadResult<T> =
  | { success: true; data: T }
  | { success: false; error: Error }
```

公共 API 中如果选择返回结构化结果，应保持同类 API 一致。

## 4. 类型断言与类型守卫

### 4.1 控制类型断言范围

类型断言应靠近数据边界，并尽快转换成明确类型。

不推荐：

```typescript
const data = response.data as DocumentCollectionRenderData
render(data.documents[0])
```

推荐：

```typescript
const data = parseDocumentCollection(response.data)
if (!data) {
  return
}

render(data.documents[0])
```

### 4.2 为复杂收窄提供类型守卫

```typescript
function isCommandId(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}
```

类型守卫适合复用性高、判断逻辑复杂或用于多个入口的数据收窄。

## 5. 模块与导出

### 5.1 公共导出要克制

新增 `export` 前先判断：

- 是否确实被包外使用。
- 是否属于稳定契约。
- 是否已有更合适的导出入口。
- 是否会暴露内部实现细节。

内部工具优先留在模块内或就近目录，不为了“可能复用”提前导出。

### 5.2 类型与实现就近维护

- 只服务单个模块的类型，放在模块附近。
- 多模块共享类型，放到当前工程已有的公共类型目录、核心模块或稳定导出入口。
- 对外 API 类型要从稳定入口导出，避免调用方引用深层内部路径。

## 6. 对象、集合与不可变性

### 6.1 不随意修改入参

除非函数名和注释明确说明会修改入参，否则默认不要改变传入对象。

推荐：

```typescript
function withDefaultConfig(config: PrintConfig): PrintConfig {
  return {
    serviceUrl: '',
    ...config
  }
}
```

### 6.2 集合操作优先表达意图

- 查找单项用 `find`。
- 判断存在用 `some`。
- 过滤集合用 `filter`。
- 转换集合用 `map`。
- 有副作用的遍历用 `for...of`。

不要滥用 `map` 做副作用。

## 7. 错误处理

### 7.1 捕获异常后必须处理

允许的处理方式：

- 转换为领域错误。
- 记录日志并返回明确失败结果。
- 补充上下文后重新抛出。
- 交给统一错误边界。

不推荐：

```typescript
try {
  await save()
} catch (error) {}
```

### 7.2 `catch` 中按 `unknown` 处理

```typescript
try {
  await save()
} catch (error: unknown) {
  const message = error instanceof Error ? error.message : '未知错误'
  notification.error({ message })
}
```

不要假设捕获到的一定是 `Error`。

## 8. Vue / TSX 代码中的 TypeScript

- 组件 Props、Emits、Slots、Expose 方法要有明确类型。
- `.vue` 和 `.tsx` 的写法优先跟随相邻文件，不为了个人偏好切换风格。
- 组合式逻辑中，`ref`、`computed`、`reactive` 不要滥写宽泛类型。
- 事件处理函数要表达参数类型，不让事件对象隐式变成 `any`。
- 组件对外暴露的方法应保持小而稳定，不暴露内部状态对象。

推荐：

```typescript
const loading = ref(false)

const handleSave = async (): Promise<void> => {
  loading.value = true
  try {
    await save()
  } finally {
    loading.value = false
  }
}
```

## 9. 注释与文档

- 公共接口、公共类型、组件 Props、Provider 契约应补充 TypeScript 文档注释。
- 注释解释“为什么”和“边界”，不重复代码已经表达清楚的内容。
- 文档注释格式遵守 [TypeScript 文档注释规范](./typescript-doc-style-guide.md)。
- 临时兼容、历史原因、危险断言和非显然算法应写短注释说明。

## 10. 禁止与限制

- 不在新增公共代码中引入新的全局 `any` 入口。
- 不把 `as any` 作为解决类型问题的默认手段。
- 不通过深层路径引用其它模块或其它工程的内部实现，除非该路径已是稳定公开入口。
- 不在业务模块中反向承载公共基础类型。
- 不用布尔参数表达多个行为分支，优先使用具名 options 对象。
- 不把接口返回值、配置对象、扩展上下文等公共契约随意改名或改结构。

## 11. 评审检查清单

提交 TypeScript 改动前至少检查：

- 类型是否表达了真实业务语义。
- 是否有可以复用的已有类型或工具。
- `any`、类型断言和非空断言是否必要且范围最小。
- 外部输入是否经过校验或收窄。
- 默认值是否使用 `??` 处理合法假值。
- 公共导出是否确实需要对外暴露。
- 异步失败语义是否明确。
- 公共接口是否补齐必要文档注释。
