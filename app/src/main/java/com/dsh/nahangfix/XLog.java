package com.dsh.nahangfix;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * 双通道日志：logcat（tag=NahangFix）+ Xposed/LSPosed 日志，便于现场取证。
 *
 * <p><b>为什么每个方法都要 try/catch 包住 XposedBridge.log</b>：本类也运行在
 * <b>模块 App 自己</b>的进程里（`ReproRunner` / `ReproService` 等都在同一个 APK），
 * 而那个进程没有 Xposed 环境，`XposedBridge.log` 会抛
 * `IllegalStateException` / `NoClassDefFoundError`（日志输出本不该是致命操作）。
 *
 * <p>实测踩过的坑：复现流程里的心跳线程调用本类后线程被直接打死，
 * 表现为"步骤/通知不再刷新、看起来卡死"。当时只在调用点改用 `android.util.Log` 绕过，
 * 根因一直留在这里——现在统一在源头兜住，任何调用点都不再需要提防。</p>
 */
final class XLog {

    static final String TAG = "NahangFix";

    /**
     * 安全地写 Xposed/LSPosed 日志。
     *
     * <p>没有 Xposed 环境（模块 App 自身进程）或 Xposed API 类缺失时静默跳过，
     * 绝不能因为"写日志"而影响调用方的正常逻辑。</p>
     */
    private static void safeBridgeLog(String message) {
        try {
            XposedBridge.log(message);
        } catch (Throwable ignore) {
            // 非 Xposed 环境：只保留 logcat 通道即可
        }
    }

    static void i(String msg) {
        Log.i(TAG, msg);
        safeBridgeLog(TAG + " | " + msg);
    }

    static void w(String msg) {
        Log.w(TAG, msg);
        safeBridgeLog(TAG + " | W | " + msg);
    }

    static void w(String msg, Throwable t) {
        Log.w(TAG, msg, t);
        safeBridgeLog(TAG + " | W | " + msg + "\n" + Log.getStackTraceString(t));
    }

    static void e(String msg) {
        Log.e(TAG, msg);
        safeBridgeLog(TAG + " | E | " + msg);
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        safeBridgeLog(TAG + " | E | " + msg + "\n" + Log.getStackTraceString(t));
    }

    /**
     * 模块 → 模块 App 的<b>状态标记</b>，只能走 logcat。
     *
     * <p>为什么不写文件：实测模块 App 在自己的挂载命名空间里读不到目标 App 的私有目录
     * （`/data/data/cn.edu.nchu.nahang/files/...` 直接报 No such file or directory），
     * 而 logcat 是 App 用 root 一定能读到的全局通道。标记必须是<b>单行纯 ASCII</b>，
     * 便于 App 侧精确匹配。</p>
     */
    static void marker(String marker) {
        Log.i(TAG, marker);
        safeBridgeLog(TAG + " | " + marker);
    }
}
