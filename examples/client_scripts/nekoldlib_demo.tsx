// NekoLDLib 阶段 2 交互验证 —— 加载后自动开界面，用于肉眼确认响应式。
//
// 放在 <gameDir>/nekojs/client_scripts/ 下，重启客户端或 /nekojs reload client 生效。
//
// 验证三件事：
//   1) 本地响应：外部改值 -> 控件刷新（SyncValue.update() 每 tick 调 getter）
//   2) 双向：改控件 -> JS 变量跟着变（set 回调）
//   3) reload 清理：/nekojs reload client 后界面应自动关闭，且再点按钮不再报
//      "The Context is already closed"

console.info('[NekoLDLib/demo2] 加载')

// ---- 状态：全部放在顶层，便于外部函数改写 ----
let enabled = false
let speed = 1
let name = 'hello'
let ticks = 0

// 让 set 回调可见：值由控件回写时打日志
function onSwitch(v) {
  enabled = v
  console.info('[NekoLDLib/demo2] switch -> ' + v)
}
function onText(v) {
  name = v
  console.info('[NekoLDLib/demo2] text -> ' + v)
}
function onSlider(v) {
  speed = v
  console.info('[NekoLDLib/demo2] slider -> ' + v)
}

function Panel() {
  return (
    <panel class="panel_bg" width={240} height={170} padding-all={8} gap-all={4}>
      <label text="阶段 2 响应式演示" />

      <label text="--- Switch（双向）---" />
      <switch bind={{ get: () => enabled, set: onSwitch }} />
      <label bind={{ get: () => 'enabled = ' + enabled }} />

      <label text="--- TextField（双向）---" />
      <text-field bind={{ get: () => name, set: onText }} />
      <label bind={{ get: () => 'name = ' + name }} />

      <label text="--- Slider（双向）---" />
      <slider min={1} max={10} bind={{ get: () => speed, set: onSlider }} />
      <label bind={{ get: () => 'speed = ' + speed }} />

      <button text="关闭" on-click={() => UI.close()} />
    </panel>
  )
}

// ---- 外部改值：验证"JS 改 -> 控件刷新" ----
(globalThis as any).__nekoldlibSetEnabled = v => {
  enabled = v
  console.info('[NekoLDLib/demo2] 外部设置 enabled = ' + v + '（控件应在一个 tick 内跟上）')
}
(globalThis as any).__nekoldlibSetSpeed = v => {
  speed = v
  console.info('[NekoLDLib/demo2] 外部设置 speed = ' + v)
}

// 每 3 秒自增一次 speed，用来观察"无需交互也会刷新"
(globalThis as any).__nekoldlibStartTicker = () => {
  setInterval(() => {
    speed = (speed % 10) + 1
    console.info('[NekoLDLib/demo2] tick speed = ' + speed)
  }, 3000)
}

try {
  UI.open(<Panel />, 'NekoLDLib 响应式演示')
  console.info('[NekoLDLib/demo2] 已请求打开')
} catch (e) {
  console.error('[NekoLDLib/demo2] 打开失败: ' + e)
}
