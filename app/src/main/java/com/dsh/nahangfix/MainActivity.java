package com.dsh.nahangfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块界面（Miuix / MIUI 风格）：状态面板 + 补丁开关 + 一键复现/验证 + 诊断。
 *
 * <p>所有需要 root 的动作都放在后台线程执行，避免阻塞主线程；首次执行时
 * KernelSU / Magisk 会弹出授权请求。</p>
 */
public final class MainActivity extends Activity {

    private static final String PREFS = "nahangfix_ui";
    private static final String KEY_LOG = "log";
    /** 最近一次步骤/结论（重进 App 后继续显示，不再残留中途的"盯崩溃：第 N/5 轮"）。 */
    private static final String KEY_STEP = "last_step";

    private TextView tvHeaderStatus;
    private TextView tvInject;
    private TextView tvPatch;
    private TextView tvIm;
    private TextView tvPid;
    private TextView tvRootHint;
    private TextView tvStep;
    private TextView tvLog;

    private View dotInject;
    private View dotPatch;
    private View dotIm;
    private View dotPid;

    private Switch switchRepairs;
    private Switch switchHideIcon;
    private boolean suppressHideIconCallback;
    private Button btnRepro;
    private Button btnVerify;
    private ScrollView logScroll;

    private boolean suppressSwitchCallback;
    private final StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvHeaderStatus = findViewById(R.id.tvHeaderStatus);
        tvInject = findViewById(R.id.tvInject);
        tvPatch = findViewById(R.id.tvPatch);
        tvIm = findViewById(R.id.tvIm);
        tvPid = findViewById(R.id.tvPid);
        tvRootHint = findViewById(R.id.tvRootHint);
        tvStep = findViewById(R.id.tvStep);
        tvLog = findViewById(R.id.tvLog);
        dotInject = findViewById(R.id.dotInject);
        dotPatch = findViewById(R.id.dotPatch);
        dotIm = findViewById(R.id.dotIm);
        dotPid = findViewById(R.id.dotPid);
        switchRepairs = findViewById(R.id.switchRepairs);
        switchHideIcon = findViewById(R.id.switchHideIcon);
        btnRepro = findViewById(R.id.btnRepro);
        btnVerify = findViewById(R.id.btnVerify);
        logScroll = findViewById(R.id.logScroll);
        TextView tvVersion = findViewById(R.id.tvVersion);
        TextView tvAbout = findViewById(R.id.tvAbout);

        tvVersion.setText("NahangFix v1.3.0 · Xposed 模块 · 需要 root");
        tvAbout.setText(ABOUT);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString(KEY_LOG, "");
        if (saved != null && saved.length() > 0) {
            logBuffer.append(saved);
            tvLog.setText(logBuffer.toString());
        }
        // 恢复上次的步骤/结论：避免重进 App 时步骤行是空的（或在流程运行中被中途文案覆盖）
        if (!ReproRunner.isRunning()) {
            String lastStep = sp.getString(KEY_STEP, "");
            if (lastStep != null && lastStep.length() > 0) {
                tvStep.setText(lastStep);
            }
        }

        switchRepairs.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (suppressSwitchCallback) {
                    return;
                }
                applySwitch(isChecked);
            }
        });

        switchHideIcon.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (suppressHideIconCallback) {
                    return;
                }
                applyHideIcon(isChecked);
            }
        });

        findViewById(R.id.rowRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshState();
            }
        });
        findViewById(R.id.rowForceStop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                background(new Runnable() {
                    @Override
                    public void run() {
                        appendLog("$ am force-stop " + PatchState.TARGET);
                        RootShell.Result r = RootShell.run("am force-stop " + PatchState.TARGET, 20);
                        appendLog(r.ok() ? "[ok] 已强制停止，Hook 将在下次启动生效" : "[x] 执行失败");
                        refreshState();
                    }
                });
            }
        });
        findViewById(R.id.rowCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("NahangFix", logBuffer.toString()));
                    toast("日志已复制到剪贴板");
                }
            }
        });
        findViewById(R.id.rowClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logBuffer.setLength(0);
                tvLog.setText("");
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_LOG).apply();
            }
        });

        btnRepro.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAndRun(true);
            }
        });
        btnVerify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAndRun(false);
            }
        });

        refreshState();
        handleAutoTest(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutoTest(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ReproRunner.isRunning()) {
            // 流程仍在后台运行：恢复界面上的进度提示
            btnRepro.setEnabled(false);
            btnVerify.setEnabled(false);
            tvStep.setText(ReproRunner.lastStep());
            return;
        }
        refreshState();
    }

    /**
     * 命令行触发流程（便于纯 adb 调试，不需要点界面）：
     *
     * <pre>
     * adb shell am start -n com.dsh.nahangfix/.MainActivity --es autotest verify
     * adb shell am start -n com.dsh.nahangfix/.MainActivity --es autotest repro
     * </pre>
     */
    private void handleAutoTest(Intent intent) {
        if (intent == null) {
            return;
        }
        final String mode = intent.getStringExtra("autotest");
        if (mode == null || mode.isEmpty()) {
            return;
        }
        final boolean expectCrash = "repro".equalsIgnoreCase(mode) || "crash".equalsIgnoreCase(mode);
        if (ReproRunner.isRunning()) {
            appendLog("[!] autotest=" + mode + " 忽略：已有任务在运行");
            return;
        }
        appendLog("==== autotest=" + mode + "（命令行触发）====");
        tvStep.postDelayed(new Runnable() {
            @Override
            public void run() {
                runRepro(expectCrash);
            }
        }, 2000);
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                }
            } catch (Throwable ignore) {
                // 不影响主流程
            }
        }
    }

    // ------------------------------------------------------------------ 状态

    private void refreshState() {
        background(new Runnable() {
            @Override
            public void run() {
                final PatchState s = PatchState.load();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        render(s);
                    }
                });
            }
        });
    }

    private void render(PatchState s) {
        setDot(dotInject, s.moduleInjected == null ? R.color.state_unknown
                : (s.moduleInjected ? R.color.state_ok : R.color.state_bad));
        tvInject.setText(s.moduleInjected == null ? "未知"
                : (s.moduleInjected ? "已注入" : "未注入"));

        setDot(dotPatch, s.repairsEnabled ? R.color.state_ok : R.color.state_warn);
        tvPatch.setText(s.repairsEnabled ? "已开启" : "只观测（不修复）");

        String blocker = s.targetUiBlocker();
        setDot(dotIm, s.imConnected == null ? R.color.state_unknown
                : (s.imConnected ? R.color.state_ok : R.color.state_warn));
        // 连接状态优先展示模块上报的原始状态串，信息量更大（如 CONNECTED / CONNECTING / TOKEN_INCORRECT）；
        // 若目标 App 还停在指纹/登录/启动页，直接说明原因——而不是笼统显示"未连接"
        if (Boolean.TRUE.equals(s.imConnected)) {
            tvIm.setText("已连接");
        } else if (!blocker.isEmpty()) {
            tvIm.setText("未连接（" + blocker + "）");
        } else if (s.imConnected == null) {
            tvIm.setText(s.imStatus.isEmpty() || "unknown".equalsIgnoreCase(s.imStatus)
                    ? "未知" : s.imStatus);
        } else {
            tvIm.setText("未连接" + (s.imStatus.isEmpty() ? "" : "（" + s.imStatus + "）"));
        }

        setDot(dotPid, s.pid.isEmpty() ? R.color.state_unknown : R.color.state_ok);
        tvPid.setText(s.pid.isEmpty() ? "未运行" : ("运行中 " + s.pid));

        if (switchRepairs.isChecked() != s.repairsEnabled) {
            // 程序化同步开关状态时必须屏蔽回调，否则会反过来写一次开关文件
            suppressSwitchCallback = true;
            switchRepairs.setChecked(s.repairsEnabled);
            suppressSwitchCallback = false;
        }

        boolean iconHidden = isLauncherIconHidden();
        if (switchHideIcon.isChecked() != iconHidden) {
            suppressHideIconCallback = true;
            switchHideIcon.setChecked(iconHidden);
            suppressHideIconCallback = false;
        }

        tvRootHint.setText((s.rootOk
                ? "root：" + s.rootMessage
                : "root：" + s.rootMessage + "\n首次使用请在弹出的授权框中允许本应用获得 root。")
                + "\n模块状态文件：" + PatchState.formatTs(s.heartbeatTs)
                + "（" + s.injectionDetail + "）"
                + "\n连接状态来源：" + (s.imSource.isEmpty() ? "—" : s.imSource)
                + (s.imStatus.isEmpty() ? "" : "，imStatus=" + s.imStatus)
                + "\n前台 Activity：" + s.topActivityShort()
                + "\n" + s.diagnostics
                + "\n桌面图标：" + (iconHidden ? "已隐藏（可用 adb am start 打开）" : "显示中"));

        String summary;
        if (!s.rootOk) {
            summary = "未获得 root，无法读取开关与日志（授权后点「刷新状态」）";
        } else if (s.moduleInjected == null) {
            summary = "模块状态未知：" + s.injectionDetail
                    + "。若刚启用模块，请「强制停止目标 App」后重新打开，再点「刷新状态」";
        } else if (!s.moduleInjected) {
            summary = "模块未注入：" + s.injectionDetail
                    + "。请在 LSPosed 中启用「蓝航修复」并勾选作用域 " + PatchState.TARGET
                    + "，再用「强制停止目标 App」重启它";
        } else if (!s.repairsEnabled) {
            summary = "当前为「只观测」模式：适合复现原始崩溃";
        } else if (Boolean.TRUE.equals(s.imConnected)) {
            summary = "修复已生效，融云已连接（消息可用）";
        } else if (!blocker.isEmpty()) {
            summary = "修复已生效；融云尚未连接：" + blocker
                    + "（完成指纹/登录进入主界面后会自动连接）";
        } else {
            summary = "修复已生效；融云未连接（" + (s.imStatus.isEmpty() ? "状态未知" : s.imStatus)
                    + "），可打开目标 App 的消息页后再刷新";
        }
        tvHeaderStatus.setText(summary);
    }

    // ------------------------------------------------------------------ 桌面图标开关

    private static final String LAUNCHER_ALIAS = "com.dsh.nahangfix.Launcher";

    /** 桌面图标是否处于隐藏状态。 */
    private boolean isLauncherIconHidden() {
        try {
            int state = getPackageManager().getComponentEnabledSetting(
                    new android.content.ComponentName(this, LAUNCHER_ALIAS));
            return state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void applyHideIcon(final boolean hide) {
        background(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    getPackageManager().setComponentEnabledSetting(
                            new android.content.ComponentName(MainActivity.this, LAUNCHER_ALIAS),
                            hide ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                    : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP);
                    result = "成功";
                } catch (Throwable t) {
                    result = "失败 " + t;
                }
                appendLog((hide ? "隐藏桌面图标：" : "恢复桌面图标：") + result
                        + (hide ? "（仍可在 LSPosed 模块列表里「打开」本应用；"
                                + "也可 adb shell am start -n com.dsh.nahangfix/.MainActivity）" : ""));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshState();
                    }
                });
            }
        });
    }

    private void setDot(View dot, int colorRes) {
        // 用 getResources().getColor 而不是 Activity#getColor（后者需要 API 23）
        dot.setBackgroundTintList(ColorStateList.valueOf(getResources().getColor(colorRes)));
    }

    private void applySwitch(final boolean enabled) {
        background(new Runnable() {
            @Override
            public void run() {
                RootShell.Result r = PatchState.setRepairsEnabled(enabled);
                appendLog((enabled ? "开启修复：" : "关闭修复（只观测）：")
                        + (r.ok() ? "成功" : "失败 " + r.firstLine()));
                if (enabled && r.ok()) {
                    appendLog("提示：开关按进程缓存，切换后请「强制停止目标 App」再打开。");
                }
                refreshState();
            }
        });
    }

    // ------------------------------------------------------------------ 复现 / 验证

    private void confirmAndRun(final boolean expectCrash) {
        if (ReproRunner.isRunning()) {
            toast("已有任务在运行");
            return;
        }
        String title = expectCrash ? "复现原始崩溃" : "验证补丁";
        String message = expectCrash
                ? "将关闭修复（放置 nahangfix.off），随后自动执行：\n"
                  + "冷启动蓝航 → 回桌面 → 杀主进程 → 恢复任务栈。\n\n"
                  + "预期：出现 RongContext.getEventBus() 空指针崩溃。\n"
                  + "前提：蓝航此前进入过「消息」页。"
                : "将开启修复（删除 nahangfix.off），随后执行同一套复现步骤。\n\n"
                  + "预期：不崩溃，且融云连接成功（消息可用）。";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runRepro(expectCrash);
                    }
                })
                .show();
    }

    private void runRepro(final boolean expectCrash) {
        // 先确认能真正启动，再锁界面/切开关：否则若已有任务在跑，这里会把开关和按钮
        // 锁成"运行中"，而实际流程并没启动，界面就永久卡住了（竞争窗口）。
        // 真正的开关文件由流程第一步写入，这里只让界面与之一致，不用手动拨开关。
        final ReproRunner.Callback cb = buildReproCallback();
        if (!ReproRunner.start(expectCrash, cb)) {
            toast("已有任务在运行，请等它结束");
            return;
        }
        // 启动成功后再同步界面状态：一键复现崩溃 → 关闭修复（只观测）；一键验证补丁 → 开启修复
        suppressSwitchCallback = true;
        switchRepairs.setChecked(!expectCrash);
        suppressSwitchCallback = false;
        switchRepairs.setEnabled(false);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnRepro.setEnabled(false);
        btnVerify.setEnabled(false);
        appendLog("");
        appendLog("==== " + (expectCrash ? "复现原始崩溃" : "验证补丁") + " ====");
        appendLog(expectCrash
                ? "已自动关闭修复（只观测模式）：本次只复现原始崩溃，不做任何修复"
                : "已自动开启修复：本次验证补丁是否能让它不崩且消息可用");

        // 前台服务保活：流程要跑 1~3 分钟，期间目标 App 占前台，本 App 后台进程会被回收
        ReproService.start(this);
        requestNotificationPermission();
    }

    private ReproRunner.Callback buildReproCallback() {
        return new ReproRunner.Callback() {
            @Override
            public void onStep(final String text) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStep.setText(text);
                        // 持久化"当前步骤/结论"：重进 App 也显示结论，同时便于用 run-as 校验。
                        // 倒计时（"… 23s"）不写，避免每秒一次落盘。
                        if (!text.matches(".*\\d+s$")) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putString(KEY_STEP, text).commit();
                        }
                    }
                });
            }

            @Override
            public void onLog(final String line) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        appendLog(line);
                    }
                });
            }

            @Override
            public void onFinish(String verdict, boolean crashed, final boolean passed) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnRepro.setEnabled(true);
                        btnVerify.setEnabled(true);
                        switchRepairs.setEnabled(true);   // 流程结束，放开手动开关
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        ReproService.stop(MainActivity.this);
                        toast((passed ? "✔ " : "✘ ") + verdict);
                        refreshState();
                    }
                });
            }
        };
    }

    // ------------------------------------------------------------------ 通用

    /**
     * 追加一行日志。
     *
     * <p>注意：调用方既可能是主线程（onClick / runOnUiThread），也可能是后台线程
     * （开关切换、强制停止等 root 动作）。后台线程直接操作 View 会抛
     * {@code CalledFromWrongThreadException} 导致崩溃，所以这里统一marshalling 到主线程。</p>
     */
    private void appendLog(final String line) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    appendLog(line);
                }
            });
            return;
        }
        logBuffer.append(line).append('\n');
        if (logBuffer.length() > 20000) {
            logBuffer.delete(0, logBuffer.length() - 20000);
        }
        tvLog.setText(logBuffer.toString());
        // 同步落盘：流程中途进程被回收时也不能丢结论
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LOG, logBuffer.toString()).commit();
        // 注意：这里刻意不做任何自动滚动/请求焦点——否则点按钮后页面会自己跳到日志区
    }

    private void background(Runnable task) {
        Thread t = new Thread(task, "nahangfix-bg");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 安全提示：无论从哪个线程调用都投递到主线程。
     *
     * <p>流程线程也会用它（例如"已有任务在运行"），后台线程直接 {@code Toast.show()}
     * 在某些 ROM 上会抛 {@code CalledFromWrongThreadException}。</p>
     */
    private void toast(final String text) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static final String ABOUT =
            "目标 App：cn.edu.nchu.nahang（蓝航）\n\n"
            + "修复的崩溃：MainActivity 的 Fragment 被同步重建时，融云全局单例 "
            + "RongContext.getInstance() 仍为 null，执行 getEventBus() 抛空指针。\n\n"
            + "五层修复：\n"
            + "1. 启动只观测，绝不替 App 调用融云初始化（避免钉死错误的 AppKey）；\n"
            + "2. 接管 init 入口：释放占位实例、记录 App 真实 AppKey，其余原样放行；\n"
            + "3. getEventBus() 兜底非 null，必要时用占位 RongContext 保命；\n"
            + "4. 框架层 Fragment.performCreate 兜底，最后关头挡住融云空指针；\n"
            + "5. 检测到「带保存状态恢复且 App 未初始化」的 MainActivity 时重定向到启动页。\n\n"
            + "复现条件（三条缺一不可）：\n"
            + "· Fragment 重建早于 App 的融云初始化；\n"
            + "· 必须带 savedInstanceState（进程被杀后从任务栈恢复；force-stop 后全新启动无效）；\n"
            + "· 被杀前 App 已进入过「消息」页。\n\n"
            + "状态判定说明：\n"
            + "· 「模块注入」「融云连接」都来自模块写在目标 App 私有目录里的状态文件"
            + "（nahangfix.state），不再依赖 logcat（日志会轮转，容易误报）；\n"
            + "· 若显示「未知」，多半是目标 App 在启用模块后还没重启过——用「强制停止目标 App」"
            + "再打开即可。\n\n"
            + "隐藏桌面图标：打开该开关后本应用从桌面消失，但仍可在 LSPosed 模块列表里"
            + "「打开」本应用（清单里注册了 CATEGORY_INFO 入口，getLaunchIntentForPackage 会优先命中它）；"
            + "也可执行 adb shell am start -n com.dsh.nahangfix/.MainActivity";
}
