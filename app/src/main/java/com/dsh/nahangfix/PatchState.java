package com.dsh.nahangfix;

/**
 * 运行时状态：全部通过 root 命令探测，不做任何假设。
 *
 * <p>修复开关就是目标 App 私有目录下的标记文件 {@code nahangfix.off}：
 * 存在 = 模块只观测不修复（复现原始崩溃用）；不存在 = 正常修复。</p>
 */
final class PatchState {

    static final String TARGET = FixEntry.TARGET_PACKAGE;
    /** 只观测开关：必须放共享路径（目标私有目录模块 App 写不进/读不到） */
    static final String OFF_FILE = "/sdcard/Android/media/" + TARGET + "/nahangfix.off";
    static final String STATE_FILE = "/data/data/" + TARGET + "/files/" + StateFile.NAME;
    /** 共享状态文件：目标 App 的外部媒体目录，模块 App 在自己命名空间里也能读到 */
    static final String SHARED_DIR = "/sdcard/Android/media/" + TARGET;
    static final String SHARED_FILE = StateFile.sharedPath(TARGET);
    static final String MAIN_ACTIVITY = TARGET + "/.ui.MainActivity";

    boolean rootOk;
    String rootMessage = "";

    /** 修复是否开启（即 off 文件不存在）。 */
    boolean repairsEnabled;
    /** off 文件是否存在（与 repairsEnabled 相反，便于直接展示）。 */
    boolean offFilePresent;

    Boolean moduleInjected;
    Boolean imConnected;
    String pid = "";
    String ipcPid = "";
    /** 心跳文件里的时间戳（毫秒），0 表示没有。 */
    long heartbeatTs;
    /** 心跳文件里记录的进程号：用它和当前目标进程比对，判断是不是"本次"注入。 */
    String heartbeatPid = "";
    /** {@code pidof} 返回的<b>全部</b> pid（目标 App 有主进程 + :ipc 等多个进程）。 */
    java.util.List<String> targetPids = new java.util.ArrayList<String>();
    /** 心跳文件里模块自己看到的"只观测"开关状态（null=没读到）。 */
    Boolean offSeen;
    /** 诊断信息：读取到的 logcat 行数等。 */
    String diagnostics = "";
    /** 注入判定的细节说明（面板上直接展示，避免"未注入"这种无信息量的结论）。 */
    String injectionDetail = "";
    /** 模块上报的融云连接状态原文（imStatus=…）。 */
    String imStatus = "";
    /** 连接状态的来源：模块上报 / logcat。 */
    String imSource = "";
    /** 当前前台 Activity 的组件名（形如 cn.edu.nchu.nahang/.ui.MainActivity）。 */
    String topActivity = "";
    /** 状态来源：logcat 标记 / 状态文件 / 无。 */
    String stateSource = "";
    /** 本次探测读到的 logcat 原文（供判定复用，避免再次 su 调用）。 */
    String rawLog = "";

    static PatchState load() {
        return load(0L);
    }

    /**
     * @param flowStartMs 复现流程开始的时间；心跳晚于它才算"本次已注入"，0 表示按"最近 10 分钟"判断
     */
    static PatchState load(long flowStartMs) {
        PatchState s = new PatchState();

        // 关键：本机每次 su 调用要 10 秒以上（KernelSU 启动开销），所以把多项探测合并成
        // 一次 su 调用，用标记分段解析。分散调用会因为超时/耗时导致读不到内容（实测踩过）。
        String script = "id; echo @@OFF@@; if [ -f " + OFF_FILE + " ]; then echo OFF=yes; else echo OFF=no; fi;"
                + " echo @@SH@@; cat " + SHARED_FILE + " 2>&1;"
                + " echo @@HB@@; cat " + STATE_FILE + " 2>&1;"
                + " echo @@PID@@; pidof " + TARGET + " 2>&1;"
                + " echo @@TOP@@; dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity;"
                + " echo @@LOG@@; logcat -d -v brief -t 6000 2>&1";
        RootShell.Result r = RootShell.run(script, 180);
        String out = r.output;

        String idPart = section(out, null, "@@OFF@@");
        String offPart = section(out, "@@OFF@@", "@@SH@@");
        String shPart = section(out, "@@SH@@", "@@HB@@");
        String hbPart = section(out, "@@HB@@", "@@PID@@");
        String pidPart = section(out, "@@PID@@", "@@TOP@@");
        String topPart = section(out, "@@TOP@@", "@@LOG@@");
        String logPart = section(out, "@@LOG@@", null);

        s.rootOk = idPart.contains("uid=0");
        s.rootMessage = s.rootOk ? firstLineOf(idPart) : ("未获得 root：" + firstLineOf(idPart));

        s.offFilePresent = offPart.contains("OFF=yes");
        s.repairsEnabled = !s.offFilePresent;

        s.pid = firstLineOf(pidPart);
        s.targetPids = parsePids(pidPart);
        s.topActivity = parseTopActivity(topPart);

        // 状态来源按可靠性排序：
        //   1) 共享状态文件 /sdcard/Android/media/<目标包名>/nahangfix.state —— 跨挂载命名空间可见，首选
        //   2) logcat 标记行 —— 会随日志轮转被冲掉，兜底
        //   3) 目标私有目录的文件 —— 模块 App 读不到（命名空间隔离），仅作诊断展示
        String marker = lastMarkerLine(logPart, "NAHANGFIX_STATE");
        if (marker == null) {
            marker = lastMarkerLine(logPart, "NAHANGFIX_READY");
        }

        s.heartbeatTs = parseTs(shPart);
        s.heartbeatPid = parseValue(shPart, "pid=");
        s.offSeen = parseBool(shPart, "repairsDisabled=");
        s.imStatus = parseValue(shPart, "imStatus=");
        if (!s.heartbeatPid.isEmpty()) {
            s.stateSource = "共享状态文件";
        } else {
            String markerPid = markerValue(marker, "pid=");
            if (!markerPid.isEmpty()) {
                s.heartbeatPid = markerPid;
                s.heartbeatTs = System.currentTimeMillis();   // 标记是刚读到的日志，视为"新鲜"
                s.stateSource = "logcat 标记";
                String markerIm = markerValue(marker, "imStatus=");
                String markerOff = markerValue(marker, "repairsDisabled=");
                if (!markerIm.isEmpty()) {
                    s.imStatus = markerIm;
                }
                if (!markerOff.isEmpty()) {
                    s.offSeen = Boolean.valueOf("true".equalsIgnoreCase(markerOff));
                }
            } else if (parseTs(hbPart) > 0) {
                s.heartbeatTs = parseTs(hbPart);
                s.heartbeatPid = parseValue(hbPart, "pid=");
                s.offSeen = parseBool(hbPart, "repairsDisabled=");
                s.imStatus = parseValue(hbPart, "imStatus=");
                s.stateSource = "私有状态文件（App 通常读不到）";
            }
        }

        // 判定"已注入"：标记/状态文件里的 pid ∈ 当前目标进程集 → 本次进程已注入。
        // 注意：不能用 pidof 的"第一行"来判断——目标 App 有主进程 + :ipc 等多个进程，
        // pidof 的输出顺序不保证，取第一行可能拿到 :ipc 的 pid，从而把已注入误报成"未注入"。
        boolean pidMatch = !s.heartbeatPid.isEmpty() && s.targetPids.contains(s.heartbeatPid);
        if (pidMatch) {
            s.moduleInjected = Boolean.TRUE;
            s.injectionDetail = "本次进程 pid=" + s.heartbeatPid;
        } else if (!s.heartbeatPid.isEmpty()) {
            if (flowStartMs > 0) {
                s.moduleInjected = Boolean.FALSE;   // 流程中必须命中的是本次进程
                s.injectionDetail = "模块上报的 pid=" + s.heartbeatPid + " 不是当前目标进程";
            } else {
                s.moduleInjected = null;
                s.injectionDetail = "模块上次上报 pid=" + s.heartbeatPid
                        + "，与当前目标进程 " + (s.pid.isEmpty() ? "（未运行）" : s.pid)
                        + " 不一致：请「强制停止目标 App」后重开";
            }
        }

        int logLines = countLines(logPart);
        s.rawLog = logPart;
        s.diagnostics = "stateSource=" + (s.stateSource.isEmpty() ? "无" : s.stateSource)
                + " marker=[ " + (marker == null ? "无" : marker.trim()) + " ]"
                + " fileHb=[ " + hbPart.trim().replace('\n', '|') + " ]"
                + " logcatLines=" + logLines;
        if (s.moduleInjected == null && s.heartbeatPid.isEmpty()) {
            // 完全没有模块痕迹：可能是旧版模块，退回日志判断；否则明确"未注入"
            boolean hasOldMark = logPart.contains("InitInterceptor");
            s.moduleInjected = hasOldMark ? Boolean.TRUE
                    : (logLines == 0 ? null : Boolean.FALSE);
            s.injectionDetail = s.moduleInjected == null ? "logcat 为空"
                    : (hasOldMark ? "按 logcat 里的旧标记判断：已注入" : "logcat 里没有模块标记");
        }

        // 连接状态：模块上报的 imStatus 优先；没有就退回 logcat 的 "to = CONNECTED"
        if (pidMatch && s.imStatus.length() > 0 && !"unknown".equalsIgnoreCase(s.imStatus)) {
            s.imConnected = classifyImStatus(s.imStatus);
            s.imSource = "模块上报（" + (s.stateSource.isEmpty() ? "?" : s.stateSource) + "）";
        } else {
            s.imConnected = logPart.contains("to = CONNECTED") ? Boolean.TRUE
                    : (logLines == 0 ? null : Boolean.FALSE);
            s.imSource = "logcat";
        }
        return s;
    }

    /** 把模块上报的连接状态串归类：true=已连接，null=连接中/不确定，false=未连接。 */
    private static Boolean classifyImStatus(String status) {
        String up = status.toUpperCase(java.util.Locale.US);
        if (up.contains("CONNECTED") && !up.contains("DISCONNECTED") && !up.contains("CONNECTING")) {
            return Boolean.TRUE;
        }
        if (up.contains("CONNECTING")) {
            return null;
        }
        return Boolean.FALSE;
    }

    /** 取 logcat 里最后一条包含 key 的行（模块状态标记）。 */
    private static String lastMarkerLine(String log, String key) {
        if (log == null || log.isEmpty()) {
            return null;
        }
        String found = null;
        for (String line : log.split("\n")) {
            if (line.contains(key)) {
                found = line;   // 保留最后一条
            }
        }
        return found;
    }

    /** 从标记行里取 {@code key=value}（值到空格或行尾为止）。 */
    private static String markerValue(String line, String key) {
        if (line == null) {
            return "";
        }
        int i = line.indexOf(key);
        if (i < 0) {
            return "";
        }
        String rest = line.substring(i + key.length()).trim();
        int sp = rest.indexOf(' ');
        if (sp >= 0) {
            rest = rest.substring(0, sp);
        }
        return rest.trim();
    }

    /** 取两个标记之间的内容；begin 为 null 表示从头，end 为 null 表示到结尾。 */
    private static String section(String text, String begin, String end) {
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

    /**
     * 解析 {@code pidof <pkg>} 的输出为 pid 列表。
     *
     * <p>{@code pidof} 会用空格/换行分隔多个 pid（目标 App 有主进程与 :ipc 等）。
     * 这里只收集纯数字 token，忽略 "not found" 之类的报错文本。</p>
     */
    private static java.util.List<String> parsePids(String text) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (text == null) {
            return out;
        }
        for (String token : text.trim().split("\\s+")) {
            if (!token.isEmpty() && token.matches("\\d+")) {
                out.add(token);
            }
        }
        return out;
    }

    private static String firstLineOf(String text) {        if (text == null) {
            return "";
        }
        String t = text.trim();
        int idx = t.indexOf('\n');
        return (idx >= 0 ? t.substring(0, idx) : t).trim();
    }

    private static int countLines(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        return text.split("\n").length;
    }

    /** 从心跳文件内容里解析 ts=… */
    private static long parseTs(String content) {
        if (content == null) {
            return 0L;
        }
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("ts=")) {
                try {
                    return Long.parseLong(t.substring(3).trim());
                } catch (Throwable ignore) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private static Boolean parseBool(String content, String prefix) {
        if (content == null) {
            return null;
        }
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith(prefix)) {
                return Boolean.valueOf("true".equalsIgnoreCase(t.substring(prefix.length()).trim()));
            }
        }
        return null;
    }

    /** 从心跳文件内容里取 {@code key=value} 的字符串值。 */
    private static String parseValue(String content, String prefix) {
        if (content == null) {
            return "";
        }
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith(prefix)) {
                return t.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    /** 心跳时间戳的可读形式。 */
    static String formatTs(long ts) {
        if (ts <= 0) {
            return "无";
        }
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(ts));
    }

    /** 从 dumpsys 输出里解析出前台 Activity 的组件名。 */
    private static String parseTopActivity(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+)")
                .matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return firstLineOf(text);
    }

    /**
     * 目标 App 是否停在"还没进主界面"的页面上（返回原因，空串表示正常在主界面）。
     *
     * <p>实测：蓝航有指纹锁，停在 {@code FingerPrintAuthenticationActivity} 时 IM 根本不会连上；
     * 之前面板只显示"未连接"，看不出是这种情况。</p>
     */
    String targetUiBlocker() {
        if (topActivity == null || topActivity.isEmpty()) {
            return "";
        }
        if (!topActivity.contains(TARGET)) {
            return "目标 App 不在前台";
        }
        String lower = topActivity.toLowerCase(java.util.Locale.US);
        if (lower.contains("fingerprint")) {
            return "停在指纹验证页";
        }
        if (lower.contains("splash")) {
            return "停在启动页";
        }
        if (lower.contains("login")) {
            return "停在登录页";
        }
        if (lower.contains("loadingdata")) {
            return "停在数据加载页";
        }
        return "";
    }

    /** 前台 Activity 的短名（去掉包名，便于面板展示）。 */
    String topActivityShort() {
        if (topActivity == null || topActivity.isEmpty()) {
            return "未知";
        }
        int slash = topActivity.indexOf('/');
        return slash >= 0 ? topActivity.substring(slash + 1) : topActivity;
    }

    /** 写入 / 删除「只观测」开关文件（共享路径，超时给足：su 启动本身就要 10s+）。 */
    static RootShell.Result setRepairsEnabled(boolean enabled) {
        if (enabled) {
            return RootShell.run("rm -f " + OFF_FILE, 90);
        }
        return RootShell.run("mkdir -p " + SHARED_DIR + " && touch " + OFF_FILE
                + " && chmod 666 " + OFF_FILE, 90);
    }
}
