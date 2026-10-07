package com.dsh.nahangfix;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 第 1 层：启动时序<b>观测</b>（不修改任何状态）。
 *
 * <p>本 App 的融云初始化由 App 自己在启动后几十毫秒内完成，并且使用的是<b>运行时传入</b>的
 * AppKey（实测为 {@code <运行时 AppKey，已脱敏>}），而 manifest 里的 {@code RONG_CLOUD_APP_KEY}
 * （{@code <manifest 中的失效旧值，已脱敏>}）是失效的旧值。</p>
 *
 * <p>因此这一层<b>不再</b>调用任何 SDK 初始化：一旦用错 AppKey 抢先初始化，SDK 的 AppKey 会被
 * 钉死，连接返回 31004 TOKEN_INCORRECT，表现为“读不到消息”。这里只记录
 * {@code RongContext.getInstance()} 的状态与进程名，供后续诊断。</p>
 */
final class AppInitHook {

    private AppInitHook() {
    }

    static void install() {
        // 1) 记录 Application Context（只记录，不做初始化）
        try {
            Method attach = null;
            for (Method m : Application.class.getDeclaredMethods()) {
                if ("attachBaseContext".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    attach = m;
                    break;
                }
            }
            if (attach == null) {
                // Android 16 上 Application#attachBaseContext 已不可直接解析，跳过即可（非必需）
                XLog.w("未找到 Application#attachBaseContext，跳过该观测点（不影响修复）");
            } else {
                XposedBridge.hookMethod(attach, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof Context) {
                            RongGuard.rememberContext((Context) param.args[0]);
                        }
                    }
                });
                XLog.i("已挂钩 Application#attachBaseContext（仅记录 Context）");
            }
        } catch (Throwable t) {
            XLog.w("挂钩 Application#attachBaseContext 失败（忽略）：" + t);
        }

        // 2) Application.onCreate 全部执行完之后：只报告状态
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                    Application.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Application app = param.args != null && param.args.length > 0
                                    && param.args[0] instanceof Application
                                    ? (Application) param.args[0] : null;
                            if (app != null) {
                                RongGuard.rememberContext(app);
                            }
                            RongGuard.reportStartupState("Instrumentation.callApplicationOnCreate");
                            // 在目标进程内写状态文件：模块 App 用它判断"模块是否已注入"，
                            // 比解析 logcat 可靠得多（App 内通过 su 读全局 logcat 实测拿不到内容）
                            ImStatusWatcher.install(app);
                            StateFile.write(app, "Instrumentation.callApplicationOnCreate");
                        }
                    });
            XLog.i("已挂钩 Instrumentation.callApplicationOnCreate（仅观测）");
        } catch (Throwable t) {
            XLog.w("挂钩 Instrumentation.callApplicationOnCreate 失败（忽略）：" + t);
        }
    }
}
