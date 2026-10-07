package com.dsh.nahangfix;

/**
 * 在设备上自动完成“复现 / 验证”流程（全部通过 su 执行）。
 *
 * <pre>
 * 1. 按下开关（复现=放置 nahangfix.off；验证=删除）
 * 2. 冷启动目标 App，等待进入主页
 * 3. 校验模块确实已注入（日志里出现 InitInterceptor）
 * 4. 回桌面，让 Activity 写入保存状态
 * 5. kill -9 主进程
 * 6. am start MainActivity 恢复任务栈
 * 7. 抓 logcat 判定：原始 NPE / 其它崩溃 / 连接成功
 * </pre>
 */
final class ReproRunner {

    interface Callback {
        /** 当前步骤（显示在按钮下方）。 */
        void onStep(String text);

        /** 追加一行日志。 */
        void onLog(String line);

        /** 结束：verdict 结论文本；crashed 是否出现崩溃；passed 是否符合本次预期。 */
        void onFinish(String verdict, boolean crashed, boolean passed);
    }

    private static volatile boolean sRunning;
    /** 保护 sRunning 的"检查 + 置位"，避免两条流程并发。 */
    private static final Object LOCK = new Object();
    /** 最近一次步骤/结论（供界面恢复与落盘派发使用）。 */
    private static volatile String sLastStep = "";
    /** 待派发的步骤文本（流程线程只写这里，派发线程负责发通知 + 落盘）。 */
    private static final java.util.concurrent.atomic.AtomicReference<String> sPendingStep =
            new java.util.concurrent.atomic.AtomicReference<String>();
    private static volatile boolean sDispatcherStarted;
    /** 通知更新是否有一个线程在飞（notify 可能被 binder 拖住，需要超时自愈）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sNotifyInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long sNotifyStart;
    /** 本次流程开始时间（用于每步显示已用时）。 */
    private static volatile long sFlowStart;
    /** 系统"隐藏错误对话框"的原始值（复现时改成 0，结束后还原）。 */
    private static volatile String sSavedHideDialogs;
    /** 系统"首次崩溃对话框"的原始值（复现时改成 1，结束后还原）。 */
    private static volatile String sSavedFirstCrash;
    /** 流程总超时（毫秒）：超时强制收尾，避免看起来"卡死"。正常一次 1 分钟左右。 */
    private static final long FLOW_TIMEOUT_MS = 4 * 60 * 1000L;

    private ReproRunner() {
    }

    static boolean isRunning() {
        return sRunning;
    }

    static String lastStep() {
        return sLastStep;
    }

    /**
     * 启动流程。
     *
     * @param expectCrash true = 复现崩溃（关闭修复、放置 nahangfix.off，期望出现原始崩溃）；
     *                    false = 验证补丁（开启修复、删除 nahangfix.off，期望不崩且连接成功）
     * @return 是否真正启动；false 表示已有任务在运行（调用方据此不要把界面锁成"运行中"）
     */
    static boolean start(final boolean expectCrash, final Callback cb) {
        // 用类锁把"检查 + 置位"做成原子操作：否则同时点两个按钮（或按钮 + autotest）
        // 可能两个调用都通过 sRunning 检查，导致两条流程并发操作同一个设备。
        synchronized (LOCK) {
            if (sRunning) {
                cb.onLog("[!] 已有任务在运行，本次请求被忽略");
                return false;
            }
            sRunning = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    execute(expectCrash, cb);
                } catch (Throwable e) {
                    cb.onLog("[!] 任务异常：" + e);
                    cb.onFinish("任务异常终止：" + e, false, false);
                } finally {
                    synchronized (LOCK) {
                        sRunning = false;
                    }
                }
            }
        }, "nahangfix-repro");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void execute(boolean expectCrash, Callback cb) {
        long flowStart = System.currentTimeMillis();
        sFlowStart = flowStart;
        startStepDispatcher();   // 通知/落盘全部走独立线程，流程线程不被 binder 拖住
        startWatchdog(cb);
        acquireWakeLock();
        // 屏幕必须全程亮着：实测中途熄屏会让 monkey/am 变慢甚至卡住（"卡在恢复任务栈"就是它）
        prepareScreenAndDialogs(cb, expectCrash);
        step(cb, "检查 root 权限…");
        RootShell.Result id = RootShell.run("id", 20);
        if (!(id.ok() && id.output.contains("uid=0"))) {
            log(cb, "[x] su 不可用或未授权：" + id.firstLine());
            finish(cb, "需要 root 权限：请在 KernelSU / Magisk 中授权「蓝航修复」后重试", false, false);
            return;
        }
        log(cb, "[ok] root 已授权：" + id.firstLine());

        // 1) 开关
        step(cb, expectCrash ? "关闭修复（放置 nahangfix.off）…" : "开启修复（删除 nahangfix.off）…");
        RootShell.Result sw = PatchState.setRepairsEnabled(!expectCrash);
        log(cb, (sw.ok() ? "[ok] " : "[x] ") + (expectCrash
                ? "已放置 " + PatchState.OFF_FILE
                : "已删除 " + PatchState.OFF_FILE));
        if (!sw.ok()) {
            log(cb, "    输出：" + sw.firstLine());
        }

        // 2) 冷启动（一条 su 命令里完成：force-stop + 清日志 + 拉起）
        step(cb, "1/6 冷启动目标 App…");
        runSlow(cb, "冷启动目标 App", "am force-stop " + PatchState.TARGET + "; logcat -c;"
                + " monkey -p " + PatchState.TARGET + " -c android.intent.category.LAUNCHER 1", 70);
        sleep(cb, 20, "等待 App 进入主页 / 消息页");

        // 3) 注入校验：心跳文件里的 pid 必须等于当前目标进程 pid（最多重试 3 次）
        step(cb, "校验模块是否注入…");
        PatchState st = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Runnable stopHb = beginHeartbeat(cb, "校验模块是否注入（第 " + attempt + "/3 次）");
            try {
                st = PatchState.load(flowStart);
            } finally {
                stopHb.run();
            }
            log(cb, "[diag#" + attempt + "] " + st.diagnostics
                    + " targetPid=[" + st.pid + "] heartbeatPid=[" + st.heartbeatPid + "]"
                    + " offSeen=" + st.offSeen);
            if (Boolean.TRUE.equals(st.moduleInjected)) {
                break;
            }
            if (attempt < 3) {
                log(cb, st.pid.isEmpty()
                        ? "[!] 目标 App 还没起来，10s 后重试"
                        : "[!] 心跳不是本次进程（可能是上一个进程留下的），10s 后重试");
                sleep(cb, 10, "重试注入校验");
            }
        }
        boolean injected = st != null && Boolean.TRUE.equals(st.moduleInjected);
        log(cb, injected ? "[ok] 模块已注入目标进程（pid=" + st.heartbeatPid + "）" : "[x] 模块未注入");
        if (!injected) {
            finish(cb, "模块未注入（心跳 pid 与目标进程不匹配）：请在 LSPosed 中启用「蓝航修复」"
                    + "，再用「强制停止目标 App」后重试（用 adb 覆盖安装模块会重置启用状态）", false, false);
            return;
        }
        Boolean wantOff = Boolean.valueOf(expectCrash);
        if (st.offSeen != null && !st.offSeen.equals(wantOff)) {
            log(cb, "[!] 模块看到的开关状态=" + st.offSeen + "，与本次预期=" + wantOff
                    + " 不一致（可能日志/心跳来自上一个进程）");
        } else if (st.offSeen != null) {
            log(cb, "[ok] 模块已按开关状态运行：" + (wantOff ? "只观测不修复" : "正常修复"));
        }

        // 4) 回桌面保存状态
        step(cb, "2/6 回桌面（写入保存状态）…");
        runSlow(cb, "回桌面", "input keyevent 3", 45);
        sleep(cb, 5, "");

        // 5) 杀主进程（一条 su 命令里完成取 pid + kill，减少 su 调用次数）
        step(cb, "3/6 杀掉主进程…");
        RootShell.Result killRes = runSlow(cb, "杀掉主进程",
                "p=$(pidof " + PatchState.TARGET + "); echo pid=$p;"
                        + " if [ -n \"$p\" ]; then kill -9 $p && echo killed; else echo noproc; fi", 120);
        if (!killRes.output.contains("killed")) {
            finish(cb, "没有找到目标进程（可能被手动停止），复现未命中，请重试", false, false);
            return;
        }
        sleep(cb, 4, "");

        // 6) 恢复任务栈 + 在 App 侧盯崩溃。
        //    为什么不用设备端 while / 管道：实测 **命令里带 `| grep` 这类管道会让 su 会话不返回**，
        //    整条命令挂住 → 流程卡在这一步。改用与状态探测相同的"标记分段、无管道"写法，
        //    匹配放在 Java 侧做；crash 缓冲在 logcat -c 之后只会有本次崩溃，内容很小（几十行）。
        step(cb, "4/6 清空日志并恢复任务栈…");
        RootShell.Result clear = runSlow(cb, "清空日志", "logcat -c", 40);
        if (!clear.ok()) {
            log(cb, "[!] 清空日志失败：" + clear.firstLine());
        }
        // 用 `am start -n <MainActivity>`（不后台、不等待）把已有任务栈恢复到前台：
        // 它只把启动请求交给 ActivityManager 就返回，会走"带 savedInstanceState 恢复"的路径。
        // 不要用 monkey：目标 App 一启动就崩时，monkey 会一直等它"稳定"，实测阻塞好几分钟
        // （表现就是"卡在清空日志并恢复任务栈"）。
        RootShell.Result restore = runSlow(cb, "恢复任务栈（am start）",
                "am start -n " + PatchState.MAIN_ACTIVITY, 45);
        log(cb, "[ok] 已恢复任务栈（am start，等价于点桌面图标恢复）"
                + (restore.output.trim().isEmpty() ? "" : " -> " + oneLine(restore.output)));

        // 崩溃发生在恢复后 1~2 秒内，通常第 1~2 轮就能命中
        String verdictTag = "none";
        String crashTail = "";
        for (int round = 1; round <= 5; round++) {
            step(cb, "4/6 盯崩溃：第 " + round + "/5 轮…");
            // 每轮也就十几秒，但带个心跳让通知一直在动，不会看起来"卡住"
            Runnable stopHb = beginHeartbeat(cb, "盯崩溃：第 " + round + "/5 轮");
            RootShell.Result r;
            try {
                r = RootShell.run(
                        "echo @@CRASH@@; logcat -d -b crash -v brief -t 300 2>&1;"
                                + " echo @@PID@@; pidof " + PatchState.TARGET + " 2>&1;"
                                + " echo @@END@@", 45);
            } finally {
                stopHb.run();
            }
            String o = r.output == null ? "" : r.output;
            String crashPart = part(o, "@@CRASH@@", "@@PID@@");
            String pidPart = part(o, "@@PID@@", "@@END@@");
            boolean hasNpe = crashPart.contains("getEventBus") || crashPart.contains("NullPointerException");
            int alive = countWords(pidPart);
            log(cb, "[watch " + round + "] crash缓冲命中=" + (hasNpe ? 1 : 0)
                    + " 目标进程数=" + (alive < 0 ? "未知" : String.valueOf(alive))
                    + (r.ok() ? "" : "（命令超时/失败）"));
            if (hasNpe) {
                verdictTag = "npe";
                crashTail = crashPart.trim();
                // 系统崩溃框在 MIUI 上被抑制，这里补一条高优先级通知，保证用户看得到
                ReproService.notifyCrash("原始崩溃已复现：RongContext.getEventBus() 空指针"
                        + "（第 " + round + " 轮捕获，crash 缓冲）");
                break;
            }
            if (round >= 3 && alive == 0) {
                verdictTag = "dead";   // 进程没了但 crash 缓冲没留痕（例如被系统清理）
                ReproService.notifyCrash("目标 App 进程在恢复任务栈后消失（判为崩溃，未取到 crash 缓冲）");
                break;
            }
            sleep(cb, 2, "");
        }
        if (!crashTail.isEmpty()) {
            log(cb, "[crash 缓冲尾部] " + oneLine(crashTail));
        }
        log(cb, "[ok] 盯崩溃结果：" + verdictTag);

        // 7) 判定：设备端结论优先，其次再扫一次 logcat / 共享状态文件
        step(cb, "5/6 分析日志…");
        Runnable stopProbe = beginHeartbeat(cb, "读取状态（su 较慢，请稍候）");
        PatchState fs;
        try {
            fs = PatchState.load(flowStart);
        } finally {
            stopProbe.run();
        }
        log(cb, "[diag] " + fs.diagnostics);
        String out = fs.rawLog + "\n" + crashTail;
        boolean rongNpe = "npe".equals(verdictTag)
                || "dead".equals(verdictTag)
                || out.contains("getEventBus()' on a null")
                || out.contains("NullPointerException: Attempt to invoke virtual method 'io.rong.eventbus.EventBus");
        boolean fatal = out.contains("FATAL EXCEPTION");
        // 连接是否成功：优先用模块上报的状态（logcat 里的 "to = CONNECTED" 会被轮转冲掉）
        boolean connected = Boolean.TRUE.equals(fs.imConnected)
                || out.contains("to = CONNECTED");
        log(cb, "统计： RongContextNPE=" + (rongNpe ? "1" : "0")
                + "  FATAL=" + (fatal ? "1" : "0")
                + "  CONNECTED=" + (connected ? "1" : "0")
                + "（imStatus=" + (fs.imStatus.isEmpty() ? "未知" : fs.imStatus)
                + "，盯崩溃=" + verdictTag + "）");

        step(cb, "6/6 判定…");
        String how = "npe".equals(verdictTag) ? "（设备端捕获到原始空指针）"
                : ("dead".equals(verdictTag) ? "（恢复后目标进程立即消失）"
                : (rongNpe ? "（日志中捕获到空指针签名）" : ""));
        if (rongNpe) {
            if (expectCrash) {
                finish(cb, "补丁生效（对照成立）：原始 RongContext.getEventBus() 空指针崩溃已复现"
                        + how + "，说明关闭修复时必然崩溃", true, true);
            } else {
                finish(cb, "补丁无效：仍然出现原始空指针崩溃" + how, true, false);
            }
        } else if (fatal) {
            finish(cb, "出现崩溃（但不是原始 NPE）：" + firstCause(out), true, false);
        } else if (connected) {
            if (expectCrash) {
                finish(cb, "未复现：本次没有崩溃且连接成功。多半没命中恢复场景 ——"
                        + "请确认：①目标 App 被杀前已进入过「消息」页；②是「进程被杀后从任务栈恢复」而不是全新启动",
                        false, false);
            } else {
                finish(cb, "补丁有效：无崩溃，且融云已连接（消息可用）", false, true);
            }
        } else if (!expectCrash) {
            // 修好的情况下即使这次没连上（网络/指纹页等原因），只要 5 轮都没盯到崩溃就算补丁有效
            finish(cb, "补丁有效：5 轮盯崩溃都没有出现崩溃（本次融云未连接，imStatus="
                    + (fs.imStatus.isEmpty() ? "未知" : fs.imStatus)
                    + "，不影响补丁结论）", false, true);
        } else {
            finish(cb, "既没崩溃也没看到连接成功，建议重跑或手动查看日志", false, false);
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 屏幕常亮 + 允许系统崩溃对话框（复现时让系统也能抓到并弹框）。 */
    private static void prepareScreenAndDialogs(Callback cb, boolean expectCrash) {
        RootShell.Result wake = RootShell.run("input keyevent 224", 30);
        log(cb, wake.ok() ? "[ok] 已唤醒屏幕" : "[!] 唤醒屏幕失败：" + wake.firstLine());
        // 屏幕中途黑掉会让 monkey/am 变慢甚至卡住（"卡在恢复任务栈"的元凶）
        RootShell.Result stay = RootShell.run("svc power stayon true", 30);
        log(cb, stay.ok() ? "[ok] 已设置屏幕保持常亮（USB 连接期间）"
                : "[!] 设置常亮失败：" + stay.firstLine());
        if (!expectCrash) {
            return;
        }
        // 复现时让系统也弹"蓝航已停止运行"：MIUI/部分 ROM 默认把错误对话框关掉了。
        // 两个开关都要管：hide_error_dialogs（隐藏所有错误框）+ show_first_crash_dialog（首次崩溃对话框）。
        try {
            sSavedHideDialogs = getGlobal("hide_error_dialogs");
            RootShell.Result p = RootShell.run("settings put global hide_error_dialogs 0", 30);
            sSavedFirstCrash = getGlobal("show_first_crash_dialog");
            RootShell.Result p2 = RootShell.run("settings put global show_first_crash_dialog 1", 30);
            log(cb, (p.ok() && p2.ok())
                    ? "[ok] 已允许系统弹出崩溃对话框（hide_error_dialogs 原值=" + sSavedHideDialogs
                            + "，show_first_crash_dialog 原值=" + sSavedFirstCrash + "）"
                    : "[!] 设置崩溃对话框失败：" + p.firstLine() + " / " + p2.firstLine());
        } catch (Throwable t) {
            log(cb, "[!] 设置崩溃对话框异常：" + t);
        }
    }

    private static String getGlobal(String key) {
        RootShell.Result r = RootShell.run("settings get global " + key, 20);
        return r.firstLine();
    }

    /** 还原上面改动的系统设置。 */
    private static void restoreSystemSettings() {
        try {
            RootShell.run("svc power stayon false", 20);
        } catch (Throwable ignore) {
            // 忽略
        }
        restoreGlobal("hide_error_dialogs", sSavedHideDialogs);
        restoreGlobal("show_first_crash_dialog", sSavedFirstCrash);
    }

    /** 还原一个 global 设置；原本没设置过（null）就删掉。 */
    private static void restoreGlobal(String key, String saved) {
        if (saved == null) {
            return;
        }
        try {
            if ("null".equals(saved) || saved.isEmpty()) {
                RootShell.run("settings delete global " + key, 20);
            } else {
                RootShell.run("settings put global " + key + " " + saved, 20);
            }
        } catch (Throwable ignore) {
            // 忽略
        }
    }

    /**
     * 独立线程负责"通知 + 落盘"，流程线程只投递最新文本。
     *
     * <p>为什么要这样：`NotificationManager.notify()` 是 binder 调用，实测在流程线程上被拖住过
     * ——表现就是"卡在 4/6 清空日志并恢复任务栈…、通知也一直不刷新"（因为 notify 没返回，
     * 后面的步骤和心跳都被卡住）。解耦后流程绝不会被通知系统拖住。</p>
     */
    private static void startStepDispatcher() {
        if (sDispatcherStarted) {
            return;
        }
        sDispatcherStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        String label = sPendingStep.getAndSet(null);
                        if (label != null) {
                            // 只管发通知（落盘由流程线程负责，见 setStep）
                            notifyAsync(label);
                        }
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Throwable e) {
                        // 派发线程绝不允许静默退出
                    }
                }
            }
        }, "nahangfix-step");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 通知更新：单独线程 fire-and-forget + 超时自愈。
     *
     * <p>为什么不能直接调：`NotificationManager.notify()` 是 binder 调用，实测在这台机器上会被
     * 拖住几十秒甚至更久；一旦放在流程线程或派发线程里，就会表现为"步骤卡住、通知不刷新"。
     * 这里每次更新都丢给一个新线程；若上一次超过 8 秒还没回来就放弃它并允许新的更新。</p>
     */
    private static void notifyAsync(final String label) {
        if (sNotifyInFlight.get()) {
            long held = System.currentTimeMillis() - sNotifyStart;
            if (held < 8000) {
                return;   // 上一次还在飞，跳过本次，避免线程堆积
            }
            android.util.Log.w("NahangFix",
                    "上一次通知更新卡住 " + held + "ms，放弃它并继续（通知是 best-effort）");
        }
        sNotifyInFlight.set(true);
        sNotifyStart = System.currentTimeMillis();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ReproService.updateStep(label);
                } catch (Throwable ignore) {
                    // 忽略
                } finally {
                    sNotifyInFlight.set(false);
                }
            }
        }, "nahangfix-notify");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 执行一条可能很慢的 root 命令，并每 2 秒刷新步骤/通知（"已等待 Ns"），避免看起来卡死。
     * 同时把命令与输出写进日志。
     */
    private static RootShell.Result runSlow(Callback cb, String what, String command, int timeout) {
        Runnable stop = beginHeartbeat(cb, what);
        RootShell.Result r;
        try {
            r = RootShell.run(command, timeout);
        } finally {
            stop.run();
        }
        log(cb, (r.ok() ? "$ " : "$(!) ") + command
                + (r.output.trim().isEmpty() ? "" : "  -> " + oneLine(r.output)));
        return r;
    }

    /** 启动一个心跳线程刷新步骤/通知；返回"停止"动作。 */
    private static Runnable beginHeartbeat(final Callback cb, final String what) {
        final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final long start = System.currentTimeMillis();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                // 整个循环包在 try/catch 里：心跳绝不允许静默死掉（死了通知就不刷新了）
                try {
                    while (!done.get()) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (done.get()) {
                            return;
                        }
                        long s = (System.currentTimeMillis() - start) / 1000;
                        // 同样不写 logcat（Log.i 可能被 logd 反压阻塞，见 setStep 注释）
                        setStep(cb, "[" + elapsed() + "] " + what + "（已等待 " + s + "s）");
                    }
                } catch (Throwable e) {
                    android.util.Log.w("NahangFix", "心跳线程异常退出", e);
                }
            }
        }, "nahangfix-heartbeat");
        t.setDaemon(true);
        t.start();
        return new Runnable() {
            @Override
            public void run() {
                done.set(true);
            }
        };
    }

    /** 单行摘要：截断过长的命令输出，避免日志被一行刷屏。 */
    private static String oneLine(String s) {
        String t = s.trim().replace('\n', ' ');
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    /** 取两个标记之间的内容（无管道命令的分段解析）。 */
    private static String part(String text, String begin, String end) {
        if (text == null) {
            return "";
        }
        int from = 0;
        if (begin != null) {
            int i = text.indexOf(begin);
            if (i < 0) {
                return "";
            }
            from = i + begin.length();
        }
        int to = text.length();
        if (end != null) {
            int i = text.indexOf(end, from);
            if (i >= 0) {
                to = i;
            }
        }
        return text.substring(from, to);
    }

    /** 统计空白分隔的单词数（pidof 输出几个 pid 就是几个进程）；无内容返回 0，解析不到返回 -1。 */
    private static int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        String t = text.trim();
        if (t.contains("not found") || t.contains("Permission denied") || t.contains("No such")) {
            return -1;
        }
        int n = 0;
        for (String w : t.split("\\s+")) {
            if (w.matches("\\d+")) {
                n++;
            } else {
                return -1;
            }
        }
        return n;
    }

    /** 解析行首数字；解析不到返回 -1（表示"未知"，不能当成 0）。 */
    private static int parseIntSafe(String s) {
        if (s == null) {
            return -1;
        }
        String t = s.trim();
        int end = 0;
        while (end < t.length() && Character.isDigit(t.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(t.substring(0, end));
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String firstCause(String log) {
        for (String line : log.split("\n")) {
            if (line.contains("Caused by")) {
                return oneLine(line);
            }
        }
        for (String line : log.split("\n")) {
            if (line.contains("FATAL EXCEPTION")) {
                return oneLine(line);
            }
        }
        return "（未取到堆栈）";
    }

    private static void log(Callback cb, String line) {
        cb.onLog(line);
    }

    private static void step(Callback cb, String text) {
        // 带上已用时：本机每条 su 命令都要 10s+，不显示进度会让人以为卡死
        setStep(cb, "[" + elapsed() + "] " + text);
    }

    /**
     * 统一设置"当前步骤"：同时更新界面那一行、通知栏、以及 {@link #sLastStep}。
     *
     * <p>踩过的坑：finish() 之前只用 cb.onStep() 更新界面，没有更新 sLastStep 和通知，
     * 结果流程跑完后一旦 onResume/刷新，界面就被还原成"盯崩溃：第 N/5 轮"，通知也停在那句。</p>
     */
    private static void setStep(Callback cb, String label) {
        sLastStep = label;
        // 注意：这里**不能写 logcat**（Log.i 在日志量极大时会因 logd 反压而阻塞，
        // 实测把整步卡死）。进度证据走 prefs 的 step_history（apply() 是非阻塞的）。
        try {
            android.content.Context ctx = ReproService.appContext();
            if (ctx != null) {
                android.content.SharedPreferences sp = ctx.getSharedPreferences(
                        "nahangfix_ui", android.content.Context.MODE_PRIVATE);
                String hist = sp.getString("step_history", "");
                StringBuilder sb = new StringBuilder(hist == null ? "" : hist);
                sb.append(System.currentTimeMillis()).append(' ').append(label).append('\n');
                if (sb.length() > 6000) {
                    sb.delete(0, sb.length() - 6000);
                }
                sp.edit().putString("last_step", label)
                        .putString("step_history", sb.toString()).apply();
            }
        } catch (Throwable ignore) {
            // 落盘失败不影响流程
        }
        // 通知交给独立线程（notify 是 binder 调用，可能被拖住）
        sPendingStep.set(label);
        cb.onStep(label);
    }

    private static android.os.PowerManager.WakeLock sWakeLock;

    /** 流程期间持有 CPU 唤醒锁，避免系统休眠把流程挂住。 */
    private static void acquireWakeLock() {
        try {
            if (sWakeLock != null && sWakeLock.isHeld()) {
                return;
            }
            android.content.Context ctx = ReproService.appContext();
            if (ctx == null) {
                return;
            }
            android.os.PowerManager pm = (android.os.PowerManager)
                    ctx.getSystemService(android.content.Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            sWakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "nahangfix:repro");
            sWakeLock.setReferenceCounted(false);
            sWakeLock.acquire(FLOW_TIMEOUT_MS + 60_000L);
        } catch (Throwable t) {
            XLog.w("获取唤醒锁失败（忽略）：" + t);
        }
    }

    private static void releaseWakeLock() {
        try {
            if (sWakeLock != null && sWakeLock.isHeld()) {
                sWakeLock.release();
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        sWakeLock = null;
    }

    private static String elapsed() {
        long ms = System.currentTimeMillis() - sFlowStart;
        if (sFlowStart <= 0) {
            return "00:00";
        }
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /**
     * 总超时兜底：本机 su 调用很慢，个别命令仍可能长时间不返回。
     * 超过 {@link #FLOW_TIMEOUT_MS} 就强制收尾，避免按钮一直"运行中"。
     */
    private static void startWatchdog(final Callback cb) {
        Thread w = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(FLOW_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (sRunning && System.currentTimeMillis() - sFlowStart >= FLOW_TIMEOUT_MS) {
                    sRunning = false;   // 让按钮/流程可以重新开始
                    cb.onLog("[!] 流程超过 " + (FLOW_TIMEOUT_MS / 60000) + " 分钟仍未结束，已强制收尾");
                    finish(cb, "流程超时（" + (FLOW_TIMEOUT_MS / 60000)
                            + " 分钟）已中止：本机 su 调用较慢，请重试或改用命令行脚本", false, false);
                }
            }
        }, "nahangfix-watchdog");
        w.setDaemon(true);
        w.start();
    }

    private static void sleep(Callback cb, int seconds, String note) {
        for (int i = seconds; i > 0; i--) {
            // 倒计时也走 setStep：这样界面/通知/恢复用的 sLastStep 始终一致
            setStep(cb, "[" + elapsed() + "] "
                    + (note == null || note.isEmpty() ? "等待 " : note + " ") + i + "s");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void finish(Callback cb, String verdict, boolean crashed, boolean passed) {
        releaseWakeLock();
        restoreSystemSettings();
        // 结论也要写回 step/通知：否则流程结束后界面与通知会残留"盯崩溃：第 N/5 轮"
        setStep(cb, (passed ? "✔ " : "✘ ") + verdict);
        cb.onLog("");
        cb.onLog("==> 结论：" + verdict);
        cb.onFinish(verdict, crashed, passed);
    }
}
