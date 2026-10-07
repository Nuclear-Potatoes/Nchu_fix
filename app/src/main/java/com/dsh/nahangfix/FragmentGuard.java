package com.dsh.nahangfix;

import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 第 4 层修复（最后一道防线）：在框架层挂钩 Fragment 的创建流程。
 *
 * <p>难点在于：业务类 {@code cn.edu.nchu.nahang.ui.contactx.portal.BaseNewContactFragment}
 * 可能被混淆或被加固方案换掉类名，直接挂钩业务类并不可靠。而崩溃调用栈里出现的是</p>
 *
 * <pre>
 * androidx.fragment.app.Fragment.performCreate(Fragment.java:2586)
 *   -> cn.edu.nchu.nahang...BaseNewContactFragment.onCreate(...)
 * </pre>
 *
 * <p>{@code Fragment#performCreate(Bundle)} 是 AndroidX 框架自己的方法（包内可见），
 * 与混淆/加固无关，因此这里用 {@code XC_MethodReplacement} 包一层：</p>
 * <ol>
 *   <li>调用原方法之前，再抢一次融云初始化（此时 Application 早已启动，通常一次就成功）；</li>
 *   <li>如果仍然抛出“融云单例为 null”导致的 NPE，就地吞掉并修补 Fragment 状态机
 *       （mCalled / mIsCreated / 子 FragmentManager），保证 Activity 不会因此崩溃。</li>
 * </ol>
 */
final class FragmentGuard {

    private static final String[] FRAGMENT_CLASSES = {
            "androidx.fragment.app.Fragment",
            "android.support.v4.app.Fragment",
    };

    /** 崩溃栈里出现的具体业务类：只做“提前初始化”，不改写行为。 */
    private static final String[] APP_FRAGMENT_CLASSES = {
            "cn.edu.nchu.nahang.ui.contactx.portal.BaseNewContactFragment",
            "cn.edu.nchu.nahang.ui.contactx.portal.ContactFragment",
    };

    private FragmentGuard() {
    }

    static void install(ClassLoader cl) {
        int hooked = 0;
        for (String className : FRAGMENT_CLASSES) {
            Class<?> fragmentClass = RongGuard.findClassIfExists(className, cl);
            if (fragmentClass == null) {
                continue;
            }
            for (Method m : fragmentClass.getDeclaredMethods()) {
                if (!"performCreate".equals(m.getName()) || !isSupportedSignature(m)) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, PERFORM_CREATE);
                    hooked++;
                    XLog.i("已挂钩 " + m);
                } catch (Throwable t) {
                    XLog.e("挂钩失败：" + m, t);
                }
            }
        }
        XLog.i("FragmentGuard: performCreate 挂钩数=" + hooked
                + (hooked == 0
                        ? " —— 【本层未生效】没找到任何可挂钩的 performCreate() 重载！"
                        : ""));
        if (hooked == 0) {
            // 必须显式报错：本层是"最后一道防线"，静默失效会让排查者误以为还有兜底
            // （业务类那份兜底在 App 被混淆/加固时同样会找不到，两者可能同时失效）。
            XLog.e("FragmentGuard 第 4 层未生效：请检查 androidx.fragment 版本与 performCreate 签名；"
                    + "此时只剩 第 3 层（getEventBus 兜底 / 占位单例）在起作用");
        }

        for (String className : APP_FRAGMENT_CLASSES) {
            Class<?> c = RongGuard.findClassIfExists(className, cl);
            if (c == null) {
                XLog.w("未找到业务类 " + className + "（可能被混淆/加固，不影响框架层兜底）");
                continue;
            }
            try {
                XposedHelpers.findAndHookMethod(c, "onCreate", Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        RongGuard.ensureInitialized(contextOf(param.thisObject),
                                "before " + param.thisObject.getClass().getName() + ".onCreate");
                    }
                });
                XLog.i("已挂钩 " + className + ".onCreate（初始化抢先执行）");
            } catch (Throwable t) {
                XLog.w("挂钩 " + className + ".onCreate 失败：" + t);
            }
        }
    }

    /**
     * 是否是我们要兜底的 {@code performCreate} 重载。
     *
     * <p><b>为什么放宽到"首个参数是 Bundle 即可"</b>：不同 androidx / support 版本的
     * {@code performCreate} 重载签名不一样（`(Bundle)`、`(Bundle, boolean)`，
     * 以及历史上出现过的更多参数形态）。原先的实现把参数个数硬限制为 ≤2、
     * 类型白名单只允许 `Bundle`/`boolean`，一旦版本不符就会<b>静默跳过</b>整个第 4 层。
     * 这里改为只锚定"方法名 + 首个参数为 Bundle"，其余参数只要能由框架传进来就不拒绝，
     * 从而对各种版本都成立。</p>
     */
    private static boolean isSupportedSignature(Method m) {
        Class<?>[] pt = m.getParameterTypes();
        if (pt.length == 0 || pt.length > 4) {
            return false;   // 0 参或参数过多的都不是我们要的形态
        }
        return pt[0] == Bundle.class;
    }

    private static final XC_MethodReplacement PERFORM_CREATE = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
            Object fragment = param.thisObject;
            if (RongGuard.repairsDisabled()) {
                // 测试模式：完全不干预，让原始行为（含崩溃）如实发生
                return XposedBridge.invokeOriginalMethod(param.method, fragment, param.args);
            }
            try {
                RongGuard.ensureInitialized(contextOf(fragment),
                        "Fragment.performCreate:" + fragment.getClass().getName());
            } catch (Throwable t) {
                XLog.e("Fragment 创建前修复失败", t);
            }
            try {
                return XposedBridge.invokeOriginalMethod(param.method, fragment, param.args);
            } catch (Throwable t) {
                if (RongGuard.isRongNullFailure(t)) {
                    XLog.e("已拦截融云空指针，保住界面且不崩溃：" + fragment.getClass().getName(), t);
                    repairAfterSwallow(fragment);
                    return null;
                }
                throw t;
            }
        }
    };

    private static Context contextOf(Object fragment) {
        try {
            Object c = XposedHelpers.callMethod(fragment, "getContext");
            if (c instanceof Context) {
                return (Context) c;
            }
        } catch (Throwable ignore) {
            // 继续尝试 getActivity
        }
        try {
            Object c = XposedHelpers.callMethod(fragment, "getActivity");
            if (c instanceof Context) {
                return (Context) c;
            }
        } catch (Throwable ignore) {
            // 最后退回 Application Context
        }
        return RongGuard.contextOrNull();
    }

    /**
     * 吞掉异常后修补 Fragment 状态机，避免后续 SuperNotCalledException 之类的连锁崩溃。
     */
    private static void repairAfterSwallow(Object fragment) {
        // 1) 补 mCalled：框架在 onCreate 之后检查该标志，不补会抛 SuperNotCalledException
        trySetBoolean(fragment, "mCalled", true);

        // 2) 推进 mState 到 CREATED。
        //    【为什么必须做，且为什么不能改 mIsCreated —— 依据 androidx 源码（已核对）】
        //    FragmentStateManager.create() 是：
        //        if (!mFragment.mIsCreated) { mFragment.performCreate(savedInstanceState); ... }
        //        else { mFragment.mState = CREATED; ... }
        //    而 mState 的正常推进发生在外层 moveToExpectedState() 的 while 循环里。
        //    performCreate 抛异常时 create() 没有返回，mState 仍停在 ATTACHED；循环条件
        //    (computeExpectedState() != mState) 依旧成立，于是**下一次调度会再次进入
        //    create() 并抛同一个异常**。所以吞掉异常而不推进 mState，等于让崩溃无限重试。
        //    另外：mIsCreated 在 Fragment.java 里从不赋值（只在 FragmentStateManager 的
        //    retained 路径被设置），把它置 true 会让框架**跳过 performCreate 与
        //    dispatchOnFragmentCreated**（onCreate 不跑完、子 Fragment 不创建、Lifecycle
        //    不推进），把"崩溃"换成"静默的坏状态"——危害更大，因此这里不再设置它。
        Integer created = stateConstantOf(fragment, "CREATED");
        if (created != null) {
            trySetInt(fragment, "mState", created);
        } else {
            XLog.w("未能解析 Fragment.CREATED 常量，跳过 mState 修补（可能导致该 Fragment 反复重试）");
        }

        // 3) 子 FragmentManager：performCreate 没跑完，子 Fragment 的创建需要手动补上
        try {
            Object childFragmentManager = XposedHelpers.getObjectField(fragment, "mChildFragmentManager");
            if (childFragmentManager != null) {
                try {
                    XposedHelpers.callMethod(childFragmentManager, "noteStateNotSaved");
                } catch (Throwable ignore) {
                    // 老版本没有该方法，忽略
                }
                try {
                    XposedHelpers.callMethod(childFragmentManager, "dispatchCreate");
                } catch (Throwable ignore) {
                    // 状态不一致时忽略，保证不崩
                }
            }
        } catch (Throwable ignore) {
            // 没有子 FragmentManager 字段（老版本），忽略
        }
    }

    /**
     * 读取 {@code Fragment} 里的状态常量（如 {@code CREATED = 1}）。
     *
     * <p>不硬编码数字：不同版本可能调整取值，直接读字段最可靠。
     * 读不到时返回 {@code null}，由调用方决定降级行为。</p>
     */
    private static Integer stateConstantOf(Object fragment, String name) {
        // 必须沿父类链查找：getClass() 给的是业务子类（如 BaseNewContactFragment），
        // 而 CREATED 声明在 androidx 的 Fragment 上，getDeclaredField 不查父类。
        for (Class<?> c = fragment.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(null);   // 静态常量
            } catch (NoSuchFieldException ignore) {
                // 继续向上找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void trySetInt(Object target, String fieldName, int value) {
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    int old = f.getInt(target);
                    f.setInt(target, value);
                    XLog.i("已修复字段 " + c.getSimpleName() + "#" + fieldName
                            + " " + old + " -> " + value);
                    return;
                } catch (NoSuchFieldException ignore) {
                    // 向上继续找
                }
            }
            XLog.w("未找到字段 " + fieldName + "，mState 未能修补");
        } catch (Throwable t) {
            XLog.w("修复字段 " + fieldName + " 失败：" + t);
        }
    }

    private static void trySetBoolean(Object target, String fieldName, boolean value) {
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.setBoolean(target, value);
                    XLog.i("已修复字段 " + c.getSimpleName() + "#" + fieldName + "=" + value);
                    return;
                } catch (NoSuchFieldException ignore) {
                    // 向上继续找
                }
            }
        } catch (Throwable t) {
            XLog.w("修复字段 " + fieldName + " 失败：" + t);
        }
    }
}
