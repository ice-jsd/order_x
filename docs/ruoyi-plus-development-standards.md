# RuoYi-Plus 前后端开发基线

## 1. 目标

本文档基于以下两套代码仓库整理，用于约束后续功能开发的实现方式、目录结构、命名规范和接口约定：

- 前端：`D:\workspace\codex\qiangpiao\ruoyi-plus-soybean`
- 后端：`D:\workspace\codex\qiangpiao\RuoYi-Vue-Plus`

后续新需求默认遵循本文档。若有偏离，需先说明原因，再决定是否破例。

## 2. 技术基线

### 2.1 前端基线

- 框架：`Vue 3 + TypeScript + Vite`
- UI：`Naive UI`
- 状态管理：`Pinia`
- 路由：`Vue Router`
- 样式体系：`UnoCSS + SCSS`
- 工程化：`pnpm workspace`
- 代码检查：`ESLint + Oxlint + Oxfmt`

从 `package.json`、`vite.config.ts`、`eslint.config.js` 和 `src` 目录结构看，前端是标准的组合式 API、类型优先、模块化后台工程。

### 2.2 后端基线

- 语言：`Java 17`
- 框架：`Spring Boot 3`
- ORM：`MyBatis-Plus`
- 权限：`Sa-Token`
- 缓存/分布式能力：`Redisson`
- 文档：`SpringDoc`
- 工具链：`MapStruct-Plus`、`Hutool`
- 构建方式：`Maven 多模块`

从 `pom.xml` 和模块目录看，后端是典型的 RuoYi-Vue-Plus 分层式企业后台架构。

## 3. 前端代码框架

### 3.1 目录组织

前端主代码位于 `src`，按“基础能力 + 业务模块”拆分：

- `src/views`：业务页面
- `src/service`：请求封装与接口定义
- `src/store`：Pinia 状态
- `src/router`：路由注册与守卫
- `src/hooks`：可复用业务 hooks
- `src/components`：通用组件
- `src/utils`：工具方法
- `src/styles`、`src/theme`：样式和主题
- `src/typings`：类型补充

页面级业务主要放在 `src/views/<模块>/<页面>/index.vue`，这是后续新增页面的默认落点。

### 3.2 页面实现套路

以 `src/views/system/user/index.vue` 为代表，页面实现遵循以下模式：

1. 使用 `<script setup lang="tsx">`
2. 页面只负责：
   - 组合查询条件
   - 调用 hooks
   - 渲染表格、弹窗、抽屉
   - 绑定权限和事件
3. 通用表格行为复用现有 hooks，不直接手写分页、列配置、批量操作状态

常用页面组成通常是：

- 查询表单
- 表格头部操作区
- 批量选择提示区
- `NDataTable`
- 新增/编辑抽屉
- 删除/状态切换确认

### 3.3 请求层规范

接口定义位于 `src/service/api/<模块>/<实体>.ts`，例如 `src/service/api/system/user.ts`。

约定如下：

- 函数名使用 `fetch + 动作 + 资源` 形式
  - 例如：`fetchGetUserList`
  - 例如：`fetchCreateUser`
  - 例如：`fetchUpdateUserStatus`
- 所有请求统一通过 `@/service/request` 导出的 `request` 实例发起
- 不在页面里直接写 axios
- 每个接口都写明确的返回泛型
- URL 保持与后端 Controller 路由一一对应

请求层统一能力已在 `src/service/request/index.ts` 中封装，包括：

- token 自动注入
- 重复提交防护
- 请求加密开关
- token 过期续签
- 后端错误码统一处理

后续新增接口必须接入这一层，不允许绕过。

### 3.4 表格与分页规范

表格能力统一来自 `src/hooks/common/table.ts`，后续开发优先复用：

- `useNaiveTable`
- `useNaivePaginatedTable`
- `useNaiveTreeTable`
- `useTableOperate`
- `useTreeTableOperate`

关键约定：

- 分页接口响应默认兼容后端 `rows + total + pageNum`
- 表格分页、滚动宽度、列显隐、批量选择统一由 hooks 管理
- 删除成功、批量删除成功后的提示与刷新逻辑复用 hook 回调

不要在每个页面重复手写：

- `page`
- `pageSize`
- `itemCount`
- `checkedRowKeys`
- 抽屉开关状态

### 3.5 路由与状态管理规范

路由在 `src/router/index.ts` 统一创建，守卫通过 `createRouterGuard(router)` 注册。

规范如下：

- 所有菜单页必须接入统一路由系统
- 鉴权、菜单加载、页面跳转逻辑走既有守卫
- 不要在页面中自行写分散式鉴权流程

状态管理在 `src/store/index.ts` 初始化 Pinia，说明：

- 全局状态放 Pinia
- 页面临时状态优先放组件或 hook 内部
- 不要把一次性表单状态、弹窗状态随意塞进全局 store

### 3.6 前端命名与风格要求

- 页面目录：`src/views/<module>/<entity>/index.vue`
- 接口文件：`src/service/api/<module>/<entity>.ts`
- hook 文件：按能力归类，例如 `src/hooks/common/*.ts`
- 组件名：PascalCase
- 接口方法名：`fetchXxx`
- 组合式方法名：`useXxx`
- 布尔状态名：`isXxx`、`hasXxx`
- 新增页面优先使用 TS 类型，不允许大面积 `any`

风格要求：

- 先复用现有业务组件、hooks、字典、权限判断能力
- 与现有页面保持同一信息密度和交互结构
- 后台页面优先“稳态、可维护、可批量操作”，不要写成展示型官网页面

## 4. 后端代码框架

### 4.1 模块结构

后端为 Maven 多模块：

- `ruoyi-admin`：启动入口
- `ruoyi-common`：公共能力
- `ruoyi-extend`：扩展能力
- `ruoyi-modules`：业务模块

实际业务开发主要落在 `ruoyi-modules/<业务模块>`。

以 `ruoyi-modules/ruoyi-system` 为例，标准结构为：

- `controller`
- `domain`
- `mapper`
- `service`
- `service/impl`
- `listener`
- `runner`

后续新增业务模块或新实体时，优先沿用这个结构。

### 4.2 分层职责

#### Controller 层

以 `SysUserController.java` 为代表，职责应保持轻量：

- 接收参数
- 权限校验
- 调用 service
- 返回统一响应结构

Controller 中可以有：

- `@SaCheckPermission`
- `@Log`
- `@RepeatSubmit`
- `@Validated`
- `@RequestMapping` / `@GetMapping` / `@PostMapping`

Controller 中不应堆积：

- 大段业务判断
- 复杂事务逻辑
- 大量 mapper 直接访问

#### Service 层

以 `SysUserServiceImpl.java` 为代表，业务逻辑主要放在 Service：

- 业务校验
- 事务控制
- 查询条件组装
- 关联表写入
- 领域规则封装
- 缓存更新

复杂逻辑优先拆为私有方法，不要把一个方法写成纯过程脚本。

#### Mapper 层

以 `SysUserMapper.java` 为代表：

- 继承 `BaseMapperPlus<Entity, Vo>`
- 简单 CRUD 复用基础能力
- 复杂查询通过 wrapper 或 mapper 方法扩展
- 数据权限通过注解控制

Mapper 层只负责数据访问，不写业务流程编排。

### 4.3 实体、BO、VO 规范

RuoYi-Vue-Plus 的实体分层非常明确，后续必须继续沿用。

#### Entity

示例：`SysUser`

特点：

- 对应数据库表
- 使用 MyBatis-Plus 注解
- 可继承统一基类，如租户实体、审计实体

#### BO

示例：`SysUserBo`

用途：

- 接收前端入参
- 承载查询条件和写入参数
- 放参数校验注解

约定：

- 类名以 `Bo` 结尾
- 可继承 `BaseEntity`
- 使用 `@AutoMapper(target = Entity.class, reverseConvertGenerate = false)`
- 参数校验写在 BO 上
- XSS、防注入等输入约束也放这里

#### VO

示例：`SysUserVo`

用途：

- 返回前端展示数据
- 承载脱敏、翻译、补充展示字段

约定：

- 类名以 `Vo` 结尾
- 使用 `@AutoMapper(target = Entity.class)`
- 脱敏用 `@Sensitive`
- 字典/名称翻译用 `@Translation`

结论：

- 前端请求不要直接绑定 Entity
- Controller 不直接收 Entity
- 对外返回不要裸露 Entity

### 4.4 分页与统一响应规范

后端统一分页参数使用 `PageQuery`，统一分页返回使用 `TableDataInfo<T>`。

规范如下：

- 列表接口：`EntityBo + PageQuery -> TableDataInfo<EntityVo>`
- 非分页接口：返回 `R<T>`
- 成功失败统一通过 `R.ok()`、`R.fail()`、`toAjax()` 等方式返回

这与前端 `useNaivePaginatedTable` 的 `rows + total + pageNum` 解析是配套的，后续不能随意改变。

### 4.5 查询与写入风格

从 `SysUserServiceImpl` 可总结出默认写法：

- 查询条件优先使用 `Wrappers.lambdaQuery()` 或 `Wrappers.query()`
- 条件拼接采用链式表达
- 条件判断写在查询语句里，不要分散到多层 if
- 数据转换优先使用 `MapstructUtils.convert`
- 批量关联写入封装成独立私有方法
- 修改/删除涉及多表时必须显式事务

建议遵循：

- 简单查询：LambdaQueryWrapper
- 联表或导出：QueryWrapper/Mapper 自定义方法
- 新增/修改：BO -> Entity 转换后落库
- 批量逻辑：先校验，再组装列表，再批量写入

### 4.6 权限、审计、安全规范

后端已有完整基础设施，后续开发必须接入，不要自行发明一套。

必须优先复用：

- 权限控制：`@SaCheckPermission`
- 操作日志：`@Log`
- 防重复提交：`@RepeatSubmit`
- 数据权限：`@DataPermission`
- 参数校验：`@Validated`、`jakarta.validation`
- 加密接口：按需使用 `@ApiEncrypt`

默认原则：

- 管理后台写操作接口都要评估是否加 `@Log`
- 防重复提交的新增、修改、批量操作要优先加 `@RepeatSubmit`
- 涉及菜单按钮权限的接口必须声明权限编码

## 5. 前后端对齐规则

### 5.1 URL 与资源命名

前后端接口命名必须保持资源化一致：

- 后端：`/system/user/list`
- 前端：`fetchGetUserList`
- 后端：`POST /system/user`
- 前端：`fetchCreateUser`

要求：

- 一个前端接口函数对应一个明确后端接口
- 不要在前端拼接隐藏协议
- 不要在后端返回前端专属的非通用结构

### 5.2 列表页标准交互

后续后台列表页默认按这套实现：

1. 查询条件
2. 操作区
3. 数据表格
4. 分页
5. 新增/编辑抽屉或弹窗
6. 删除/启停/状态切换

前端用通用表格 hook，后端用 `PageQuery + TableDataInfo`。

### 5.3 权限编码联动

页面按钮显示和接口权限都应围绕同一套编码：

- 前端：`hasAuth('system:user:edit')`
- 后端：`@SaCheckPermission("system:user:edit")`

后续功能开发时，前后端权限编码必须同步设计，同步落库，同步接入。

## 6. 后续开发必须遵守的实现要求

### 6.1 前端要求

- 新页面按 `views/service/api/hooks` 分层组织
- 页面尽量使用 `<script setup lang="tsx">`
- 接口统一接到 `src/service/api`
- 列表页优先复用现有表格 hooks
- 统一走现有请求封装，不直接写裸 axios
- 权限控制、字典、导出、下载优先复用现有公共能力

### 6.2 后端要求

- 新实体至少补齐 `Entity + Bo + Vo + Controller + Service + ServiceImpl + Mapper`
- Controller 只做薄层编排
- 业务规则沉到 Service
- 查询条件统一 wrapper 化
- 列表接口统一 `PageQuery + TableDataInfo`
- 普通接口统一 `R<T>`
- 写操作默认评估日志、权限、幂等、事务

### 6.3 联调要求

- 前端类型命名与后端字段含义保持一致
- 分页字段固定兼容 `rows/total/pageNum`
- 状态字段、字典字段、权限编码提前统一
- 新增模块时，先定义接口与数据结构，再分别实现前后端

## 7. 推荐开发流程

后续新增功能建议按以下顺序推进：

1. 先确定业务模块归属和菜单归属
2. 先设计后端实体、BO、VO、接口路径、权限编码
3. 再定义前端 API 文件和类型
4. 先打通列表、详情、增改、删除闭环
5. 再补批量操作、导出、状态流转、日志等增强能力

## 8. 不建议的做法

- 前端页面直接调用 axios
- 前端每个页面自己维护一套分页协议
- Controller 直接操作复杂 Mapper 逻辑
- 入参、出参直接复用 Entity
- 前后端权限编码不一致
- 列表接口返回结构随意变动
- 新模块不接入日志、幂等、权限基础设施

## 9. 本文档的实际依据

本文档主要基于以下文件整理：

- 前端
  - `ruoyi-plus-soybean/package.json`
  - `ruoyi-plus-soybean/vite.config.ts`
  - `ruoyi-plus-soybean/eslint.config.js`
  - `ruoyi-plus-soybean/src/router/index.ts`
  - `ruoyi-plus-soybean/src/store/index.ts`
  - `ruoyi-plus-soybean/src/service/request/index.ts`
  - `ruoyi-plus-soybean/src/service/api/system/user.ts`
  - `ruoyi-plus-soybean/src/hooks/common/table.ts`
  - `ruoyi-plus-soybean/src/views/system/user/index.vue`
- 后端
  - `RuoYi-Vue-Plus/pom.xml`
  - `RuoYi-Vue-Plus/ruoyi-admin/src/main/java/org/dromara/DromaraApplication.java`
  - `RuoYi-Vue-Plus/ruoyi-common/ruoyi-common-mybatis/src/main/java/org/dromara/common/mybatis/core/page/PageQuery.java`
  - `RuoYi-Vue-Plus/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/controller/system/SysUserController.java`
  - `RuoYi-Vue-Plus/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/service/impl/SysUserServiceImpl.java`
  - `RuoYi-Vue-Plus/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/mapper/SysUserMapper.java`
  - `RuoYi-Vue-Plus/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/domain/bo/SysUserBo.java`
  - `RuoYi-Vue-Plus/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/domain/vo/SysUserVo.java`

---

后续如果基于这两套代码继续开发，默认以本文档为实现基线。
