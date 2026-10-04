package com.tkisor.nekoldlib.jsx;

import com.tkisor.nekojs.api.JSTypeAdapter;
import graal.graalvm.polyglot.Value;

/**
 * 把 JS 侧的 vnode 转成 {@link VNode}，让签名的类型声明具体化。
 *
 * <h2>为什么 test 条件要宽松</h2>
 *
 * 识别条件刻意只要求"带 props 的对象"，<b>不</b>校验是合法 vnode。原因：
 * <ul>
 *   <li>宽松匹配让拼错的 vnode 仍能走到 {@link ElementRenderer}，在那里得到
 *       <em>"未知的 JSX 标签 &lt;panell&gt;。已注册：[...]"</em> 这种可操作的报错；
 *       若在这里就拒收，用户只会看到"NekoJS 无法把对象转成 VNode"。</li>
 *   <li>两种 vnode 形状（classic 的 {@code tag} / automatic 的 {@code type}）
 *       以及函数组件、Fragment 都要能过。</li>
 * </ul>
 *
 * <p>不匹配时返回 false，让 NekoJS 继续尝试其它适配器或给出它自己的报错。
 */
public final class VNodeAdapter implements JSTypeAdapter<VNode> {

    @Override
    public Class<VNode> getTargetClass() {
        return VNode.class;
    }

    @Override
    public boolean test(Value value) {
        if (value == null || value.isNull()) {
            return false;
        }
        // 已经是 VNode（Java 侧直接调用时）
        if (value.isHostObject() && value.asHostObject() instanceof VNode) {
            return true;
        }
        // 宽松：带 props 的对象就认。合法性与标签名交给渲染器校验。
        return value.hasMembers() && value.getMember("props") != null;
    }

    @Override
    public VNode apply(Value value) {
        if (value.isHostObject() && value.asHostObject() instanceof VNode vnode) {
            return vnode;
        }
        return new VNode(value);
    }
}
