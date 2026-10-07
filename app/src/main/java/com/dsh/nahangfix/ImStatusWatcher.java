package com.dsh.nahangfix;

import android.content.Context;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 在目标进程内跟踪<b>融云连接状态</b>，并把结果写进 {@link StateFile}。
 *
 * <p>为什么不用 logcat：连接成功的 `to = CONNECTED` 那几行很快会被日志轮转挤掉，
 * 模块 App 读最近 N 行 logcat 经常看不到，于是面板上误报"未连接"。</p>
 *
 * <p>做法：融云 SDK 会把连接状态通过 {@code io.rong.eventbus.EventBus#post(Object)}
 * 派发（事件类型名里带 {@code ConnectionStatus}，实测日志中能看到 RongIM/CallClient
 * 都在处理它）。这里挂住 {@code post}，只做一次廉价的类名判断，命中就更新状态文件。</p>
 */
final class ImStatusWatcher {

    private static final long WRITE_INTERVAL_MS = 1000L;
    /** 轮询间隔：事件兜底用，10 秒足够且开销极小。 */
    private static final long POLL_INTERVAL_MS = 10_000L;

    private static volatile String sStatus = "unknown";
    /** 最近一次<b>已成功落盘</b>的状态（用于避免重复写盘，而不是用时间节流）。 */
    private static volatile String sWritten = "";
    private static volatile Context sContext;
    private static volatile boolean sInstalled;

    private ImStatusWatcher() {
    }

    static void install(Context context) {
        if (context != null) {
            sContext = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
        }
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        hookEventBusPost();
        hookConnectionStatusListener();
        // 启动时先探一次当前状态（此时多半还是 DISCONNECTED，之后由事件/监听器/轮询更新）
        String polled = pollCurrentStatus();
        if (polled != null) {
            sStatus = polled;
        }
        startPolling();
    }

    /**
     * 包装 App 自己注册的 {@code ConnectionStatusListener}：这是最贴合 App 视角的状态来源
     * （实测有些转换不会经过 EventBus，但一定会回调到 App 注册的监听器）。
     */
    private static void hookConnectionStatusListener() {
        String[] classes = {"io.rong.imlib.RongIMClient", "io.rong.imkit.RongIM"};
        int hooked = 0;
        for (String className : classes) {
            Class<?> clazz = RongGuard.findClassIfExists(className);
            if (clazz == null) {
                continue;
            }
            for (final Method m : clazz.getDeclaredMethods()) {
                if (!"setConnectionStatusListener".equals(m.getName())) {
                    continue;
                }
                final Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1 || !pt[0].isInterface()) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object original = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            if (original == null || java.lang.reflect.Proxy.isProxyClass(original.getClass())) {
                                return;   // 已经包装过
                            }
                            try {
                                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                                        pt[0].getClassLoader(),
                                        new Class<?>[]{pt[0]},
                                        new StatusInvocationHandler(original));
                                param.args[0] = proxy;
                            } catch (Throwable t) {
                                XLog.w("包装 ConnectionStatusListener 失败（忽略）：" + t);
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable t) {
                    XLog.w("挂钩 setConnectionStatusListener 失败（忽略）：" + t);
                }
            }
        }
        XLog.i("已挂钩 setConnectionStatusListener 共 " + hooked + " 个（捕获 App 侧连接状态）");
    }

    /** 转发给原始监听器，同时记录状态。 */
    private static final class StatusInvocationHandler implements java.lang.reflect.InvocationHandler {
        private final Object original;

        StatusInvocationHandler(Object original) {
            this.original = original;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] != null) {
                    record(String.valueOf(args[0]));
                }
            } catch (Throwable ignore) {
                // 记录失败不影响 App
            }
            try {
                return method.invoke(original, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }

    /**
     * 轮询兜底：实测只靠 EventBus 事件不够——有些状态（例如最终 CONNECTED）不一定派发到
     * 主进程的 EventBus，导致状态文件一直停在 CONNECTING。这里每 10 秒反射读一次当前状态，
     * 仅在变化时落盘。
     */
    private static void startPolling() {
        try {
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                private int ticks;

                @Override
                public void run() {
                    try {
                        String now = pollCurrentStatus();
                        if (now != null) {
                            record(now);
                        }
                        // 周期性确认落盘：外部存储刚启动时可能还没挂载好，写失败就靠这里补上
                        ticks++;
                        if (ticks % 6 == 0) {
                            persist(sStatus, true);
                        }
                    } catch (Throwable ignore) {
                        // 轮询失败不影响目标 App
                    }
                    handler.postDelayed(this, POLL_INTERVAL_MS);
                }
            }, POLL_INTERVAL_MS);
            XLog.i("已启动连接状态轮询（每 " + (POLL_INTERVAL_MS / 1000) + "s，仅变化时写盘）");
        } catch (Throwable t) {
            XLog.w("启动连接状态轮询失败：" + t);
        }
    }

    static String status() {
        return sStatus;
    }

    // ------------------------------------------------------------------

    private static void hookEventBusPost() {
        Class<?> eventBus = RongGuard.findClassIfExists("io.rong.eventbus.EventBus");
        if (eventBus == null) {
            XLog.w("未找到 io.rong.eventbus.EventBus，连接状态将只能靠轮询");
            return;
        }
        int hooked = 0;
        for (Method m : eventBus.getDeclaredMethods()) {
            if (!"post".equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 1) {
                            return;
                        }
                        onEvent(param.args[0]);
                    }
                });
                hooked++;
            } catch (Throwable t) {
                XLog.w("挂钩 EventBus#post 失败（忽略）：" + t);
            }
        }
        XLog.i("已挂钩 EventBus#post 共 " + hooked + " 个（用于跟踪融云连接状态）");
    }

    private static void onEvent(Object event) {
        if (event == null) {
            return;
        }
        String className;
        try {
            className = event.getClass().getName();
        } catch (Throwable t) {
            return;
        }
        if (className == null || !className.contains("ConnectionStatus")) {
            return;
        }
        record(String.valueOf(event));
    }

    private static void record(String value) {
        if (value == null || value.isEmpty() || value.equals(sStatus)) {
            return;
        }
        sStatus = value;
        persist(sStatus, false);
    }

    /**
     * 把状态落盘（写共享状态文件 + logcat 标记）。
     *
     * <p>踩过的坑：之前用"1 秒节流 + 只在变化时写"，结果状态刚好落在节流窗口里时更新被永久丢弃
     * （标记行显示 CONNECTED，而共享文件一直停在 CONNECTING）。改成按"是否与已落盘值不同"判断，
     * 并让轮询周期性地再确认一次，保证最终一致。</p>
     */
    private static void persist(String value, boolean force) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!force && value.equals(sWritten)) {
            return;
        }
        String previous = sWritten;
        sWritten = value;
        boolean ok = StateFile.write(sContext, "ImStatusWatcher", value);
        if (!ok) {
            // 写失败（例如外部存储尚未挂载）时必须回滚，否则这个状态在非 force 路径下
            // 会被 sWritten 永久去重掉 —— 表现为共享状态文件一直停在旧值（踩过）。
            sWritten = previous;
            XLog.w("状态落盘失败，已回滚去重标记（状态=" + value + "），等下一轮重试");
        }
    }

    /** 反射读一次 {@code RongIMClient.getInstance().getCurrentConnectionStatus()}。 */
    private static String pollCurrentStatus() {
        try {
            Class<?> clientClass = RongGuard.findClassIfExists("io.rong.imlib.RongIMClient");
            if (clientClass == null) {
                return null;
            }
            Object client = XposedHelpersBridge.callStatic(clientClass, "getInstance");
            if (client == null) {
                return null;
            }
            Object status = XposedHelpersBridge.call(client, "getCurrentConnectionStatus");
            return status == null ? null : String.valueOf(status);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只用到很小的反射能力，避免额外依赖。 */
    private static final class XposedHelpersBridge {
        static Object callStatic(Class<?> clazz, String name) {
            try {
                Method m = clazz.getMethod(name);
                m.setAccessible(true);
                return m.invoke(null);
            } catch (Throwable t) {
                return null;
            }
        }

        static Object call(Object target, String name) {
            try {
                Method m = target.getClass().getMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
