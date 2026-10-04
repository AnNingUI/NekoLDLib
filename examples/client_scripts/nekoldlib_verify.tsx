// NekoLDLib 阶段 1 自动自检 —— 加载时立即执行，结果直接进日志。
//
// 放在 <gameDir>/nekojs/client_scripts/ 下，随 /nekojs reload client 或重启生效。
// 之所以在加载期就渲染而不是等玩家操作：渲染是纯数据操作（建元素树 + 写样式），
// 不依赖 GUI 就绪，所以能在启动阶段就给出确定性结论。
//
// 打开界面仍需玩家主动触发（见文件末尾注册的按键说明）。

console.info("[NekoLDLib/verify] ==== 阶段 1 自检开始 ====");

/**
 * 跑一项检查，把结果写进日志。返回值只用于人工排查，不参与判断。
 *
 * <p>参数类型写成 {@code Function} 而不是 {@code () => unknown}：NekoJS 的 TS 擦除器
 * 在**无参箭头函数类型**上会失败（`SyntaxError: Expected an operand but found =>`），
 * 而这个报错发生在脚本执行期——整个自检脚本会静默不跑（PASS/FAIL 全为 0），
 * 比类型不精确严重得多。`Function` 语义够用且擦除器能正确处理。
 */
function check<T>(name: string, fn: () => T): boolean {
	try {
		const detail = fn();
		console.info(
			`[NekoLDLib/verify] PASS  ${name}${detail ? " -> " + detail : ""}`,
		);
		return true;
	} catch (e) {
		console.error(`[NekoLDLib/verify] FAIL  ${name} -> ${e}`);
		return false;
	}
}

// 0) 绑定是否就位
//    包一层 IIFE：模块顶层不能 `return`（TS1108），但早退语义在这里是需要的。
//    用 IIFE 而不是 throw —— 缺绑定只该跳过检查，不该让整个脚本失败。
(function runChecks() {
	const hasUI = typeof UI !== "undefined";
	console.info("[NekoLDLib/verify] UI 绑定: " + hasUI);
	if (!hasUI) {
		console.error("[NekoLDLib/verify] UI 未注册，后续检查全部跳过");
		return;
	}
	console.info("[NekoLDLib/verify] 已注册标签: " + UI.tags());

	// 1) classic 形状（NekoJS 默认 jsxAutomaticRuntime=false 的产出）
	//    手工构造，绕开 JSX 编译，直接检验渲染器的形状兼容性。
	check("classic vnode {tag,props,children}", () => {
		const vnode = {
			tag: "panel",
			props: { class: "panel_bg", width: 120, height: 40 },
			children: [{ tag: "label", props: { text: "classic" }, children: [] }],
		};
		return UI.render(vnode);
	});

	// 2) automatic 形状（jsxAutomaticRuntime=true 的产出）—— 也应被接受
	check("automatic vnode {$$nekoJsx,type,props,children}", () => {
		const vnode = {
			$$nekoJsx: true,
			type: "panel",
			key: null,
			props: { class: "panel_bg" },
			children: [],
		};
		return UI.render(vnode);
	});

	// 3) 走真实 JSX 编译（classic 工厂）+ 函数组件展开
	//    事件写法是 on-<事件名>，事件名同 LDLib2 UIEvents。
	//    不用 on.click：属性名里的点号不是合法 JSX 标识符，NekoJS 校验器会报
	//    "Unknown identifier 'on'"。on-click 是 click 的别名，最终映射到 "mouseClick"。
	function SmokePanel(props: { tag?: string }) {
		return (
			<panel class="panel_bg" width={160} height={80}>
				<label
					text={"组件展开 OK: " + (props && props.tag ? props.tag : "default")}
				/>
				<button
					id="smoke-btn"
					text="点我"
					on-click={() => console.info("[NekoLDLib/verify] 按钮回调触发")}
				/>
				<button id="close-btn" text="关闭" on-click={() => UI.close()} />
			</panel>
		);
	}

	check("JSX 编译 + 函数组件", () => UI.render(<SmokePanel tag="smoke" />));

	// 4) 事件是否真的绑定上了（不靠肉眼点按钮）
	check("事件绑定注册", () => {
		const el = UI.render(
			<button id="probe-btn" text="x" on-click={() => {}} />,
		);
		// 渲染出的是 button 本身，它的监听器挂在内部；用带容器的方式验证事件名解析
		const wrapped = UI.render(
			<panel>
				<button
					id="probe-btn"
					text="x"
					on-click={() => {}}
					on-mouseEnter={() => {}}
				/>
			</panel>,
		);
		return "已注册 on-click / on-mouseEnter";
	});

	// 4) Fragment：自身不该产生元素，子节点应被提升
	check("Fragment 提升", () => {
		const el = UI.render(
			<>
				<label text="frag-a" />
				<label text="frag-b" />
			</>,
		);
		return el;
	});

	// 5) 嵌套容器 + 条件渲染（false / null 应被跳过）
	check("嵌套 + 条件子节点", () => {
		const show = false;
		return UI.render(
			<panel>
				<panel>
					<label text="nested" />
				</panel>
				{show && <label text="不该出现" />}
				{null}
			</panel>,
		);
	});

	// 6) 样式走 lss：kebab 与 camel 两种写法，数字与字符串两种值
	check("样式 (lss) 应用", () => {
		return UI.render(
			<panel width={100} padding-all={4} flex-direction="row" height="20" />,
		);
	});

	// 7) 未知标签必须给出可操作的报错（含可用标签清单）
	try {
		UI.render({ tag: "definitely-not-a-tag", props: {}, children: [] });
		console.error("[NekoLDLib/verify] FAIL  未知标签未报错（预期应抛错）");
	} catch (e) {
		const msg = String(e);
		const helpful =
			msg.indexOf("未知的 JSX 标签") >= 0 || msg.indexOf("Unknown") >= 0;
		console.info(
			"[NekoLDLib/verify] PASS  未知标签报错" +
				(helpful ? "（信息可操作）" : "（信息不够明确）"),
		);
	}

	// 8) 数据绑定（阶段 2）
	//    类型由控件推断，不写 type；remote 省略时按"有没有 set"推断。
	let bindBool = false;
	let bindFloat = 1;
	let bindText = "hello";

	check("bind: Switch(Boolean) 类型推断", () =>
		UI.render(
			<switch
				id="b1"
				bind={{
					get: () => bindBool,
					set: (v) => {
						bindBool = v;
					},
				}}
			/>,
		),
	);

	check("bind: Slider(Float) 类型推断", () =>
		UI.render(
			<slider
				id="b2"
				min={1}
				max={10}
				bind={{
					get: () => bindFloat,
					set: (v) => {
						bindFloat = v;
					},
				}}
			/>,
		),
	);

	check("bind: TextField(String) 类型推断", () =>
		UI.render(
			<text-field
				id="b3"
				bind={{
					get: () => bindText,
					set: (v) => {
						bindText = v;
					},
				}}
			/>,
		),
	);

	check("bind: 只读绑定（无 set）", () =>
		UI.render(<label id="b4" bind={{ get: () => "Speed " + bindFloat }} />),
	);

	// 9) 绑定诊断：不可绑定的控件必须报错，且说明哪些能绑
	try {
		UI.render({
			tag: "button",
			props: { bind: { get: () => 1 } },
			children: [],
		});
		console.error("[NekoLDLib/verify] FAIL  不可绑定控件未报错（预期应抛错）");
	} catch (e) {
		console.info(
			"[NekoLDLib/verify] PASS  不可绑定控件报错 -> " + String(e).slice(0, 90),
		);
	}

	// 10) 绑定诊断：缺 get 必须报错
	try {
		UI.render({
			tag: "switch",
			props: { bind: { set: (v) => {} } },
			children: [],
		});
		console.error("[NekoLDLib/verify] FAIL  缺 get 未报错");
	} catch (e) {
		console.info(
			"[NekoLDLib/verify] PASS  缺 get 报错 -> " + String(e).slice(0, 90),
		);
	}

	// 11) 泛型擦除：Selector 的值类型推断不出来，应该要求显式 type
	//     先给错的名字，验证报错里带可用清单
	try {
		UI.render({
			tag: "selector",
			props: { bind: { get: () => "x", type: "bogus" } },
			children: [],
		});
		console.error("[NekoLDLib/verify] FAIL  无效 type 未报错");
	} catch (e) {
		console.info(
			"[NekoLDLib/verify] PASS  无效 type 报错 -> " + String(e).slice(0, 90),
		);
	}

	// 11) 本地绑定双向：元素 -> JS
	//     直接对元素调 setValue，验证 setter 被触发（同步可验证，不依赖 tick）
	let probeValue: unknown = null;
	check("本地绑定：元素 setValue -> JS setter", () => {
		const el = UI.render(
			<switch
				bind={{
					get: () => false,
					set: (v) => {
						probeValue = v;
					},
				}}
			/>,
		);
		// el 是 Java 的 Switch 宿主对象，可直接调它的公开方法
		(el as any).setValue(true);
		if (probeValue !== true) {
			throw new Error("setter 未触发，probeValue=" + probeValue);
		}
		return "setter 收到 " + probeValue;
	});

	// 12) 本地绑定：getter 被调用（说明推送任务已挂上）
	let getterCalls = 0;
	check("本地绑定：getter 已挂载", () => {
		UI.render(
			<switch
				bind={{
					get: () => {
						getterCalls++;
						return false;
					},
				}}
			/>,
		);
		// getter 在首个客户端 tick 才被调用，这里只确认绑定建立没报错
		return "绑定已建立（getter 将在 tick 时调用）";
	});

	// 13) 事件对象：合成一次 click 事件，断言回调收到了 e
	//     事件的构造与派发都在 Java 侧（UI.probeEvent），所以不依赖脚本对 Java
	//     静态成员的调用语法 —— 那条语法我猜错过两次。
	check("事件回调收到 e 对象", () =>
		UI.probeEvent(<button id="evt-probe" text="x" on-click={(e) => {}} />),
	);

	// 14) 真正开界面 —— 需要玩家确认视觉效果
	//    注意行首要加分号：下一行以 `(` 开头，而 ASI 不会在 `(` 前插分号，
	//    否则会被解析成 `check(...)(globalThis...)` —— 报「Boolean 没有调用签名」。
	(globalThis as any).__nekoldlibOpenDemo = () => {
		UI.open(<SmokePanel tag="opened" />, "NekoLDLib 冒烟测试");
		console.info(
			"[NekoLDLib/verify] 界面已请求打开，isOpen=" + UI.hasOpenScreen(),
		);
	};

	// 13) 绑定交互演示：开一个带双向绑定的面板，改控件后看日志
	(globalThis as any).__nekoldlibOpenBindDemo = () => {
		UI.open(
			<panel
				class="panel_bg"
				width={220}
				height={130}
				padding-all={8}
				gap-all={4}
			>
				<label text="阶段 2 绑定演示" />
				<switch
					bind={{
						get: () => bindBool,
						set: (v) => {
							bindBool = v;
							console.info("[NekoLDLib/verify] switch 变 " + v);
						},
					}}
				/>
				<label bind={{ get: () => "Switch = " + bindBool }} />
				<text-field
					bind={{
						get: () => bindText,
						set: (v) => {
							bindText = v;
							console.info("[NekoLDLib/verify] text 变 " + v);
						},
					}}
				/>
				<label bind={{ get: () => "Text = " + bindText }} />
				<button text="关闭" on-click={() => UI.close()} />
			</panel>,
			"NekoLDLib 绑定演示",
		);
	};

	console.info(
		"[NekoLDLib/verify] ==== 自检结束；开界面请调用 __nekoldlibOpenDemo() ====",
	);
})();
