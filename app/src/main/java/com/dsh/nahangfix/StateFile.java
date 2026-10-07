package com.dsh.nahangfix;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/**
 * 模块在<b>目标 App 进程内</b>写下的状态文件（心跳 + 实时状态）。
 *
 * <p>为什么需要它：模块 App 想确认「模块是否已注入目标 App」「融云是否已连接」时，
 * 靠解析 logcat 并不靠谱（日志会轮转，实测在 App 内通过 su 读全局 logcat 也常拿不到内容）。
 * 而模块本身就跑在目标进程里，直接把状态写进目标 App 的私有目录，模块 App 再用 root 读它，
 * 简单且确定。</p>
 *
 * <p>文件内容（每行一个 {@code key=value}）：</p>
 * <pre>
 * ts=毫秒时间戳         最近一次写入时间
 * pid=进程号            写入它的目标进程（App 用它与当前目标 pid 比对，判断是否"本次"注入）
 * process=包名
 * where=触发点
 * version=模块版本
 * repairsDisabled=是否处于"只观测不修复"模式
 * imStatus=融云连接状态（CONNECTED / CONNECTING / DISCONNECTED / TOKEN_INCORRECT …）
 * </pre>
 */
final class StateFile {

    static final String NAME = "nahangfix.state";

    private StateFile() {
    }

    /**
     * 共享状态文件路径：目标 App 的<b>外部媒体目录</b>。
     *
     * <p>为什么不用私有目录：Android 给每个 App 独立的挂载命名空间，模块 App 即使拿到 root，
     * 在自己命名空间里也看不到 {@code /data/data/<目标包名>/files/}（实测 No such file or directory）。
     * 而 {@code /sdcard/Android/media/<包名>/} 是所有 App 都能看到的路径（目标 App 无需权限即可写），
     * 模块 App 用 root 就能读 —— 实测可行。</p>
     */
    static String sharedPath(String targetPackage) {
        return android.os.Environment.getExternalStorageDirectory()
                + "/Android/media/" + targetPackage + "/" + NAME;
    }

    /** 完整写入（进程启动时调用）。 */
    static boolean write(Context context, String where) {
        return write(context, where, ImStatusWatcher.status());
    }

    /**
     * 写入状态并返回<b>共享路径</b>是否写成功。
     *
     * <p>返回值的唯一用途是让调用方（{@link ImStatusWatcher}）在写失败时回滚去重标记：
     * 模块 App 真正读的是共享路径，私有路径与 logcat 标记都只是诊断/兜底，
     * 所以这里用"共享路径成功与否"代表整体成功。</p>
     */
    static boolean write(Context context, String where, String imStatus) {
        if (context == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ts=").append(System.currentTimeMillis()).append('\n');
        sb.append("pid=").append(android.os.Process.myPid()).append('\n');
        sb.append("process=").append(context.getPackageName()).append('\n');
        sb.append("where=").append(where).append('\n');
        sb.append("version=").append("1.3.0").append('\n');
        // 记录模块自己看到的开关状态：模块 App 用它验证"只观测"开关是否真的生效
        sb.append("repairsDisabled=").append(RongGuard.repairsDisabled()).append('\n');
        sb.append("imStatus=").append(imStatus == null ? "unknown" : imStatus).append('\n');
        byte[] data = sb.toString().getBytes(Charset.forName("UTF-8"));

        // 1) 目标 App 私有目录（供 adb 调试）
        try {
            File file = new File(context.getFilesDir(), NAME);
            FileOutputStream out = new FileOutputStream(file, false);
            try {
                out.write(data);
                out.flush();
            } finally {
                out.close();
            }
            XLog.i("已写入状态文件 " + file.getAbsolutePath()
                    + "（where=" + where + " imStatus=" + imStatus + "）");
        } catch (Throwable t) {
            XLog.w("写私有状态文件失败：" + t);
        }

        // 2) 共享路径（模块 App 真正读的就是这个；外部存储可能还没挂载好，失败就等下一轮）
        boolean sharedOk = false;
        try {
            File shared = new File(sharedPath(context.getPackageName()));
            File dir = shared.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(shared, false);
            try {
                out.write(data);
                out.flush();
            } finally {
                out.close();
            }
            sharedOk = true;
        } catch (Throwable t) {
            XLog.w("写共享状态文件失败（稍后重试）：" + t);
        }

        // 3) logcat 标记：作为最后兜底（日志会轮转，不能只靠它）
        emitMarker(where);
        return sharedOk;
    }

    /**
     * 只往 logcat 发一行状态标记（不写文件）。
     *
     * <p>为什么还要周期重发：logcat 是全局环形缓冲，busy 设备上几千行就能把几分钟前的标记冲掉，
     * 模块 App 读不到就会误判"未注入"。每分钟左右重发一次，保证缓冲里总有当前进程的新标记。</p>
     */
    static void emitMarker(String where) {
        XLog.marker("NAHANGFIX_STATE pid=" + android.os.Process.myPid()
                + " imStatus=" + (ImStatusWatcher.status() == null ? "unknown" : ImStatusWatcher.status())
                + " repairsDisabled=" + RongGuard.repairsDisabled()
                + " where=" + where);
    }
}
