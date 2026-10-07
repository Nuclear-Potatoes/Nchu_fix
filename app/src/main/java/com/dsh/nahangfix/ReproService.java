package com.dsh.nahangfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 复现/验证期间的前台服务：保证 App 在退到后台时不被系统回收。
 *
 * <p>为什么需要它：复现流程要 1~3 分钟，期间目标 App 会占据前台，本 App 处于后台；
 * 实测后台进程会被回收，导致任务线程消失、结论丢失。前台服务 + 常驻通知可以让
 * 流程完整跑完，顺便把当前步骤显示在通知里。</p>
 */
public final class ReproService extends Service {

    private static final String CHANNEL_ID = "nahangfix_repro";
    private static final int NOTIFICATION_ID = 1001;
    /** 崩溃告警用单独的高优先级通道/ID（MIUI 会抑制系统崩溃框，这里保证用户一定看得到）。 */
    private static final String ALERT_CHANNEL_ID = "nahangfix_alert";
    private static final int ALERT_NOTIFICATION_ID = 1002;

    private static volatile ReproService sInstance;
    /** 应用上下文（流程里获取唤醒锁等用）。 */
    private static volatile Context sAppContext;

    private NotificationManager notificationManager;

    /** 供 ReproRunner 获取上下文（流程期间申请唤醒锁）。 */
    static Context appContext() {
        return sAppContext;
    }

    /**
     * 弹出"已捕获崩溃"高优先级通知。
     *
     * <p>为什么需要：实测这台 MIUI 即使把 {@code hide_error_dialogs} 置 0，也不会弹系统崩溃对话框
     * （日志里只有 {@code Force finishing activity}，没有 "Showing crash dialog"）。所以由本应用
     * 自己发一条高优先级通知，保证用户能看到"蓝航崩溃了"。</p>
     */
    static void notifyCrash(String summary) {
        Context ctx = sAppContext;
        if (ctx == null) {
            return;
        }
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        ALERT_CHANNEL_ID, "崩溃告警", NotificationManager.IMPORTANCE_HIGH);
                ch.setShowBadge(true);
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, ALERT_CHANNEL_ID)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(R.drawable.ic_info)
                    .setContentTitle("已捕获 蓝航 崩溃")
                    .setContentText(summary)
                    .setStyle(new Notification.BigTextStyle().bigText(summary))
                    .setAutoCancel(true)
                    .setContentIntent(android.app.PendingIntent.getActivity(
                            ctx, 1, open,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                    ? android.app.PendingIntent.FLAG_IMMUTABLE : 0)));
            nm.notify(ALERT_NOTIFICATION_ID, b.build());
        } catch (Throwable t) {
            android.util.Log.w("NahangFix", "崩溃通知发送失败", t);
        }
    }

    static void start(Context context) {
        try {
            Intent intent = new Intent(context, ReproService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignore) {
            // 保活失败不影响主流程
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, ReproService.class));
        } catch (Throwable ignore) {
            // 忽略
        }
    }

    /** 更新通知里的当前步骤。 */
    static void updateStep(String step) {
        ReproService s = sInstance;
        if (s != null && s.notificationManager != null) {
            try {
                s.notificationManager.notify(NOTIFICATION_ID, s.buildNotification(step));
            } catch (Throwable ignore) {
                // 忽略
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        sAppContext = getApplicationContext();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "复现任务", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
        startForeground(NOTIFICATION_ID, buildNotification("准备中…"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 不要 START_STICKY：被系统重启会留下一条停在半途的常驻通知
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        // 流程结束必须把这条常驻通知真正移除，否则它会一直停在中途某一步的文案上
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        try {
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_refresh)
                .setContentTitle("蓝航修复 · 复现/验证进行中")
                .setContentText(text)
                .setOngoing(true);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        builder.setContentIntent(android.app.PendingIntent.getActivity(
                this, 0, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? android.app.PendingIntent.FLAG_IMMUTABLE : 0)));
        return builder.build();
    }
}
