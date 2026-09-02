---
title: TypeScript 文档注释规范
description: TypeScript 接口、属性、方法和类型的结构化文档注释规范
audience:
  - product-development
owner: product-development
status: active
lastReviewed: 2026-04-27
sourceType: manual
---

### **TypeScript 文档注释规范**

#### **核心理念**

我们的目标是将注释不仅仅看作是简单的描述，而是作为直接嵌入代码中的结构化元数据。AI将解析这些元数据，以构建一个关于您接口的完整、准确且具备上下文感知能力的知识图谱。

#### **一、通用原则**

1.  **文档块格式**: 所有文档注释都必须使用 `/** ... */` 的块格式。使用 `//` 或 `/* ... */` 的注释将被解析器忽略。
2.  **Markdown 的使用**: 主要描述以及所有标签的内容都应使用 Markdown 编写，以支持富文本格式。
3.  **清晰与简洁**: 描述应当清晰、简洁，并从使用者的角度阐述接口或成员的用途。

---

#### **二、接口级别的注释**

该注释块位于接口定义之前，用于描述其整体用途和背景。

**结构:**

```typescript
/**
 * [接口的主要描述]
 *
 * @remarks
 * [可选: 更详细的解释、背景或设计动机。]
 *
 * @example [可选: 示例的标题]
 * ```typescript
 * // 接口的完整使用示例
 * const user: IUserProfile = {
 *   userId: '123-abc',
 *   username: 'JohnDoe',
 *   email: 'john.doe@example.com',
 *   registrationDate: new Date(),
 * };
 * ```
 *
 * @see {@link IOtherInterface} - 用于关联其他相关接口。
 * @version 1.0.0 - 该接口被引入或最后更新的版本。
 * @deprecated [可选: 废弃原因] - 请改用 {@link INewInterface}。
 */
export interface IUserProfile {
  // ... 属性和方法
}
```

**标签说明:**

| 标签 | 是否必需 | 描述 |
| :--- | :--- | :--- |
| (主要描述) | 是 | 用一个清晰的句子总结接口的用途。 |
| `@remarks` | 否 | 用于提供更详尽的解释、设计理念或复杂概念，这些内容不适合放在摘要中。 |
| `@example` | 否 | 提供一个完整、可直接复制粘贴的代码块，演示如何使用该接口。可多次使用。 |
| `@see` | 否 | 使用 `{@link}` 内联标签链接到另一个相关的类、接口或函数。 |
| `@version` | 否 | 指定该API被引入时的软件版本。 |
| `@deprecated` | 否 | 标记整个接口为已废弃。随附的文本**必须**解释废弃原因以及应使用哪个新接口替代。 |

---

#### **三、属性级别的注释**

该注释块位于接口内每个属性的前面。

**结构:**

```typescript
/**
 * [属性的主要描述]
 *
 * @remarks
 * [可选: 关于该属性行为的更深入细节。]
 *
 * @default "default-value"
 *
 * @example
 * ```typescript
 * // 此特定属性的使用示例
 * const username = user.username; // "JohnDoe"
 * ```
 *
 * @constraint {"type": "string", "minLength": 3, "maxLength": 50}
 *
 * @readonly
 * @deprecated [废弃原因]
 */
propertyName: type;
```

**标签说明:**

| 标签 | 是否必需 | 描述 |
| :--- | :--- | :--- |
| (主要描述) | 是 | 简洁地概括该属性代表什么。 |
| `@remarks` | 否 | 在必要时提供更详细的解释。 |
| `@default` | 否 | 如果属性是可选的，则注明其默认值。对于字符串，请用引号包裹，例如：`@default "anonymous"`。 |
| `@example` | 否 | 针对此属性的特定示例。 |
| **`@constraint`** | 否 | **(AI增强标签)** 一个描述校验规则的JSON对象。这为AI提供了结构化数据。例如：`{"min": 0, "max": 100}`, `{"pattern": "^\\d{3}$"}`。 |
| `@readonly` | 否 | 表示该属性在初始化后不可修改。 |
| `@deprecated` | 否 | 标记单个属性为已废弃。 |

---

#### **四、方法级别的注释**

该注释块位于接口内每个方法签名的前面。

**结构:**

```typescript
/**
 * [方法用途的主要描述]
 *
 * @param paramName - [参数的描述]
 * @param options - [options对象的描述]
 * @param options.id - [options对象中嵌套属性的描述]
 *
 * @returns [返回值的描述]
 *
 * @throws {ErrorType} - [在何种条件下会抛出此错误]
 *
 * @example
 * ```typescript
 * user.updateEmail('new.email@example.com')
 *   .then(success => console.log(success));
 * ```
 */
methodName(paramName: type): returnType;
```

**标签说明:**

| 标签 | 是否必需 | 描述 |
| :--- | :--- | :--- |
| (主要描述) | 是 | 总结该方法*做什么*。使用主动动词（例如，“计算...”，“检索...”）。 |
| `@param` | 是 (每个参数) | 描述单个参数。格式为 `@param 名称 - 描述`。对于复杂的对象参数，可以使用点表示法来记录嵌套属性（例如 `@param options.id`）。 |
| `@returns` | 是 | 描述方法的返回值。如果返回 `Promise`，请描述其解析后的值。对于 `void` 方法，可以写 `@returns {void}`。 |
| `@throws` | 否 | 描述该方法可能抛出的错误。在 `{}` 中指定错误类型，并说明抛出条件。 |
| `@example` | 否 | 展示如何调用此方法的特定代码示例。 |

---

#### **五、完整示例**

这是一个实现了本规范的完整接口示例：

```typescript
import { UserId } from './types';

/**
 * 代表系统中的用户配置，包含必要的身份和联系信息。
 *
 * @remarks
 * 该接口是处理用户相关操作的主要数据结构。它既用于从数据库检索用户数据，
 * 也用于通过API更新用户信息。
 *
 * @example
 * ```typescript
 * const user: IUserProfile = {
 *   userId: 'usr_12345',
 *   username: 'JaneDoe',
 *   email: 'jane.doe@example.com',
 *   isActive: true,
 *   lastLogin: new Date('2023-10-27T10:00:00Z'),
 * };
 * ```
 * @version 1.2.0
 * @see {@link IAuthSession}
 */
export interface IUserProfile {
  /**
   * 用户的唯一标识符。
   * @remarks 这是一个由系统生成的UUID。
   * @readonly
   */
  readonly userId: UserId;

  /**
   * 用户的公开显示名称。
   * @constraint {"type": "string", "minLength": 3, "maxLength": 50}
   * @example "JaneDoe"
   */
  username: string;

  /**
   * 用户的主要电子邮件地址，用于登录和接收通知。
   * @constraint {"type": "string", "format": "email"}
   */
  email: string;

  /**
   * 指示用户的账户当前是否处于活动状态。
   * @default true
   */
  isActive: boolean;

  /**
   * 用户上次成功登录的时间戳。
   * 如果用户从未登录过，则可能为undefined。
   */
  lastLogin?: Date;

  /**
   * 在验证后更新用户的电子邮件地址。
   *
   * @param newEmail - 要与该账户关联的新电子邮件地址。
   * @returns 一个Promise，如果更新成功，则解析为 `true`，否则解析为 `false`。
   * @throws {InvalidEmailError} - 如果提供的电子邮件格式无效，则抛出此错误。
   */
  updateEmail(newEmail: string): Promise<boolean>;
}
```
