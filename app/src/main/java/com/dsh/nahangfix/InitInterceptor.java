package com.dsh.nahangfix;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

/**
 * 第 2 层：融云初始化入口的“让位 + 记录”，<b>绝不拦截 App 自己的初始化</b>。
 *
 * <p>做过的事：</p>
 * <ol>
 *   <li>App 调用 init 之前，先把本模块可能设置的<b>占位单例清空</b>，让 SDK 在干净状态下完成真实初始化；</li>
 *   <li>记录 App 实际传入的 AppKey（诊断用，也是发现 manifest 里 AppKey 失效的依据）；</li>
 *   <li>调用原方法，返回值/异常原样透传 —— 行为与“没有装模块”完全一致。</li>
 * </ol>
 *
 * <p><b>不再做去重跳过</b>。曾经的“跳过重复 init”会跳过 {@code RongContext#init(Context)} 这类
 * 无 AppKey 参数的关键调用，造成 IMLib 与 IMKit 状态不一致，并且把 SDK 的 AppKey 固定在
 * 模块抢先初始化时用的错误值上，最终导致 31004 TOKEN_INCORRECT（读不到消息）。</p>
 */
final class InitInterceptor {

    private static final String[] INIT_CLASSES = {
            "io.rong.imkit.RongIM",
            "io.rong.imkit.RongCoreClient",
            "io.rong.imlib.RongCoreClient",
            "io.rong.imlib.RongIMClient",
            "io.rong.imkit.IMCenter",
            "io.rong.imkit.RongContext",
    };

    private InitInterceptor() {
    }

    static void install(ClassLoader cl) {
        int count = 0;
        for (String className : INIT_CLASSES) {
            Class<?> c = RongGuard.findClassIfExists(className, cl);
            if (c == null) {
                continue;
            }
            int perClass = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!"init".equals(m.getName()) || !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (m.getParameterTypes().length == 0) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, CALLBACK);
                    perClass++;
                    count++;
                } catch (Throwable t) {
                    XLog.w("拦截失败（忽略）：" + m + " -> " + t);
                }
            }
            if (perClass > 0) {
                XLog.i("已接管初始化入口 " + className + " 共 " + perClass + " 个（仅让位+记录）");
            }
        }
        XLog.i("InitInterceptor 接管点合计=" + count);
    }

    private static final XC_MethodReplacement CALLBACK = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
            Method m = (Method) param.method;
            String appKeyArg = RongGuard.firstStringArg(param.args);

            if (appKeyArg != null) {
                RongGuard.learnAppKey(appKeyArg);
            }
            if (RongGuard.isPlaceholder()) {
                RongGuard.clearPlaceholder("App 调用 " + RongGuard.describe(m) + "，让出真实初始化");
            }
            XLog.i("放行 App 的真实初始化：" + RongGuard.describe(m) + " appKey=" + appKeyArg);

            // 不吞异常、不改返回值：与未安装模块时的行为完全一致
            return XposedBridge.invokeOriginalMethod(m, param.thisObject, param.args);
        }
    };
}
