package com.anningui.nekoldlib.jsx;

import graal.graalvm.polyglot.Value;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * {@link JsxHost} 的 Graal 实现。
 *
 * <p>这是全项目唯一直接操作 Graal {@link Value} 的地方——上游若换 shading 或改用原生
 * {@code org.graalvm.polyglot}，改动集中在本文件。
 */
public final class GraalJsxHost implements JsxHost {

    public static final GraalJsxHost INSTANCE = new GraalJsxHost();

    private GraalJsxHost() {
    }

    @Override
    public @Nullable Value member(Value object, String name) {
        if (object == null || object.isNull() || !object.hasMembers()) {
            return null;
        }
        try {
            return object.getMember(name);
        } catch (RuntimeException e) {
            // getMember 在部分宿主对象上会抛（不可访问成员），当作不存在
            return null;
        }
    }

    @Override
    public List<String> keys(Value object) {
        if (object == null || object.isNull() || !object.hasMembers()) {
            return List.of();
        }
        Object raw;
        try {
            raw = object.getMemberKeys();
        } catch (RuntimeException e) {
            return List.of();
        }
        // getMemberKeys() 的返回类型随 Graal 版本变化：可能是 Set<String>，
        // 也可能是一个宿主 Value（数组）。两种都处理。
        if (raw instanceof Set<?> set) {
            var out = new ArrayList<String>(set.size());
            for (var k : set) {
                out.add(String.valueOf(k));
            }
            return out;
        }
        if (raw instanceof Value v && v.hasArrayElements()) {
            var out = new ArrayList<String>((int) v.getArraySize());
            for (long i = 0; i < v.getArraySize(); i++) {
                var e = v.getArrayElement(i);
                if (e != null && !e.isNull()) {
                    out.add(e.asString());
                }
            }
            return out;
        }
        return List.of();
    }

    @Override
    public boolean isArray(Value value) {
        return value != null && !value.isNull() && value.hasArrayElements();
    }

    @Override
    public List<Value> iterable(Value value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.hasArrayElements()) {
            var size = (int) value.getArraySize();
            var out = new ArrayList<Value>(size);
            for (long i = 0; i < size; i++) {
                out.add(value.getArrayElement(i));
            }
            return out;
        }
        if (value.hasIterator()) {
            var out = new ArrayList<Value>();
            var it = value.getIterator();
            while (it.hasIteratorNextElement()) {
                out.add(it.getIteratorNextElement());
            }
            return out;
        }
        return Collections.singletonList(value);
    }

    @Override
    public @Nullable Value call(Value fn, @Nullable Value arg) {
        try {
            return arg == null ? fn.execute() : fn.execute(arg);
        } catch (RuntimeException e) {
            throw new IllegalStateException("调用 JS 函数失败：" + e.getMessage(), e);
        }
    }

    /**
     * 识别 vnode。
     *
     * <p>两套 runtime 的产出都要认：
     * <ul>
     *   <li>automatic —— {@code { $$nekoJsx: true, type, key, props, children }}</li>
     *   <li>classic（NekoJS 默认）—— {@code { tag, props, children }}，没有标记位，
     *       所以靠"有 props/children、且 tag 或 type 存在"来判定。</li>
     * </ul>
     *
     * <p>只认 {@code $$nekoJsx} 会让默认配置下整条链路静默失效——classic 工厂
     * （{@code define.js} 的 {@code createJsxElement}）根本不写那个标记。
     */
    @Override
    public boolean isElement(Value value) {
        if (value == null || value.isNull() || !value.hasMembers()) {
            return false;
        }
        var marker = member(value, "$$nekoJsx");
        if (marker != null && !marker.isNull() && marker.asBoolean()) {
            return true;
        }
        // classic 形状：必须有 tag 或 type，且带 props
        if (member(value, "props") == null) {
            return false;
        }
        return member(value, "tag") != null || member(value, "type") != null;
    }

    @Override
    public @Nullable Value typeOf(Value vnode) {
        var type = member(vnode, "type");
        return type != null ? type : member(vnode, "tag");
    }

    @Override
    public @Nullable Value propsOf(Value vnode) {
        return member(vnode, "props");
    }

    @Override
    public @Nullable Value childrenOf(Value vnode) {
        return member(vnode, "children");
    }

    @Override
    public boolean isFragment(@Nullable Value type) {
        if (type == null || type.isNull()) {
            return false;
        }
        // JSX 运行时用 Symbol 表示 Fragment（classic 是 Symbol.for('nekojs.jsx.fragment')，
        // automatic 是 Symbol('nekojs.jsx.fragment')）。
        //
        // NekoJS 所用的 Graal 版本里 Value 既没有 isSymbol()，Symbol 也**没有可读的
        // members**（description 读不到）——实测发现唯一可靠的识别方式是 toString()：
        // 它稳定产出 "Symbol(nekojs.jsx.fragment)"。
        var text = String.valueOf(type);
        if (text.contains("fragment")) {
            return true;
        }
        // 兜底：某些宿主实现可能把 description 暴露成成员
        if (type.hasMembers()) {
            var desc = member(type, "description");
            if (desc != null && !desc.isNull() && desc.isString()) {
                return desc.asString().contains("fragment");
            }
        }
        return false;
    }

    @Override
    public List<String> stringList(Value value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isString()) {
            return List.of(value.asString());
        }
        if (value.hasArrayElements()) {
            var out = new ArrayList<String>((int) value.getArraySize());
            for (var v : iterable(value)) {
                if (v != null && !v.isNull()) {
                    out.add(v.asString());
                }
            }
            return out;
        }
        return List.of(value.asString());
    }
}
