package com.dsh.nahangfix;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 第 5 层：处理“进程被杀后任务栈被过早恢复”的场景（真机实测结论）。
 *
 * <p>本 App 的融云初始化与业务层（{@code TaskDispatcher}）都由它<b>自己的启动流程</b>完成，
 * 而进程死亡后从最近任务恢复 {@code MainActivity} 时会跳过这套流程，于是：</p>
 * <ol>
 *   <li>{@code BaseNewContactFragment.onCreate} 里 {@code RongContext.getInstance()} 为 null → 原始崩溃；</li>
 *   <li>即使第 1 处被占位实例保下来，紧接着 {@code MainActivity.onCreateActionBar} →
 *       {@code CacheTask.getMyStaffInfo} → {@code TaskDispatcher.getCurrentUserInfo()} 又是 null → 再崩一次；</li>
 *   <li>并且这条路径下 App 永远不自己初始化融云（真机日志确认 AppKey 初始化调用次数为 0），
 *       所以消息永远读不出来。</li>
 * </ol>
 *
 * <p>做法：检测到<b>带保存状态</b>的 MainActivity 在 App 尚未初始化时被创建，就把这个实例
 * finish 掉，并用 App 自己的启动页（{@code FLAG_ACTIVITY_CLEAR_TASK}）重新拉起，让 App 走完
 * 完整初始化流程后再进入主页。被放弃的那个半初始化实例，其生命周期回调里必然还会抛异常
 * （实测 {@code onCreate} 抛业务层空指针、{@code onDestroy} 抛 {@code IllegalArgumentException}），
 * 这些异常只对该实例定向吞掉，不影响 App 其它任何 Activity。</p>
 *
 * <p>为什么挂在 {@link Instrumentation#callActivityOnCreate(Activity, Bundle)} 而不是
 * {@code MainActivity.onCreate}：真机实测发现，若直接替换 {@code onCreate} 且不调用
 * {@code super.onCreate()}，{@code ActivityThread.performLaunchActivity} 会立刻抛
 * {@code SuperNotCalledException: did not call through to super.onCreate()}。
 * 挂在这里既能包住原始调用、又能在需要时补上框架要求的 {@code Activity.mCalled} 标志。</p>
 *
 * <p>正常冷启动是 {@code savedInstanceState == null}，而本 App 正常启动时
 * {@code MainActivity.onCreate} 本来就早于融云初始化，所以判据必须同时要求“带保存状态”，
 * 否则每次启动都会被误重定向（上一版就踩过这个坑）。每个进程只重定向一次，杜绝循环。</p>
 */
final class EarlyRestoreGuard {

    private static final String MAIN_ACTIVITY = "cn.edu.nchu.nahang.ui.MainActivity";

    private static volatile boolean sRedirected;
    private static volatile Activity sRedirectedActivity;

    private EarlyRestoreGuard() {
    }

    static void install() {
        hook("callActivityOnCreate", Activity.class, Bundle.class);
        hook("callActivityOnStart", Activity.class);
        hook("callActivityOnResume", Activity.class);
        hook("callActivityOnPause", Activity.class);
        hook("callActivityOnStop", Activity.class);
        hook("callActivityOnDestroy", Activity.class);
        hook("callActivityOnRestart", Activity.class);
    }

    private static void hook(String name, Class<?>... parameterTypes) {
        try {
            Object[] args = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
            args[parameterTypes.length] = CALLBACK;
            XposedHelpers.findAndHookMethod(Instrumentation.class, name, args);
            XLog.i("已挂钩 Instrumentation." + name);
        } catch (Throwable t) {
            // 某些 ROM 可能没有该重载，缺一个不影响主流程
            XLog.w("挂钩 Instrumentation." + name + " 失败（忽略）：" + t);
        }
    }

    private static final XC_MethodReplacement CALLBACK = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
            String name = param.method instanceof Method ? ((Method) param.method).getName() : "";
            Activity activity = firstActivity(param.args);
            if (activity == null) {
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            }

            if (RongGuard.repairsDisabled()) {
                // 测试模式：不重定向、不吞异常，让原始崩溃如实发生
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            }

            // —— 决策：只有“带保存状态 + App 未初始化 + 目标 Activity + 本进程首次”才重定向
            if ("callActivityOnCreate".equals(name)
                    && MAIN_ACTIVITY.equals(activity.getClass().getName())) {
                Bundle state = param.args.length > 1 && param.args[1] instanceof Bundle
                        ? (Bundle) param.args[1] : null;
                boolean appNotInitialized = RongGuard.getRealInstanceOrNull() == null;
                if (state != null && appNotInitialized && !sRedirected) {
                    sRedirected = true;
                    sRedirectedActivity = activity;
                    restartAppFlow(activity);
                } else if (state != null && appNotInitialized) {
                    XLog.w("本进程已重定向过一次，不再重复（避免循环），按原流程继续");
                }
            }

            // —— 收尾：被我们放弃的那个半初始化实例，其生命周期回调异常定向吞掉
            if (activity == sRedirectedActivity) {
                try {
                    Object result = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    if ("callActivityOnDestroy".equals(name)) {
                        sRedirectedActivity = null;
                    }
                    return result;
                } catch (Throwable t) {
                    // 只有"这个实例正在被丢弃"造成的可预期异常才吞；Error（OOM / StackOverflow 等）
                    // 一律放行——吞掉它们会把真正的问题藏起来，比崩溃更难排查。
                    if (!isSwallowableForRedirected(t)) {
                        XLog.e("被重定向 Activity 的 " + name + " 抛出了不可吞的异常，原样放行", t);
                        sRedirectedActivity = null;
                        markCalled(activity);
                        throw t;
                    }
                    XLog.w("已吞掉被重定向 Activity 的 " + name + " 异常（该实例正在被丢弃）：", t);
                    // 必须补 mCalled：框架在 callActivityOnCreate/onStart/onResume/onDestroy 之后
                    // 都会检查该标志，否则抛 SuperNotCalledException（真机已踩到 onCreate 与 onDestroy 两处）。
                    markCalled(activity);
                    // 无论成功失败都要解除引用：否则该实例的后续回调会被"永久吞异常"，
                    // 并且静态字段一直持有已销毁的 Activity（内存泄漏）。
                    if ("callActivityOnDestroy".equals(name)) {
                        sRedirectedActivity = null;
                    }
                    return null;
                }
            }

            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
        }
    };

    /**
     * 判断异常是否属于"被放弃实例的可预期异常"，只有这类才允许吞掉。
     *
     * <p>白名单很窄是有意的：{@code Error}（{@code OutOfMemoryError}、
     * {@code StackOverflowError}、{@code NoClassDefFoundError} 等）与
     * 其它非 NPE/非状态异常必须继续向外抛，否则问题会被静默掩盖。</p>
     */
    private static boolean isSwallowableForRedirected(Throwable t) {
        // 先沿 cause 链解开反射包装：XposedBridge.invokeOriginalMethod 抛出的异常会被包成
        // java.lang.reflect.InvocationTargetException（实测真机栈就是
        // InvocationTargetException -> NullPointerException）。
        // 若只看顶层类型，真正的业务 NPE 会被误判成"不可吞"而重新抛出 —— 这正是踩过的回归。
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            if (cur instanceof InvocationTargetException || cur instanceof UndeclaredThrowableException) {
                cur = cur.getCause();
                continue;
            }
            if (cur instanceof NullPointerException || cur instanceof IllegalStateException
                    || cur instanceof IllegalArgumentException) {
                return true;
            }
            // OutOfMemoryError / StackOverflowError / NoClassDefFoundError 等一律不放行：
            // 它们不是"被放弃的实例"造成的可预期异常，吞掉会把真问题藏起来（比崩溃更难查）。
            if (cur instanceof Error) {
                return false;
            }
            // 其余运行时异常继续沿链看下一层 cause（不再无条件放行任意 RuntimeException）
            if (cur instanceof RuntimeException && cur.getCause() != null
                    && cur.getCause() != cur) {
                cur = cur.getCause();
                continue;
            }
            return false;
        }
        return false;
    }

    private static Activity firstActivity(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        return args[0] instanceof Activity ? (Activity) args[0] : null;
    }

    /** 补上框架要求的 {@code Activity.mCalled}，避免 SuperNotCalledException。 */
    private static void markCalled(Activity activity) {
        try {
            XposedHelpers.setBooleanField(activity, "mCalled", true);
        } catch (Throwable t) {
            XLog.w("设置 mCalled 失败：" + t);
        }
    }

    /** 用 App 自己的启动页重新拉起整个流程。 */
    private static boolean restartAppFlow(Activity activity) {
        try {
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
            if (launch == null) {
                XLog.e("拿不到启动 Intent，放弃重定向");
                return false;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            XLog.w("检测到 MainActivity 在 App 初始化之前被恢复，结束它并重定向到启动页："
                    + launch.getComponent()
                    + "（App 会走完自己的初始化流程，融云 AppKey 依旧由 App 自己提供）");
            activity.startActivity(launch);
            activity.finish();
            return true;
        } catch (Throwable t) {
            XLog.e("重定向到启动页失败", t);
            return false;
        }
    }

}
