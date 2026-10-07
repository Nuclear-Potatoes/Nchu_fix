package com.dsh.nahangfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.util.Locale;

/**
 * 从 AndroidManifest 的 meta-data 中找出融云 AppKey。
 *
 * <p>IMKit 4.x 及更早版本（也就是使用 {@code io.rong.eventbus} 的版本）必须在清单里配置
 * {@code RONG_CLOUD_APP_KEY}，所以绝大多数情况下 AppKey 可以直接读出来。
 * 读不到时不影响“不崩溃”这个目标，只是会退化为占位实例，等 App 自己初始化时再接管。</p>
 */
final class AppKeyFinder {

    private static final String[] EXACT_KEYS = {
            "RONG_CLOUD_APP_KEY",
            "RONGCLOUD_APP_KEY",
            "RONG_APP_KEY",
            "RONG_IM_APP_KEY",
            "RONGYUN_APP_KEY",
            "RONG_KEY",
    };

    private static volatile String sLastKey;
    private static volatile boolean sLastCertain;

    private AppKeyFinder() {
    }

    // getApplicationInfo(String, int) 与 PackageManager.GET_META_DATA 自 API 33 起被标记废弃，
    // 但为了兼容 minSdk 21 仍沿用旧签名，这里显式抑制该编译告警。
    @SuppressWarnings("deprecation")
    static String find(Context ctx) {
        sLastKey = null;
        sLastCertain = false;
        if (ctx == null) {
            return null;
        }
        try {
            ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), PackageManager.GET_META_DATA);
            Bundle meta = ai.metaData;
            if (meta == null || meta.isEmpty()) {
                XLog.w("manifest 中没有 meta-data，无法自动读取融云 AppKey");
                return null;
            }
            String best = null;
            String bestKeyName = null;
            for (String key : meta.keySet()) {
                Object raw = meta.get(key);
                String value = raw == null ? null : String.valueOf(raw);
                XLog.i("meta-data: " + key + " = " + value);
                if (value == null || !looksLikeAppKey(value)) {
                    continue;
                }
                String upper = key == null ? "" : key.toUpperCase(Locale.US);
                for (String exact : EXACT_KEYS) {
                    if (exact.equals(upper)) {
                        sLastKey = value;
                        sLastCertain = true;
                        XLog.i("命中融云 AppKey（meta-data " + key + "）");
                        return value;
                    }
                }
                if (upper.contains("RONG") || upper.contains("APP_KEY") || upper.contains("APPKEY")) {
                    if (best == null) {
                        best = value;
                        bestKeyName = key;
                    }
                }
            }
            if (best != null) {
                sLastKey = best;
                sLastCertain = true;
                XLog.i("推断融云 AppKey（meta-data " + bestKeyName + "）");
                return best;
            }
            XLog.w("meta-data 中未找到疑似融云 AppKey 的字段");
            return null;
        } catch (Throwable t) {
            XLog.e("读取 meta-data 失败", t);
            return null;
        }
    }

    /** 上一次 find() 的结果是否来自明确的 AppKey 字段（可信度高）。 */
    static boolean lastCertain() {
        return sLastCertain;
    }

    private static boolean looksLikeAppKey(String s) {
        if (s.length() < 6 || s.length() > 40) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
