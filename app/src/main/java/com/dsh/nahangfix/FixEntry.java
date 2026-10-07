package com.dsh.nahangfix;

import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口。
 *
 * <p>崩溃现象：
 * <pre>
 * java.lang.NullPointerException: Attempt to invoke virtual method
 *   'io.rong.eventbus.EventBus io.rong.imkit.RongContext.getEventBus()' on a null object reference
 *   at cn.edu.nchu.nahang.ui.contactx.portal.BaseNewContactFragment.onCreate(BaseNewContactFragment.java:48)
 * </pre>
 * 即 MainActivity 的 Fragment 被同步重建时，融云 IMKit 的全局单例
 * {@code RongContext.getInstance()} 还是 null（App 自己的初始化发生在之后几十毫秒）。</p>
 *
 * <p><b>只作用于主进程</b>：本 App 的 IM 长连接由 {@code :ipc} 进程承载，那里的初始化完全由
 * App 自己完成。实测表明，模块一旦在 {@code :ipc} 抢先初始化（或用伪造单例顶替），
 * 会把 SDK 的 AppKey 钉成错误值，导致连接返回 31004 TOKEN_INCORRECT、消息读不出来。
 * 因此非主进程一律不装任何 Hook，只留一条日志。</p>
 */
public final class FixEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    /** 目标应用包名（南航 App）。若包名变化，只改这一处。 */
    public static final String TARGET_PACKAGE = "cn.edu.nchu.nahang";

    private static volatile ClassLoader sTargetClassLoader;

    static ClassLoader targetClassLoader() {
        return sTargetClassLoader;
    }

    @Override
    public void initZygote(StartupParam startupParam) {
        XLog.i("模块已随 zygote 加载, modulePath=" + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        sTargetClassLoader = lpparam.classLoader;
        RongGuard.setProcessName(lpparam.processName);

        boolean mainProcess = lpparam.processName == null || !lpparam.processName.contains(":");

        XLog.i("=== 进入目标应用 === 包名=" + lpparam.packageName
                + " 进程=" + lpparam.processName
                + " pid=" + Process.myPid()
                + " 主进程=" + mainProcess);

        if (!mainProcess) {
            XLog.i("非主进程不做任何干预（该进程的融云初始化必须由 App 自己完成）");
            return;
        }

        // 第 1 层：只观测，不修改（诊断 App 自身的初始化时序）
        try {
            AppInitHook.install();
        } catch (Throwable t) {
            XLog.e("AppInitHook 安装失败", t);
        }

        // 第 2 层：拦截 SDK 初始化入口：释放占位实例、记录 App 的真实 AppKey，其余原样放行
        try {
            InitInterceptor.install(lpparam.classLoader);
        } catch (Throwable t) {
            XLog.e("InitInterceptor 安装失败", t);
        }

        // 第 3 层：单例/EventBus 兜底（占位实例只在 Fragment 即将创建、单例仍为 null 时才使用）
        try {
            RongGuard.install(lpparam.classLoader);
        } catch (Throwable t) {
            XLog.e("RongGuard 安装失败", t);
        }

        // 第 4 层：框架层 Fragment 兜底（最后一道防线）
        try {
            FragmentGuard.install(lpparam.classLoader);
        } catch (Throwable t) {
            XLog.e("FragmentGuard 安装失败", t);
        }

        // 第 5 层：进程死亡后 MainActivity 被过早恢复时，重定向回 App 自己的启动流程
        try {
            EarlyRestoreGuard.install();
        } catch (Throwable t) {
            XLog.e("EarlyRestoreGuard 安装失败", t);
        }

        XLog.i("全部 Hook 安装完成");
        // 给模块 App 留一行"我在这里"的标记（纯 ASCII，单行）：模块 App 读不到目标 App 的
        // 私有目录，只能用 logcat 判断"模块是否已注入这个进程"，pid 用于和当前目标进程比对。
        XLog.marker("NAHANGFIX_READY pid=" + android.os.Process.myPid()
                + " ver=1.3.0 process=" + lpparam.packageName);
    }
}
