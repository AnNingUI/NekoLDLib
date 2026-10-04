// NekoLDLib 阶段 3 验证 —— 客户端侧。
//
// 放到 client_scripts/ 下。
//
// 为什么客户端也要注册同一个 id：
//   ModularUIContainerMenu 在两端各构造一次，构造器里都调 createUI(player)。
//   客户端收到开界面包后会自己构造 menu，因此需要客户端侧的工厂。

console.info('[NekoLDLib/p3-client] 加载')

PlayerUI.register('nekoldlib:demo', player => {
  // 与服务端同 id 的 RPC：本侧的 impl 在对端 send 时执行。
  const echo = RPC.create('nekoldlib:echo')
    .schema({ msg: 'string' })
    .returns('string')
    .fn(({ msg }) => {
      console.info('[NekoLDLib/p3-client] RPC echo 被服务端调用，收到: ' + msg)
      return '客户端应答:' + msg
    })

  // 服务端点"发 RPC 到客户端"时会走这里
  const ping = RPC.create('nekoldlib:ping')
    .schema({ n: 'int' })
    .returns('void')
    .fn(({ n }) => {
      console.info('[NekoLDLib/p3-client] RPC ping 收到 n = ' + n + '（反方向 RPC 成功）')
    })

  return (
    <panel class="panel_bg" width={240} height={170} padding-all={8} gap-all={4}>
      <label text="阶段 3：服务端开 UI" />
      <label bind={{ get: () => 'speed = ' + 3 }} />
      <slider min={1} max={10} bind={{ get: () => 3, set: v => {} }} />
      <label bind={{ get: () => 'enabled = ' + false }} />
      <switch bind={{ get: () => false, set: v => {} }} />
      <button
        text="发 RPC 到服务端"
        on-click={() => {
          const ok = echo.send({ msg: '来自客户端' })
          console.info('[NekoLDLib/p3-client] echo.send 返回 ' + ok)
        }}
      />
    </panel>
  )
})

console.info('[NekoLDLib/p3-client] 已注册: ' + PlayerUI.ids())
