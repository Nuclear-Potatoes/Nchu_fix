package com.dsh.nahangfix;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 融云 IMKit 单例（RongContext）守卫。
 *
 * <p>目标：让 {@code RongContext.getInstance()} 与 {@code RongContext.getInstance().getEventBus()}
 * 永远不返回 null，从而消除：</p>
 *
 * <pre>
 * NullPointerException: Attempt to invoke virtual method
 *   'io.rong.eventbus.EventBus io.rong.imkit.RongContext.getEventBus()' on a null object reference
 * </pre>
 *
 * <p>处理顺序：</p>
 * <ol>
 *   <li>如果 App 自己已经初始化好，什么都不做；</li>
 *   <li>否则反射调用 SDK 的初始化接口（RongIM / RongCoreClient / IMCenter / RongContext），
 *       AppKey 取自 AndroidManifest 的 meta-data（这版 IMKit 依赖 RONG_CLOUD_APP_KEY）；</li>
 *   <li>仍然拿不到时，构造一个“占位 RongContext”写回静态单例字段，并保证 getEventBus() 非 null。
 *       占位实例只用于保命；App 之后真正初始化时由 InitInterceptor 释放占位、放行真实初始化。</li>
 * </ol>
 *
 * <p>如果希望强制使用某个 AppKey，把它填到 {@link #HARDCODED_APP_KEY}。</p>
 */
final class RongGuard {

    /**
     * 强制指定的 AppKey；留空表示不使用。
     *
     * <p><b>只在 {@link #ALLOW_PRE_INIT} 为 true 时才会被用到。</b>本 App 实测的真实
     * AppKey 是运行时传入的 {@code <运行时 AppKey，已脱敏>}；manifest 里的
     * {@code RONG_CLOUD_APP_KEY=<manifest 中的失效旧值，已脱敏>} 是模板遗留的失效值，用它会直接导致
     * 31004 TOKEN_INCORRECT（读不到消息）。</p>
     */
    static final String HARDCODED_APP_KEY = "";

    /**
     * 是否允许模块代替 App 调用融云 SDK 初始化。
     *
     * <p><b>默认 false，请保持 false。</b>原因见 {@link #HARDCODED_APP_KEY} 的说明：
     * 抢先初始化会把 SDK 的 AppKey 钉死成错误值，破坏 IM 连接（消息读不出来）。
     * 模块默认只做“占位单例 + 让位”，真实初始化始终由 App 自己完成。</p>
     */
    static final boolean ALLOW_PRE_INIT = false;

    /**
     * 是否只在主进程做修复（入口处已经过滤过一次，这里是双保险）。
     */
    static final boolean ONLY_MAIN_PROCESS = true;

    static final String RONG_CONTEXT = "io.rong.imkit.RongContext";
    static final String EVENT_BUS = "io.rong.eventbus.EventBus";

    private static final Object LOCK = new Object();

    private static ClassLoader sClassLoader;
    private static volatile Context sAppContext;

    private static volatile boolean sResolved;
    private static volatile boolean sRealReady;
    private static volatile boolean sPlaceholder;
    private static volatile Object sPlaceholderInstance;
    private static volatile boolean sAppKeyCertain;
    private static volatile String sAppKey;
    private static volatile int sAttempts;
    private static volatile boolean sShapeDumped;
    private static volatile boolean sEventBusHooked;
    private static volatile boolean sNoImKitLogged;
    private static volatile Object sFallbackEventBus;
    private static volatile String sProcessName;
    private static volatile String sLearnedAppKey;
    private static volatile boolean sStateReported;

    private RongGuard() {
    }

    static void install(ClassLoader cl) {
        sClassLoader = cl;
        hookEventBusGetter();
        XLog.i("RongGuard 已安装（getEventBus 兜底=" + sEventBusHooked + "）");
    }

    static void setProcessName(String processName) {
        sProcessName = processName;
    }

    // ------------------------------------------------------------------ 测试开关

    /** 关闭修复的标记文件名。 */
    static final String OFF_FILE = "nahangfix.off";

    private static volatile Boolean sRepairsDisabled;
    /** 上面缓存值的读取时间（用于 TTL 失效）。 */
    private static volatile long sRepairsDisabledAt;
    /**
     * 开关缓存的有效期。
     *
     * <p>为什么不能永久缓存：开关文件由模块 App 通过 root 在<b>运行期</b>增删。
     * 原先的实现一旦读过就永久缓存，导致"刚删掉 off 文件"的进程仍按只观测运行
     * （A/B 对照会得出错误结论），只能靠用户手动「强制停止目标 App」。
     * 这里加一个短 TTL：既避免每次调用都做文件 IO，又能在 2 秒内感知到开关变化。</p>
     */
    private static final long REPAIRS_CACHE_TTL_MS = 2000L;

    /**
     * 测试开关：存在 {@code nahangfix.off} 时，模块<b>只记录日志、不做任何修复</b>。
     *
     * <p>用途：在真机上做 A/B 对照，确认“不打补丁会崩、打了补丁不崩”。</p>
     *
     * <p><b>放在共享路径</b>（目标 App 的外部媒体目录）：实测 Android 的挂载命名空间隔离会让
     * 模块 App 即使有 root 也写不进/读不到目标 App 的私有目录，所以开关文件必须放双方都可见的位置。
     * 模块进程自己能读自己包名的媒体目录（无需权限）。</p>
     *
     * <pre>
     * 关闭修复（复现原始崩溃）：
     *   adb shell su -c "touch /sdcard/Android/media/cn.edu.nchu.nahang/nahangfix.off"
     * 打开修复（验证补丁）：
     *   adb shell su -c "rm -f /sdcard/Android/media/cn.edu.nchu.nahang/nahangfix.off"
     * </pre>
     */
    static boolean repairsDisabled() {
        Boolean cached = sRepairsDisabled;
        // 带 TTL 的缓存：见 REPAIRS_CACHE_TTL_MS 的说明（开关可按运行期随时变化）
        if (cached != null
                && System.currentTimeMillis() - sRepairsDisabledAt < REPAIRS_CACHE_TTL_MS) {
            return cached;
        }
        boolean off = false;
        try {
            Context ctx = sAppContext;
            if (ctx != null) {
                // 1) 共享路径（App 侧开关真正生效的位置）
                if (new File(sharedOffPath(ctx)).exists()) {
                    off = true;
                }
                // 2) 兼容：私有目录里的旧标记（adb 手工放置时仍可用）
                if (!off) {
                    off = new File(ctx.getFilesDir(), OFF_FILE).exists()
                            || new File(ctx.getCacheDir(), OFF_FILE).exists();
                }
            }
        } catch (Throwable t) {
            // 读不到就当作未关闭
        }
        boolean changed = cached != null && cached != off;
        sRepairsDisabled = off;
        sRepairsDisabledAt = System.currentTimeMillis();
        if (changed) {
            XLog.w("检测到 " + OFF_FILE + " 状态已变化：" + (off ? "进入【只观测不修复】" : "恢复【正常修复】"));
        } else if (off && cached == null) {
            XLog.w("检测到 " + OFF_FILE + "：模块进入【只观测不修复】模式（用于复现原始崩溃）");
        }
        return off;
    }

    /** 共享路径下的开关文件：{@code /sdcard/Android/media/<目标包名>/nahangfix.off}。 */
    static String sharedOffPath(Context ctx) {
        return android.os.Environment.getExternalStorageDirectory()
                + "/Android/media/" + ctx.getPackageName() + "/" + OFF_FILE;
    }

    // ------------------------------------------------------------------ 基础工具

    static Class<?> findClassIfExists(String name, ClassLoader cl) {
        if (name == null || cl == null) {
            return null;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static Class<?> findClassIfExists(String name) {
        return findClassIfExists(name, sClassLoader);
    }

    static void rememberContext(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Context app = ctx.getApplicationContext();
            sAppContext = app != null ? app : ctx;
        } catch (Throwable t) {
            sAppContext = ctx;
        }
    }

    static Context contextOrNull() {
        return sAppContext;
    }

    static String appKey() {
        return sAppKey;
    }

    static boolean appKeyCertain() {
        return sAppKeyCertain;
    }

    static boolean isRealReady() {
        return sRealReady;
    }

    static boolean isPlaceholder() {
        return sPlaceholder;
    }

    /** 记录 App 自己传入的 AppKey（诊断 + 可选的抢先初始化用）。 */
    static void learnAppKey(String key) {
        if (key == null || key.length() == 0) {
            return;
        }
        if (!key.equals(sLearnedAppKey)) {
            sLearnedAppKey = key;
            String note = (sAppKey == null || key.equals(sAppKey))
                    ? ""
                    : "（与模块此前使用的 " + sAppKey + " 不同）";
            XLog.i("记录到 App 实际使用的 AppKey = " + key + note);
            sAppKey = key;
            sAppKeyCertain = true;
        }
    }

    /** 启动后只报告一次单例状态，便于确认“模块确实没有抢先初始化”。 */
    static void reportStartupState(String where) {
        if (sStateReported) {
            return;
        }
        sStateReported = true;
        Object instance = getInstance();
        XLog.i("[" + where + "] 启动观测：进程=" + (sProcessName == null ? "?" : sProcessName)
                + " RongContext.getInstance()="
                + (instance == null
                        ? "null（App 尚未初始化，属本 App 的正常时序；模块不介入，等 App 自己初始化）"
                        : "已就绪"));
    }

    /** 单例是否已经可用（真实实例或占位实例都算）。 */
    static boolean isReady() {
        if (sRealReady || sPlaceholder) {
            return true;
        }
        return getInstance() != null;
    }

    static Object getInstance() {
        Class<?> c = findClassIfExists(RONG_CONTEXT);
        if (c == null) {
            return null;
        }
        try {
            return XposedHelpers.callStaticMethod(c, "getInstance");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只取真实单例；占位实例不算（用于判断 App 是否真的初始化过 SDK）。 */
    static Object getRealInstanceOrNull() {
        if (sPlaceholder) {
            return null;
        }
        return getInstance();
    }

    // ------------------------------------------------------------------ 主流程

    static void ensureInitialized(Context ctx, String where) {
        if (ctx != null) {
            rememberContext(ctx);
        }
        if (repairsDisabled()) {
            return;
        }
        if (sClassLoader == null) {
            return;
        }
        if (ONLY_MAIN_PROCESS && sProcessName != null && sProcessName.contains(":")) {
            return;
        }
        if (findClassIfExists(RONG_CONTEXT) == null) {
            if (!sNoImKitLogged) {
                sNoImKitLogged = true;
                XLog.i("本进程未加载 io.rong.imkit（进程=" + processNameHint() + "），无需处理");
            }
            return;
        }
        if (sResolved && getInstance() != null) {
            return;
        }
        if (sPlaceholder) {
            // 占位实例已生效：等 App 自己初始化时由 InitInterceptor 释放并让位。
            return;
        }
        if (getInstance() != null) {
            if (!sRealReady) {
                sRealReady = true;
                XLog.i("[" + where + "] 融云 SDK 已由 App 自行初始化，无需干预");
            }
            sResolved = true;
            return;
        }

        synchronized (LOCK) {
            if (sPlaceholder || getInstance() != null) {
                return;
            }
            sAttempts++;
            XLog.w("[" + where + "] App 尚未完成融云初始化（RongContext 为 null），"
                    + "先放占位实例保证不崩溃，真实初始化仍由 App 自己完成");
            if (ALLOW_PRE_INIT) {
                trySdkInit(sAppContext, where);
            }
            if (getInstance() == null) {
                dumpShapeOnce();
                tryPlaceholder(sAppContext, where);
            }
            XLog.w("[" + where + "] 占位结果：单例可用=" + (getInstance() != null)
                    + " 真实初始化=" + sRealReady + " 占位=" + sPlaceholder
                    + "（第 " + sAttempts + " 次）");
        }
    }

    // ------------------------------------------------------------------ 策略 1：真实初始化

    private static boolean trySdkInit(Context ctx, String where) {
        if (ctx == null) {
            XLog.w("没有可用 Context，无法调用 SDK 初始化接口");
            return false;
        }
        if (HARDCODED_APP_KEY != null && HARDCODED_APP_KEY.length() > 0) {
            sAppKey = HARDCODED_APP_KEY;
            sAppKeyCertain = true;
        } else if (sLearnedAppKey != null) {
            sAppKey = sLearnedAppKey;
            sAppKeyCertain = true;
        } else if (sAppKey == null) {
            sAppKey = AppKeyFinder.find(ctx);
            sAppKeyCertain = AppKeyFinder.lastCertain();
            XLog.w("警告：只能使用 manifest 里的 AppKey=" + sAppKey
                    + "，本 App 实测使用运行时传入的其它 AppKey，该值可能已失效");
        }

        List<Method> candidates = new ArrayList<Method>();
        collectInitMethods(candidates, "io.rong.imkit.RongIM", ctx);
        collectInitMethods(candidates, "io.rong.imkit.RongCoreClient", ctx);
        collectInitMethods(candidates, "io.rong.imlib.RongCoreClient", ctx);
        collectInitMethods(candidates, "io.rong.imkit.IMCenter", ctx);
        collectInitMethods(candidates, "io.rong.imkit.RongContext", ctx);

        boolean invokedWithoutError = false;
        for (Method m : candidates) {
            Object[] args = buildInitArgs(m, ctx);
            if (args == null) {
                continue;
            }
            try {
                m.setAccessible(true);
                XLog.i("尝试初始化：" + describe(m));
                Object result = m.invoke(null, args);
                invokedWithoutError = true;
                XLog.i("初始化调用返回：" + result);
                if (getInstance() != null) {
                    sRealReady = true;
                    return true;
                }
                // 调用没有抛异常但单例仍未建立：不再尝试其它重载，避免重复初始化。
                XLog.w("初始化接口未抛异常，但 RongContext 仍为 null，停止继续尝试其它重载");
                break;
            } catch (Throwable t) {
                XLog.e("初始化调用失败：" + describe(m), t);
            }
        }
        if (!invokedWithoutError) {
            XLog.w("没有找到可用的 SDK 初始化接口（可能 AppKey 缺失或 SDK 版本不匹配）");
        }
        return false;
    }

    /** 收集静态 init(...) 方法，参数只允许 Context / String / boolean / InitOption。 */
    private static void collectInitMethods(List<Method> out, String className, Context ctx) {
        Class<?> c = findClassIfExists(className);
        if (c == null) {
            return;
        }
        int found = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"init".equals(m.getName()) || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (!isUsableInitSignature(m, ctx)) {
                continue;
            }
            out.add(m);
            found++;
        }
        if (found > 0) {
            XLog.i("发现初始化接口 " + className + " 共 " + found + " 个候选");
        }
        // 单参数（只有 Context）的 init 优先：它由 SDK 自己从 manifest 读取 AppKey。
        // 这里用 Collections.sort 而不是 List#sort（默认方法在低版本 Android 上需要脱糖）。
        java.util.Collections.sort(out, new java.util.Comparator<Method>() {
            @Override
            public int compare(Method a, Method b) {
                return Integer.compare(signatureWeight(a), signatureWeight(b));
            }
        });
    }

    private static int signatureWeight(Method m) {
        Class<?>[] pt = m.getParameterTypes();
        int weight = pt.length * 10;
        for (Class<?> p : pt) {
            if (p == String.class) {
                weight += 1;
            }
        }
        return weight;
    }

    private static boolean isUsableInitSignature(Method m, Context ctx) {
        Class<?>[] pt = m.getParameterTypes();
        if (pt.length == 0 || pt.length > 4) {
            return false;
        }
        for (Class<?> p : pt) {
            if (Context.class.isAssignableFrom(p)) {
                continue;
            }
            if (p == String.class) {
                continue;
            }
            if (p == boolean.class || p == Boolean.class) {
                continue;
            }
            if (p.getName().endsWith("InitOption")) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static Object[] buildInitArgs(Method m, Context ctx) {
        Class<?>[] pt = m.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) {
            Class<?> p = pt[i];
            if (Context.class.isAssignableFrom(p)) {
                args[i] = ctx;
            } else if (p == String.class) {
                if (sAppKey == null) {
                    return null;
                }
                args[i] = sAppKey;
            } else if (p == boolean.class || p == Boolean.class) {
                args[i] = Boolean.TRUE;
            } else if (p.getName().endsWith("InitOption")) {
                Object option = buildInitOption(p);
                if (option == null) {
                    return null;
                }
                args[i] = option;
            } else {
                return null;
            }
        }
        return args;
    }

    /** 反射构造 {@code new Xxx.InitOption.Builder().build()}。 */
    private static Object buildInitOption(Class<?> optionClass) {
        try {
            for (Class<?> inner : optionClass.getDeclaredClasses()) {
                if (!"Builder".equals(inner.getSimpleName())) {
                    continue;
                }
                Constructor<?> ctor = inner.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object builder = ctor.newInstance();
                Method build = inner.getDeclaredMethod("build");
                build.setAccessible(true);
                return build.invoke(builder);
            }
        } catch (Throwable t) {
            XLog.w("构造 " + optionClass.getName() + " 失败：" + t);
        }
        return null;
    }

    // ------------------------------------------------------------------ 策略 2：占位单例

    private static boolean tryPlaceholder(Context ctx, String where) {
        Class<?> rc = findClassIfExists(RONG_CONTEXT);
        if (rc == null) {
            return false;
        }
        Object instance = null;

        // 2.1 优先使用真实构造器
        try {
            for (Constructor<?> ctor : rc.getDeclaredConstructors()) {
                Class<?>[] pt = ctor.getParameterTypes();
                Object[] args = new Object[pt.length];
                boolean usable = true;
                for (int i = 0; i < pt.length; i++) {
                    if (Context.class.isAssignableFrom(pt[i])) {
                        args[i] = ctx;
                    } else if (pt[i] == boolean.class || pt[i] == Boolean.class) {
                        args[i] = Boolean.FALSE;
                    } else {
                        usable = false;
                        break;
                    }
                }
                if (!usable) {
                    continue;
                }
                ctor.setAccessible(true);
                instance = ctor.newInstance(args);
                XLog.i("使用构造器创建 RongContext 占位实例成功：" + ctor);
                break;
            }
        } catch (Throwable t) {
            XLog.w("构造 RongContext 失败（将尝试 Unsafe.allocateInstance）：" + t);
        }

        // 2.2 Unsafe.allocateInstance（跳过构造器）
        if (instance == null) {
            instance = unsafeAllocate(rc);
        }
        if (instance == null) {
            XLog.e("无法创建 RongContext 占位实例");
            return false;
        }

        if (ctx != null) {
            fillContextFields(rc, instance, ctx);
        }
        if (!setStaticInstance(rc, instance)) {
            XLog.w("没能写入 RongContext 的静态单例字段（字段名可能变化），仍继续尝试 EventBus 兜底");
        }
        // 注意：这里刻意不调用任何 init 方法（包括 RongContext#init(Context)）。
        // 占位实例只负责“让 getInstance()/getEventBus() 不为 null”，一旦调用 SDK 的初始化
        // 入口，就会把 IMLib 的状态带偏，甚至钉死错误的 AppKey（曾导致 31004 TOKEN_INCORRECT）。
        ensureEventBusField(rc, instance);
        hookEventBusGetter();

        sPlaceholderInstance = instance;
        sPlaceholder = true;
        sRealReady = false;
        XLog.w("已启用占位 RongContext（" + where + "）：界面不再崩溃；"
                + "App 之后若真正调用初始化，占位实例会被自动释放并让位");
        return true;
    }

    private static Object unsafeAllocate(Class<?> clazz) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            Object instance = allocateInstance.invoke(unsafe, clazz);
            XLog.i("使用 Unsafe.allocateInstance 创建 RongContext 占位实例");
            return instance;
        } catch (Throwable t) {
            XLog.e("Unsafe.allocateInstance 失败", t);
            return null;
        }
    }

    private static void fillContextFields(Class<?> rc, Object instance, Context ctx) {
        for (Class<?> c = rc; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!Context.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.get(instance) == null) {
                        f.set(instance, ctx);
                        XLog.i("已填充 Context 字段 " + c.getSimpleName() + "#" + f.getName());
                    }
                } catch (Throwable t) {
                    XLog.w("填充 Context 字段失败 " + f.getName() + "：" + t);
                }
            }
        }
    }

    private static boolean setStaticInstance(Class<?> rc, Object instance) {
        boolean set = false;
        for (Class<?> c = rc; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                boolean exactType = f.getType() == rc;
                boolean namedLikeSingleton = f.getType().isAssignableFrom(rc)
                        && f.getName().toLowerCase(Locale.US).matches(".*(instance|singleton|scontext).*");
                if (!exactType && !namedLikeSingleton) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    f.set(null, instance);
                    XLog.i("已写入静态单例字段 " + c.getName() + "#" + f.getName());
                    set = true;
                } catch (Throwable t) {
                    XLog.w("写入静态单例字段失败 " + f.getName() + "：" + t);
                }
            }
        }
        return set;
    }

    // ------------------------------------------------------------------ EventBus 兜底

    private static void ensureEventBusField(Class<?> rc, Object instance) {
        Class<?> eventBusClass = findClassIfExists(EVENT_BUS);
        Object bus = obtainEventBus(eventBusClass);
        sFallbackEventBus = bus;
        if (bus == null) {
            XLog.e("无法创建 " + EVENT_BUS + " 实例，将仅依赖 getEventBus 挂钩");
            return;
        }
        for (Class<?> c = rc; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (eventBusClass != null) {
                    if (!eventBusClass.isAssignableFrom(f.getType())) {
                        continue;
                    }
                } else if (!EVENT_BUS.equals(f.getType().getName())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.get(instance) == null) {
                        f.set(instance, bus);
                        XLog.i("已注入 EventBus 字段 " + c.getSimpleName() + "#" + f.getName());
                    }
                } catch (Throwable t) {
                    XLog.w("注入 EventBus 字段失败 " + f.getName() + "：" + t);
                }
            }
        }
    }

    private static Object obtainEventBus(Class<?> eventBusClass) {
        if (eventBusClass == null) {
            return null;
        }
        try {
            Method getDefault = eventBusClass.getDeclaredMethod("getDefault");
            getDefault.setAccessible(true);
            Object bus = getDefault.invoke(null);
            if (bus != null) {
                return bus;
            }
        } catch (Throwable ignore) {
            // 没有 getDefault，继续尝试构造器
        }
        try {
            Constructor<?> ctor = eventBusClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            XLog.w("创建 EventBus 失败：" + t);
            return null;
        }
    }

    /** 挂钩 RongContext#getEventBus()，保证返回值永不为 null。 */
    private static void hookEventBusGetter() {
        if (sEventBusHooked) {
            return;
        }
        Class<?> rc = findClassIfExists(RONG_CONTEXT);
        if (rc == null) {
            return;
        }
        int count = 0;
        for (Class<?> c = rc; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"getEventBus".equals(m.getName()) || m.getParameterTypes().length != 0) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, EVENT_BUS_REPLACEMENT);
                    count++;
                    XLog.i("已挂钩 " + m);
                } catch (Throwable t) {
                    XLog.e("挂钩 getEventBus 失败：" + m, t);
                }
            }
        }
        sEventBusHooked = count > 0;
        if (count == 0) {
            XLog.w("未找到 RongContext#getEventBus()（SDK 版本可能不同）");
        }
    }

    private static final XC_MethodReplacement EVENT_BUS_REPLACEMENT = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
            if (repairsDisabled()) {
                // 测试模式：不兜底，让原始返回值/异常如实发生
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            }
            Object result = null;
            try {
                result = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            } catch (Throwable t) {
                XLog.w("getEventBus 原方法抛异常，改用兜底实例：" + t);
            }
            if (result != null) {
                return result;
            }
            Object bus = sFallbackEventBus;
            if (bus == null) {
                bus = obtainEventBus(findClassIfExists(EVENT_BUS));
                sFallbackEventBus = bus;
            }
            XLog.w("RongContext#getEventBus() 返回 null，已用兜底 EventBus 顶替（bus=" + bus + "）");
            return bus;
        }
    };

    // ------------------------------------------------------------------ 诊断

    static void dumpShapeOnce() {
        if (sShapeDumped) {
            return;
        }
        sShapeDumped = true;
        Class<?> rc = findClassIfExists(RONG_CONTEXT);
        if (rc == null) {
            XLog.i("诊断：本进程未加载 " + RONG_CONTEXT);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("诊断：RongContext 自身结构（只列本类成员，避免把 Context 的常量刷成几千行）\n");
        sb.append("  class=").append(rc.getName())
                .append(" super=").append(rc.getSuperclass() == null ? "?" : rc.getSuperclass().getName())
                .append('\n');
        try {
            int fieldLines = 0;
            for (Field f : rc.getDeclaredFields()) {
                if (fieldLines++ >= 25) {
                    sb.append("  ...（字段过多，已截断）\n");
                    break;
                }
                sb.append("  field ").append(Modifier.toString(f.getModifiers())).append(' ')
                        .append(f.getType().getName()).append(' ').append(f.getName());
                if (Modifier.isStatic(f.getModifiers())) {
                    sb.append(" = ").append(staticValueHint(f));
                }
                sb.append('\n');
            }
            for (Constructor<?> ctor : rc.getDeclaredConstructors()) {
                sb.append("  ctor ").append(ctor).append('\n');
            }
            for (Method m : rc.getDeclaredMethods()) {
                if (m.getName().startsWith("init") || m.getName().contains("EventBus")
                        || m.getName().startsWith("getInstance")) {
                    sb.append("  method ").append(m).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("  读取结构异常: ").append(t).append('\n');
        }
        XLog.i(sb.toString());
    }

    private static String staticValueHint(Field f) {
        try {
            f.setAccessible(true);
            Object v = f.get(null);
            if (v == null) {
                return "null";
            }
            Class<?> t = f.getType();
            if (t.isPrimitive() || t == String.class) {
                return String.valueOf(v);
            }
            return v.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(v));
        } catch (Throwable t) {
            return "<不可读>";
        }
    }

    private static String processNameHint() {
        try {
            String name = android.app.Application.getProcessName();
            return name == null ? "?" : name;
        } catch (Throwable t) {
            return "?";
        }
    }

    static String describe(Method m) {
        return m.getDeclaringClass().getName() + "#" + m.getName() + java.util.Arrays.toString(m.getParameterTypes());
    }

    // ------------------------------------------------------------------ 其它工具

    /** 释放占位实例，让 App 的真实初始化可以在干净状态下执行。 */
    static void clearPlaceholder(String reason) {
        if (!sPlaceholder) {
            return;
        }
        synchronized (LOCK) {
            if (!sPlaceholder) {
                return;
            }
            Class<?> rc = findClassIfExists(RONG_CONTEXT);
            Object placeholder = sPlaceholderInstance;
            if (rc != null && placeholder != null) {
                for (Class<?> c = rc; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers()) || f.getType() != rc) {
                            continue;
                        }
                        try {
                            f.setAccessible(true);
                            if (f.get(null) == placeholder) {
                                f.set(null, null);
                                XLog.i("已清空静态单例字段 " + f.getName());
                            }
                        } catch (Throwable t) {
                            XLog.w("清空静态单例字段失败 " + f.getName() + "：" + t);
                        }
                    }
                }
            }
            sPlaceholder = false;
            sPlaceholderInstance = null;
            sRealReady = false;
            sResolved = false;
            XLog.i("已释放占位 RongContext：" + reason);
        }
    }

    /** 判断异常是否属于“融云单例为 null”这一类，避免误吞 App 自身的其它 NPE。 */
    static boolean isRongNullFailure(Throwable t) {
        int depth = 0;
        for (Throwable x = t; x != null && depth < 10; x = x.getCause(), depth++) {
            if (!(x instanceof NullPointerException)) {
                continue;
            }
            String message = x.getMessage();
            if (message != null && message.contains("io.rong.")) {
                return true;
            }
            StackTraceElement[] stack = x.getStackTrace();
            if (stack != null) {
                for (StackTraceElement e : stack) {
                    String cn = e.getClassName();
                    if (cn != null && cn.startsWith("io.rong.")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 取出调用参数里的第一个非空 String（用于判断 App 传入的 AppKey）。 */
    static String firstStringArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof String && ((String) a).length() > 0) {
                return (String) a;
            }
        }
        return null;
    }

    /** 给被跳过的初始化方法返回一个类型正确的默认值。 */
    static Object defaultReturn(Method m) {
        Class<?> r = m.getReturnType();
        if (r == void.class || r == Void.class) {
            return null;
        }
        if (r == boolean.class || r == Boolean.class) {
            return Boolean.FALSE;
        }
        if (r == int.class || r == Integer.class) {
            return 0;
        }
        if (r == long.class || r == Long.class) {
            return 0L;
        }
        if (r == short.class || r == Short.class) {
            return (short) 0;
        }
        if (r == byte.class || r == Byte.class) {
            return (byte) 0;
        }
        if (r == char.class || r == Character.class) {
            return (char) 0;
        }
        if (r == float.class || r == Float.class) {
            return 0f;
        }
        if (r == double.class || r == Double.class) {
            return 0d;
        }
        return null;
    }
}
