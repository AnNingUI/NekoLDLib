// NekoLDLib 阶段 3 验证 —— 服务端开 UI + 跨端绑定 + RPC。
//
// 放到 server_scripts/ 下。

console.info('[NekoLDLib/p3-server] 加载')

// 权威状态在服务端。跨端同步由 bind 走 LDLib2 的 SimpleBinding。
let speed = 3
let enabled = false

// 服务端侧的 RPC 句柄，供下面的自动探针使用（声明发生在工厂内，句柄要存出来）
let pingHandle = null

PlayerUI.register('nekoldlib:demo', player => {
  // RPC 是 per-界面的，必须在工厂内声明。
  // 同一个 id 在两端各声明一次：send() 发到对端、由对端的 impl 执行。
  const echo = RPC.create('nekoldlib:echo')
    .schema({ msg: 'string' })
    .returns('string')
    .fn(({ msg }) => {
      console.info('[NekoLDLib/p3-server] RPC echo 被对端调用，收到: ' + msg)
      return '服务端应答:' + msg
    })

  // 由服务端主动发给客户端，验证反方向
  const ping = RPC.create('nekoldlib:ping')
    .schema({ n: 'int' })
    .returns('void')
    .fn(({ n }) => {
      console.info('[NekoLDLib/p3-server] ping 的服务端 impl 被执行了 —— 说明这是客户端发来的')
    })
  pingHandle = ping

  return (
    <panel class="panel_bg" width={240} height={170} padding-all={8} gap-all={4}>
      <label text="阶段 3：服务端开 UI" />
      <label bind={{ get: () => 'speed = ' + speed }} />
      <slider min={1} max={10} bind={{ get: () => speed, set: v => { speed = v } }} />
      <label bind={{ get: () => 'enabled = ' + enabled }} />
      <switch bind={{ get: () => enabled, set: v => { enabled = v } }} />
      <button
        text="手动发 RPC 到客户端"
        on-click={() => console.info('[NekoLDLib/p3-server] ping.send 返回 ' + ping.send({ n: 42 }))}
      />
    </panel>
  )
})

console.info('[NekoLDLib/p3-server] 已注册: ' + PlayerUI.ids())

// 服务端每 5 秒推一次值 —— 用来观察"服务端改 -> 客户端刷新"
// 同时自动发一次 S2C RPC，无需人工点击即可验证反方向是否打通。
let tickCount = 0
setInterval(() => {
  speed = (speed % 10) + 1
  console.info('[NekoLDLib/p3-server] speed -> ' + speed)

  // 界面打开后（句柄已挂载）每两轮发一次 S2C RPC
  tickCount++
  if (pingHandle && tickCount % 2 === 0) {
    const ok = pingHandle.send({ n: tickCount })
    console.info('[NekoLDLib/p3-server] 自动 S2C ping.send(' + tickCount + ') 返回 ' + ok)
  }
}, 5000)

// 玩家进服即开界面
// 注意：事件对象是原生 NeoForge 事件，字段按 Java getter 暴露 —— 用 getEntity() 拿玩家。
PlayerEvents.loggedIn(event => {
  const player = event.getEntity()
  console.info('[NekoLDLib/p3-server] 玩家进服，尝试开界面')
  const ok = PlayerUI.open(player, 'nekoldlib:demo')
  console.info('[NekoLDLib/p3-server] open 返回 ' + ok)
})
