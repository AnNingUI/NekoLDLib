# NekoLDLib

**NekoJS × LDLib2 桥接 mod** —— 在脚本里用 JSX 声明式地写 LDLib2 界面。

```tsx
// <gameDir>/nekojs/client_scripts/panel.tsx
function MachinePanel({ title, onStart }) {
  return (
    <panel class="panel_bg" width={180} height={100} gap-all={4} padding-all={6}>
      <label text={title} />
      <button text="Start" on-click={() => onStart()} />
      <button text="关闭" on-click={() => UI.close()} />
    </panel>
  )
}

globalThis.openMachinePanel = () =>
  UI.open(<MachinePanel title="Machine" onStart={() => console.info('started')} />, 'Machine')
```

> 数据绑定（`bind`）、服务端开 UI（`PlayerUI`）与 RPC 均已可用——见下文与文末开发状态。

## 为什么

- **LDLib2** 有一套完整的 UI 基建（Taffy flexbox 布局、LSS 样式表、UI RPC、字段同步），但界面只能从 Java 写——整合包作者想做面板就得改 Java、重新打包、重启。
- **NekoJS** 能跑脚本，但 GUI 能力只有手绘的 `PainterJS`——没有组件模型、没有布局、没有样式表。

本 mod 把两者接起来：JSX 写界面，白拿 LDLib2 的跨端机制。

## 架构要点

### 接入点是"消费"，不是"替换"

NekoJS 内置的 `nekojs/jsx-runtime` 源码注释写得很明确：

> 纯 JS 实现、不依赖宿主对象：元素是普通对象 `{ $$nekoJsx, type, key, props, children }`，
> **渲染/消费由用户侧代码（或后续的 painter/渲染绑定）自行处理**。

所以桥接就是那个"用户侧代码"——把 vnode 转成 LDLib2 元素树。不需要覆盖 JSX 运行时，
脚本侧也就不需要任何 pragma、import 或配置。

### 两种 vnode 形状都要认

NekoJS 的 JSX 运行时由配置 `jsxAutomaticRuntime` 切换，**默认是 `false`**，两种形状不同：

| 模式 | 产出 | 标签字段 |
|---|---|---|
| `false`（默认，classic） | `{ tag, props, children }` | `tag` |
| `true`（automatic） | `{ $$nekoJsx, type, key, props, children }` | `type` |

classic 产物**没有 `$$nekoJsx` 标记**。只认标记会让默认配置下整条链路静默失效——
渲染器通过 `JsxHost.typeOf/propsOf/childrenOf` 抹平这个差异，两种都支持。

顺带：NekoJS 生成的 `jsconfig.json` 也印证默认走 classic（`jsxFactory: "__nekoJsxFactory"`），
且 classic 工厂由 `define.js` 内置提供，无需自带模块。

### 一份 JSX 跑两端

`ModularUIContainerMenu` 在**服务端也会完整构建 UI 树**，因此同一份 JSX 会在两端各求值一次。
这带来一条必须遵守的约束：

> UI 工厂在服务端求值，闭包内不可捕获客户端专属对象。跨端数据请走同步值（阶段 2）。

LDLib2 自身的跨端行为也需要留意——`layout()` 与 `style()` 在服务端是 **no-op**：

```java
public UIElement layout(Consumer<LayoutStyle> layout) {
    if (LDLib2.isServer()) return this;   // ← 服务端直接跳过
    ...
}
```

而 `lss(属性名, 值)` **没有**这个早退。所以渲染器一律走 `lss()` 写样式与布局，
保证两端状态一致，也让 JS 侧的心智模型和写 LSS 一样（kebab-case 属性名）。

### 复用而非重造

| 需求 | 来源 |
|---|---|
| vnode 结构与 JSX 编译 | NekoJS 内置（`NekoJsxCompiler` + `nekojs/jsx-runtime`） |
| 开 UI（服务端） | LDLib2 `PlayerUIMenuType.register` + `openUI` |
| 菜单 / 同步 | LDLib2 `ModularUIContainerMenu` + `UISyncManager` |
| RPC | LDLib2 `RPCEventBuilder` + `UISyncManager` |
| 字段同步 | LDLib2 `SyncValue<T>` |
| 布局 / 样式 | LDLib2 Taffy + LSS 引擎（经 `UIElement.lss`） |

## 构建

### 依赖

| 依赖 | 版本 | 坐标/来源 |
|---|---|---|
| JDK | 25 | — |
| Minecraft | 26.1.2 | ModDevGradle |
| NeoForge | 26.1.2.100 | ModDevGradle |
| LDLib2 | 26.1.2.41 | `com.lowdragmc.ldlib2:ldlib2-neoforge-26.1`（maven.firstdark.dev） |
| NekoJS | 1.1.0-preview3 | **本地构建**（无 maven 发布） |
| Graal | `graalmc` ≥ 25.0.1 | `curse.maven:graal-1504336:8456810` |

两个坑值得记下来：

- **Graal 的包名是重定位过的**：不是原生的 `org.graalvm.polyglot`，而是
  `graal.graalvm.polyglot`。NekoJS 消费该依赖时就是这个形态，本 mod 沿用同一套 import。
- **LDLib2 的 artifact 名用 MC 短版本**：是 `ldlib2-neoforge-26.1`，不是
  `ldlib2-neoforge-26.1.2`（后者会把 `minecraft_version` 直接拼进去而解析失败）。

### 构建

NekoJS 目前**没有 maven 发布**，插件编译依赖其平台 fat jar（该 jar 已内嵌
`:common` + `:common-api` 全部类，所以单文件即可满足编译）。

```bash
# 1. 构建 NekoJS（在 NekoJS 仓库根目录）
gradlew :platforms:neoforge-26.1:build -x test

# 2. 构建本 mod
gradlew build

# 3. 开发环境运行
gradlew runClient
```

依赖缺失时 `build.gradle` 会打印可操作的提示，而不是抛一堆 unresolved symbol。

### 验证

`examples/` 是**开发期脚本的源码位置**（进版本库）；`run/nekojs/` 是运行时位置
（`run/` 在 `.gitignore` 里，混着 `mods/`、`logs/`、`saves/` 等生成物，不适合放源码）。

两者由 `syncExampleScripts` 任务自动同步——**所有 `run*` 任务执行前都会先跑一遍**，
所以改完 `examples/` 直接启动游戏即可：

```bash
gradlew runClientAuto          # 自动进入 gradle.properties 里 quickPlayWorld 指定的存档
```

反向同步没有做（两个方向都自动会互相覆盖、容易丢改动）。若你在游戏内编辑器里改脚本，
记得手动拷回 `examples/`。

| 脚本 | 触发方式 |
|---|---|
| `examples/client_scripts/nekoldlib_verify.tsx` | 加载时自动跑自检，结果进日志（搜 `[NekoLDLib/verify]`） |
| `examples/server_scripts/nekoldlib_p3_server.tsx` + `client_scripts/nekoldlib_p3_client.tsx` | 玩家进服自动开界面；服务端每 5 秒推值并自动发一次 S2C RPC |
| `examples/test_scripts/nekoldlib_smoke.tsx` | 游戏内 `/nekojs test` |

自检覆盖 36 项，重点是**诊断路径**而非只有 happy path：类型推断、只读绑定、
事件对象、不可绑定控件的报错、无效 type 的报错等。

### 跨端 RPC

同一个 id 在**两端的脚本里各声明一次** —— `send()` 发到对端，由**对端的 impl** 执行，
天然形成双向通道。这是三种脚本环境隔离下的既定协作方式。

```tsx
// server_scripts/machine.tsx
PlayerUI.register('mypack:machine', player => {
  const start = RPC.create('mypack:start')
    .i32()          // 一个 int 参数
    .retBool()      // 返回 boolean
    .fn(n => {
      started = true
      return n > 0
    })

  return (
    <panel>
      <button text="Start" on-click={() => start.send(3)} />
    </panel>
  )
})

// client_scripts/machine.tsx —— 同一个 id
PlayerUI.register('mypack:machine', player => {
  const start = RPC.create('mypack:start')
    .i32().retBool()
    .fn(n => console.info('服务端点了开始，n=' + n) ?? true)
  return <panel>…</panel>
})
```

**参数与返回类型各有同名方法**，按调用顺序对应 lambda 形参：

| 参数方法 | 返回类型方法 | 类型 |
|---|---|---|
| `bool()` | `retBool()` | boolean |
| `i32()` `i64()` `i16()` `i8()` | `retI32()` `retI64()` … | 整数 |
| `f32()` `f64()` | `retF32()` `retF64()` | 浮点 |
| `str()` `char_()` | `retStr()` `retChar()` | 文本 |
| `component()` | `retComponent()` | 文本组件 |
| `item()` `itemStack()` | `retItem()` `retItemStack()` | 物品（种类 / 堆） |
| `block()` `blockState()` | `retBlock()` `retBlockState()` | 方块 |
| `fluid()` `fluidStack()` | `retFluid()` `retFluidStack()` | 流体 |
| `entityType()` `blockEntityType()` | `retEntityType()` … | 注册表项 |
| `identifier()` `uuid()` `tag()` | `retIdentifier()` … | 标识 |
| `blockPos()` `chunkPos()` `aabb()` | `retBlockPos()` … | 坐标 |
| `dyeColor()` | `retDyeColor()` | 染料色 |

**为什么是方法名而不是 `args('int')`**：字符串写法在编辑器里没有约束，打错了要到运行时才报，
补全框里也列不出可用类型。方法名让**类型信息随方法名一起**：输入 `.` 就能看到全部可选项，
拼错立即报"没有这个方法"。

> **传"某一只具体的生物"用 `uuid()`**，不是 `entityType()`。后者是生物**种类**
> （注册表项），LDLib2 的同步层没有 `Entity` 实例的 accessor。传 UUID 后两端各自
> 按 id 查自己的实体。

**无参数 / 无返回**直接省略对应方法：

```tsx
const ping = RPC.create('mypack:ping').i32().fn(n => { … })
const notify = RPC.create('mypack:notify').fn(() => { … })
```

返回句柄：`{ id(), send(...args) }`。**界面关闭后 `send()` 返回 `false`** 而不是抛错。

**声明必须在界面工厂函数内**——RPC 是 per-界面的（注册到具体 `ModularUI` 的 syncManager）。
声明进待挂载队列，随下一次渲染挂到根元素，界面挂载时自动注册。

### JSX 属性速查

| 写法 | 含义 |
|---|---|
| `class="a b"` / `className` | 加 LSS class（空格分隔的字符串或数组） |
| `id="x"` | 元素 id |
| `text="..."` | 文本（Button / Label / TextElement；按字面量处理，不查翻译表） |
| `on-<事件名>={fn}` | 事件绑定，事件名同 LDLib2 `UIEvents`（`on-click` 是 `mouseClick` 的别名） |
| `bind={{ get, set, remote, type }}` | 数据绑定（见下） |
| `min={n}` / `max={n}` | **仅 Slider**：取值范围。不是 LSS 属性，走 `setRange` |
| `width={100}` 等 | 直接当 LSS 属性处理（camel 或 kebab 均可，数字会归一化） |
| `style={{...}}` / `layout={{...}}` | 同上，成组写法 |

> 属性分派规则：`class`/`id`/`style`/`layout`/`bind`/`min`/`max` 是特例，
> 其余**一律当 LSS 属性名处理**（走 `PropertyRegistry`，与写 `.lss` 文件同一套名字）。
> 所以拼错的属性名会被静默忽略而不是报错——写 `gap={4}` 请用 `gap-all` 或 `gap-row`/`gap-column`。

### 数据绑定

```tsx
let enabled = false
let speed = 1

<switch bind={{ get: () => enabled, set: v => { enabled = v } }} />
<slider bind={{ get: () => speed,   set: v => { speed = v }, min: 1 }} min={1} max={10} />
<label  bind={{ get: () => 'Speed ' + speed }} />        // 只读：省略 set
```

| 键 | 必需 | 说明 |
|---|---|---|
| `get` | 是 | 取值函数，返回值即控件当前值 |
| `set` | 否 | 收值函数。**省略即只读**（值只从服务端流向控件） |
| `remote` | 否 | 是否接受客户端→服务端。省略时按"有没有 `set`"推断 |
| `type` | 否 | 仅当控件值类型被泛型擦除时需要（如 `Selector`），平时由控件自动推断，**不要写** |

**类型由控件决定，不由脚本指定**——`Label` 的值类型是 `Component`（不是 `String`），
`Slider` 是 `Float`，`Switch` 是 `Boolean`。让脚本自由写 `type` 只会制造不匹配。

可绑定控件分红黑两档，能力不同：

| 控件 | 值类型 | 方向 |
|---|---|---|
| `Switch` / `Toggle` | Boolean | **双向** |
| `Slider` / `ProgressBar` | Float | **双向** |
| `TextField` | String | **双向** |
| `Label` | Component | **只读**（去掉 `set`） |
| `Selector` | T（泛型，需显式 `type`） | 双向 |

`Label` 只实现 `IBindable` 而没有 `registerValueListener`，所以它不会产生值变化——
给它写 `set` 会在渲染时报错并提示去掉。其余 `Button` / `TextArea` / `TabView` / `Dialog`
不支持绑定。

### 绑定在两种界面下的实现不同

| | 纯客户端界面（`UI.open`） | 服务端菜单（阶段 3） |
|---|---|---|
| 权威值 | 脚本变量 | 服务端 |
| 机制 | 每客户端 tick 搬运 getter；订阅元素变化回调 setter | LDLib2 `SimpleBinding` 跨端同步 |

纯客户端界面**必须**走本地搬运：LDLib2 的 `UISyncManager.tick()` 第一行就是
`if (modularUI.player == null) return;`，而 `UI.open` 建的 `ModularUI.of(ui)` 没有 player，
同步整条链路是惰性的。

值转换走 NekoJS 的类型适配器，因此 `ItemStack` / `Component` / `Block` / `Tag` 等
也可直接绑定。

日志里搜 `[NekoLDLib/verify]` 看结果。

开界面需要在游戏内调用（已注册为全局函数）：

```js
__nekoldlibOpenDemo()          // 自检脚本注册
```

## 开发状态

- [x] **阶段 0** —— 仓库骨架、双插件入口（`@LDLibPlugin` + `@RegisterNekoJSPlugin`）、依赖打通
- [x] **阶段 1** —— JSX → LDLib2 元素树（客户端闭环）
- [x] **阶段 2** —— 数据绑定（客户端本地双向 + 服务端菜单跨端同步）
- [x] **阶段 3** —— 服务端开 UI + 跨端双向绑定 + RPC（双向已验证）
- [x] **阶段 4** —— JSX 标签与属性的类型声明
- [ ] **阶段 5** —— 客户端 HUD / Screen 事件组

### 编辑器补全

NekoJS 自己的声明里**没有**任何 JSX 标签类型（`JSX.IntrinsicElements` 是空的），
所以写 `<panel` 不会有任何提示，属性名拼错也不报错——而 LDLib2 对未知属性是
**静默忽略**的，这类错误在运行时很难发现。

本 mod 通过 `ManualDeclarationCatalogEntry` 注入一段 `declare global` 声明，
NekoJS 的 probe 会把它落进 `.neko_probe/typescript/@manual/index.d.ts`
（各脚本目录的 `jsconfig.json` 已 include 该路径），于是编辑器里：

- `<panel` 能补全 31 个内置标签
- `width=` / `gap-all=` 等能补全 55 个 LSS 属性名
- `on-click=` 等能补全事件名
- `bind={{ get, set }}` 有字段说明

声明**从 `ElementRegistry` 与 `ElementRenderer` 的清单生成，不硬编码**——加标签后自动更新。
重新生成：游戏内 `/nekojs probe all`。

### 方法签名的类型也是具体的

`@package/com/tkisor/nekoldlib/` 下的声明里，参数与返回类型都是**具体类型而非 `$Value`**：

```typescript
// client/index.d.ts
open(arg0: $VNode, arg1: string): void;
render(arg0: $VNode): $UIElement;          // 返回类型可继续跳转到 LDLib2

// server/index.d.ts
interface $PanelFactory { create(arg0: $Player): $VNode; }
register(arg0: string, arg1: $PanelFactory): void;

// signal/index.d.ts
define(arg0: string, arg1: $RpcCallbacks$RpcImpl): $RpcCollector$Entry;
```

做到这一点靠两条 Graal/NekoJS 机制（都实测验证过）：

1. **JS 函数 → Java 函数式接口**：`NekoSharedHostAccess` 开了
   `allowAllImplementations`，所以 `player => vnode` 能直接传给 `PanelFactory`。
   实测单参、`List` 参数、**变参**三种 SAM 都能转换，且调用语义正确
   （Java 侧 `invoke("a","b")` 时 JS 收到 2 个独立参数，不是数组）。
2. **JS 对象 → 自定义类型**：注册 `JSTypeAdapter`，把 vnode 转成 `VNode`
   （见 `VNodeAdapter`）。这样 `UI.render(<panel/>)` 的参数类型也是具体的。

唯一还需要 `Value` 的地方是渲染器内部的 vnode 形状识别（`$$nekoJsx` / 任意 props 名 /
Symbol 形式的 Fragment）——那些没有对应的 Java 类型，且不是脚本面 API。

> **若 `@package` 下没有你的类**：`probe.toml` 的 `scan.extraIncludePackages`
> 默认只含 `java` / `com.tkisor.nekojs` / 平台的 MC 包。这是 NekoJS 与使用者之间的
> 配置，本 mod 不代改。JSX 标签补全走 `@manual`，不受此影响。

### 已验证 / 未验证

严格区分——不要把"实现了"当成"验证过"：

| 能力 | 状态 | 证据 |
|---|---|---|
| 客户端本地绑定（双向） | ✅ 已验证 | 自检 36 项全绿 |
| 服务端开 UI | ✅ 已验证 | `open 返回 true`，无断连 |
| 绑定跨端同步（S2C/C2S） | ✅ 已验证 | 服务端出现小数 `speed -> 4.24`（服务端逻辑只产生整数，小数只能来自客户端滑块） |
| RPC 客户端 → 服务端 | ✅ 已验证 | 服务端日志 `RPC echo 被对端调用，收到: 来自客户端` |
| 事件回调的 `e` 对象 | ✅ 已验证 | 自检 `PASS 回调收到 UIEvent` |
| RPC 服务端 → 客户端 | ✅ 已验证 | 客户端日志 `RPC ping 收到 n = 2` |
| JSX 类型声明 / 编辑器跳转 | ✅ 已验证 | IDE 里 `<panel` 可跳转与补全 |
| `ItemStack` / `Component` 等类型绑定 | ⚠️ **未验证** | 值转换走适配器，未在两端实测 |

### 已知限制

- **无 diff/patch**：每次渲染重建子树，尚未做增量更新
- **`Selector` 需显式 `type`**：其值类型是泛型，运行期被擦除，无法自动推断

### 阶段 2 已验证的行为

实测日志（拖动 Slider，逐帧回传真实区间值）：

```
slider -> 5.163230895996094
slider -> 5.531412124633789
slider -> 5.572320938110352
```

自检覆盖 14 项，重点是**诊断路径**而非只有 happy path：

| 检查项 | 验证的是 |
|---|---|
| `bind: Switch/Slider/TextField 类型推断` | 泛型实参解析（含继承链类型变量替换） |
| `bind: 只读绑定（无 set）` | `Label` 这类只可写元素 |
| `本地绑定：元素 setValue -> JS setter` | 控件 → JS 方向（同步可断言） |
| `不可绑定控件报错` | 报错含可绑控件清单 |
| `缺 get 报错` / `无效 type 报错` | 报错含可用类型名清单 |

### 阶段 2 已知限制

- **事件回调不接收事件对象**：`on-click={(e) => ...}` 里的 `e` 是 `undefined`
- **无 diff/patch**：每次渲染重建子树，尚未做增量更新
- **跨端同步未验证**：`SERVER_MENU` 分支已接好但还没有真实菜单调用它，
  需要能开集成服务器双端连接才能验证（阶段 3）

## 许可

LGPL-3.0 —— 与两个上游（LDLib2、NekoJS）一致。
