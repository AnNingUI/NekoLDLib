// NekoLDLib 阶段 1 冒烟测试。
//
// 由 /nekojs test 触发（ScriptType.TEST）。这一份把整条链路都走一遍，
// 失败时的报错能直接指出断在哪一环。
//
// 注意：NekoJS 默认用 classic JSX runtime（jsxAutomaticRuntime=false），
// 产出 { tag, props, children }；若在 config 里开了 automatic，则产出
// { $$nekoJsx, type, key, props, children }。渲染器两种都认。

console.info('[NekoLDLib] ==== 阶段 1 冒烟测试开始 ====')

// 1) 脚本环境能看到 UI 绑定吗？
console.info('[NekoLDLib] UI 绑定存在: ' + (typeof UI !== 'undefined'))

// 2) 渲染器认识哪些标签
console.info('[NekoLDLib] 可用标签: ' + UI.tags())

// 3) 手工构造一个 vnode，验证"渲染"这一步本身（不开窗）。
//    手工构造可以绕开 JSX 编译，直接检验渲染器的形状兼容性。
try {
  const manual = {
    tag: 'panel',
    props: { class: 'panel_bg', width: 120, height: 40 },
    children: [{ tag: 'label', props: { text: '手工 vnode 渲染成功' }, children: [] }],
  }
  const el = UI.render(manual)
  console.info('[NekoLDLib] 手工 vnode 渲染: OK -> ' + el)
} catch (e) {
  console.error('[NekoLDLib] 手工 vnode 渲染失败: ' + e)
}

// 4) 走真实的 JSX 编译路径（函数组件 + 事件 + 嵌套）
//    事件用 on-<事件名>，同 LDLib2 UIEvents；on-click 是 mouseClick 的别名。
function SmokePanel() {
  return (
    <panel class="panel_bg" width={160} height={80}>
      <label text="NekoLDLib 阶段 1" />
      <button text="点击测试" on-click={() => console.info('[NekoLDLib] 按钮回调触发')} />
      <button text="关闭" on-click={() => UI.close()} />
    </panel>
  )
}

try {
  const jsxEl = UI.render(<SmokePanel />)
  console.info('[NekoLDLib] JSX 渲染: OK -> ' + jsxEl)
} catch (e) {
  console.error('[NekoLDLib] JSX 渲染失败: ' + e)
}

// 5) 真正开一个界面（人工肉眼确认）
try {
  UI.open(<SmokePanel />, 'NekoLDLib 冒烟测试')
  console.info('[NekoLDLib] 界面已打开，isOpen=' + UI.hasOpenScreen())
} catch (e) {
  console.error('[NekoLDLib] 开界面失败: ' + e)
}

console.info('[NekoLDLib] ==== 冒烟测试结束 ====')
