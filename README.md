# NahangFix v1.3.0 — 蓝航 App（cn.edu.nchu.nahang）融云崩溃 + 消息读不出的修复模块

Xposed / LSPosed 模块，只作用于 `cn.edu.nchu.nahang`。已在 **真机（Android 16 / API 36 / arm64）** 上完成修复与回归验证。

> **v1.3.0 新增**：Miuix/MIUI 风格界面、按蓝航图标重做的新图标、**App 内一键复现/验证补丁**（走 root）。
> 详见 [第 12 章](#12-v130-新增miuix-界面--新图标--app-内一键复现)。

---

## 1. 结论速览（真机实测得到的三条关键事实）

| # | 事实 | 证据 |
|---|------|------|
| 1 | 崩溃根因是**时序竞态**：`MainActivity` 的 Fragment 被同步重建时，App 自己的融云初始化还没跑（约几十毫秒之后才跑） | 修正后的日志：`启动观测 ... RongContext.getInstance()=null`，随后 `放行 App 的真实初始化：RongIM#init[Context, String] appKey=<运行时 AppKey，已脱敏>` |
| 2 | **manifest 里的 `RONG_CLOUD_APP_KEY=<manifest 中的失效旧值，已脱敏>` 是模板遗留的失效值**，App 真正用的是运行时传入的 `<运行时 AppKey，已脱敏>` | 模块拦截日志：`RongIM#init[Context, String] appKey=<运行时 AppKey，已脱敏>`；用错 key 时 navi 服务返回 `401 Your token is not valid` / `errorCode=31004 TOKEN_INCORRECT` |
| 3 | 进程死亡后从最近任务**恢复** `MainActivity` 时，App 的启动流程被整段跳过：融云没初始化、业务层 `TaskDispatcher` 也是 null，且**该路径下 App 永远不会自己初始化融云** | 恢复场景日志中 `appKey=...` 初始化调用次数为 0；崩溃点先是 `RongContext.getEventBus()`，保下来后紧接着 `CacheTask.getMyStaffInfo → TaskDispatcher.getCurrentUserInfo()` 又崩 |

**因此，第一版模块的两处设计是错的**（也是“注入后读不到消息”的直接原因）：

1. ❌ 用 manifest 里的 AppKey **抢先初始化**融云 → SDK 的 AppKey 被钉死成 `<manifest 中的失效旧值，已脱敏>`，连接被融云服务端判为 token 与 AppKey 不匹配（31004），消息自然读不出来；
2. ❌ 在 `:ipc` 进程（IM 长连接所在进程）里也做了干预，并塞进伪造的 `RongContext`；
3. ❌ “跳过重复初始化”逻辑把 App 的 `RongContext#init(Context)` 这类关键调用也跳过了，导致 IMLib 与 IMKit 状态不一致。

v1.2.0 彻底改掉了这三处。

---

## 2. 原始崩溃链路

```text
MainActivity.onCreate(210)
└─ BaseActivity.onCreate(59)
   └─ FragmentActivity.onCreate(316)
      └─ FragmentController.dispatchCreate └─ FragmentManagerImpl.moveToState
         └─ androidx.fragment.app.Fragment.performCreate(2586)
            └─ ContactFragment.onCreate(63)
               └─ BaseNewContactFragment.onCreate(48)
                  └─ RongContext.getInstance().getEventBus()      ← NPE
```

`RongContext.getInstance()` 返回的是融云 IMKit 的静态单例（真机结构：`private static io.rong.imkit.RongContext sContext`），
只有 SDK 初始化后才有值。`getEventBus()` 是普通实例方法，接收者为 null 时由虚拟机直接抛 NPE，
所以**异常栈里看不到 `io.rong.*` 帧**，只能从 message 判断——模块的异常过滤正是依据这一点
（message 含 `io.rong.`，或栈里出现 `io.rong.*` 帧）。

本 App 的 `Application` 是 `cn.rongcloud.rce.App`（基于融云 RCE 模板），它**不在 `Application.onCreate` 里同步初始化**，
而是在启动流程中稍后调用 `RongIM.init(context, "<运行时 AppKey，已脱敏>")`。恢复场景里这段流程被跳过，于是就有了这个竞态。

---

## 3. 修复策略（5 层，全部真机验证过）

| 层 | 类 | 挂钩点 | 作用 |
|----|----|--------|------|
| 1 | `AppInitHook` | `Instrumentation.callApplicationOnCreate`、`Application.attachBaseContext` | **只观测，不修改**：记录 `RongContext.getInstance()` 状态与进程名，确认模块没有抢先初始化 |
| 2 | `InitInterceptor` | `RongIM#init*`、`RongCoreClient#init*`、`RongIMClient#init*`、`IMCenter#init*`、`RongContext#init*` | App 调用 init 之前**释放占位实例**、记录 App 真实 AppKey，然后**原样放行**（返回值与异常都不改），**绝不去重跳过** |
| 3 | `RongGuard` | `RongContext#getEventBus()`；必要时写回静态单例 | `getEventBus()` 永不为 null；当 Fragment 即将创建而单例仍为 null 时，用构造器 `RongContext(Context)` 造占位实例写回 `sContext`，并注入 `EventBus` 字段。**占位实例不调用任何 SDK init** |
| 4 | `FragmentGuard` | `androidx.fragment.app.Fragment#performCreate(Bundle)`（框架方法，与混淆/加固无关） | 创建前再检查一次单例；万一仍抛融云 NPE，就地吞掉并修补 `mCalled`/`mIsCreated`/子 FragmentManager，保住界面 |
| 5 | `EarlyRestoreGuard` | `Instrumentation.callActivityOnCreate/onStart/onResume/onStop/onDestroy` | 检测到**带保存状态恢复**且 App 尚未初始化时的 `MainActivity`：结束它并用 App 启动页（`FLAG_ACTIVITY_CLEAR_TASK`）重新拉起，让 App 走完整初始化；被放弃的那个半初始化实例，其生命周期异常（实测 onCreate 抛业务层 NPE、onDestroy 抛 `IllegalArgumentException`）只对该实例定向吞掉并补 `mCalled` |

关键取舍：

* **不替 App 初始化融云**：`RongGuard.ALLOW_PRE_INIT = false`（默认且建议保持）。真机证明抢先初始化会把 AppKey 钉死，代价是消息彻底不可用。
* **不干预非主进程**：`FixEntry` 在 `:ipc` 等子进程直接返回，IM 长连接进程完全交给 App 自己初始化。
* **判据必须带“保存状态”**：正常冷启动 `savedInstanceState == null`，而本 App 正常启动时 `MainActivity.onCreate` 本来就早于融云初始化，只看单例是否为 null 会**每次启动都误重定向**（v1.1 就踩过这个坑，已修正）。
* **不吞无关异常**：第 4 层只吞“融云相关空指针”；第 5 层只吞被重定向的那一个 Activity 实例的异常。

---

## 4. 实测验证结果

设备：小米（Android 16 / API 36 / arm64-v8a），LSPosed（KernelSU root）。

### 场景 A：正常启动（`monkey -c LAUNCHER`）

| 指标 | 结果 |
|------|------|
| 误重定向 | 0 次 ✓ |
| App 自身初始化 | 使用 `appKey=<运行时 AppKey，已脱敏>` ✓ |
| 融云连接 | `to = CONNECTED` ✓ |
| `31004 / TOKEN_INCORRECT` | 0 次 ✓ |
| `FATAL EXCEPTION` | 0 次 ✓ |

### 场景 B：进程死亡后任务栈恢复（HOME → `kill -9` 主进程 → 恢复任务）

| 指标 | 结果 |
|------|------|
| 检测并重定向 | 1 次（`结束它并重定向到启动页：.../SplashActivity`）✓ |
| 被放弃实例的异常 | 定向吞掉 `callActivityOnCreate`、`callActivityOnDestroy` 两处 ✓ |
| App 自身初始化 | 重定向后正常执行，`appKey=<运行时 AppKey，已脱敏>` ✓ |
| 占位实例 | 使用后**自动释放让位**：`已释放占位 RongContext：App 调用 RongIM#init[...]` ✓ |
| 融云连接 | `to = CONNECTED`，会话列表拿到用户信息（`UserInfo ... 刘俊言` / `has nick name`）✓ |
| `getEventBus()' on a null` | 0 次 ✓ |
| `FATAL EXCEPTION` | 0 次 ✓ |

> 日志中可能出现一条 SDK 自身的良性告警，**不是崩溃**：
> `E Event: Could not dispatch event: ConnectionStatus to ... ChatsFragment
> java.lang.NullPointerException ... ConversationListFragment.setNotificationBarVisibility(316)`
> 这是 EventBus 在视图尚未创建时派发连接状态事件，被 SDK 自己捕获记录，App 继续正常运行。

---

## 5. 如何稳定复现该 bug / 验证补丁是否有效

### 5.1 复现的充要条件

崩溃**不是**每次启动都发生，它需要同时满足三个条件（缺一不可）：

1. **CPU 侧时序**：`MainActivity` 的 Fragment 被重建时，App 自己的 `RongIM.init(...)` 还没执行
   （App 的初始化在 `Application.onCreate` 之后几十毫秒；Fragment 的 `onCreate` 是同步的）；
2. **有保存状态**：`MainActivity` 必须是**带 `savedInstanceState` 重建**的——即进程被杀后从任务栈恢复，
   而不是 `am force-stop` 后全新启动（那样任务栈是空的，Fragment 根本不会被重建，撞不到这行代码）；
3. **Fragment 确实存在**：崩溃点在 `ChatsFragment/ContactFragment/BaseNewContactFragment.onCreate`，
   所以进程被杀之前，App 必须已经进过「消息」页（该 Fragment 已创建并被写入保存状态）。

因此最可靠的配方是：**正常打开到消息页 → HOME → 杀进程 → 恢复任务栈**。

### 5.2 手动复现（4 步）

```bash
# 0) 让 Hook 生效：装上模块后必须 force-stop 一次
adb shell am force-stop cn.edu.nchu.nahang

# 1) 正常打开 App，切到「消息」页，确认会话列表已出现（这一步决定了 Fragment 已创建）
adb shell monkey -p cn.edu.nchu.nahang -c android.intent.category.LAUNCHER 1
#    （等 20~30 秒，手动切到「消息」Tab）

# 2) 回桌面：让 Activity 走 onSaveInstanceState，把任务栈状态落盘
adb shell input keyevent 3

# 3) 杀掉主进程，模拟系统回收（需要 root）
adb shell "su -c 'kill -9 $(pidof cn.edu.nchu.nahang)'"

# 4) 恢复任务栈 —— 系统从“最近任务”回来时走的就是这条路径
adb shell am start -n cn.edu.nchu.nahang/.ui.MainActivity
adb logcat -d | grep -E "getEventBus\(\)' on a null|FATAL EXCEPTION|to = CONNECTED"
```

判定：

| 观察结果 | 含义 |
|---------|------|
| 出现 `getEventBus()' on a null object reference` + `FATAL EXCEPTION`（栈里有 `BaseNewContactFragment.onCreate`） | 复现成功，补丁**未生效** |
| 无 `getEventBus ... null`，且日志出现 `to = CONNECTED` | 补丁生效 |
| 没有崩溃但也没连上 | 复现条件没满足（多半是第 2/3 条），检查是否真的带了保存状态、消息页是否进过 |

### 5.3 一键脚本（推荐，可反复 A/B）

脚本：[tools/repro_crash.ps1](tools/repro_crash.ps1)，自动完成上面 4 步并给出结论。

```powershell
# 复现原始崩溃（让模块进入“只观测不修复”模式）
pwsh -File NahangFix\tools\repro_crash.ps1 -DisableRepairs

# 验证补丁（恢复正常修复）
pwsh -File NahangFix\tools\repro_crash.ps1 -EnableRepairs
```

### 5.4 运行时修复开关（用于 A/B 对照）

模块支持一个**免刷机、免改 LSPosed 配置**的开关：目标 App 私有目录下存在 `nahangfix.off` 时，
模块只记录日志、**不做任何修复**（占位实例、EventBus 兜底、Fragment 吞异常、过早恢复重定向全部停用）：

```bash
# 关闭修复（复现原始崩溃）
adb shell su -c "touch /data/data/cn.edu.nchu.nahang/files/nahangfix.off"
# 打开修复（验证补丁）
adb shell su -c "rm -f /data/data/cn.edu.nchu.nahang/files/nahangfix.off"
```

每次切换后都要 `adb shell am force-stop cn.edu.nchu.nahang`（开关按进程缓存）。

### 5.5 对照结果（真机实测，A/B 均已跑通）

| 实验 | 模块状态 | 预期 | 实测结果 |
|------|---------|------|---------|
| 补丁不生效 | 模块**未注入**（等价于没装模块） | 复现原始 `RongContext.getEventBus()` NPE | ✅ **2/2 次稳定复现**：`RongContextNPE=11 FATAL=1 CONNECTED=0`，崩溃栈与原报告逐行一致（`BaseNewContactFragment.java:48` → `ContactFragment.java:63` → `Fragment.performCreate`） |
| 只观测不修复 | 已注入 + `nahangfix.off` | 同上 | ✅ `RongContextNPE=4 FATAL=1 CONNECTED=0` |
| **补丁生效** | 已注入，无 `nahangfix.off` | 不崩溃且 `to = CONNECTED` | ✅ `RongContextNPE=0 FATAL=0 CONNECTED=1`（复现前提全部满足：MainActivity 在前台、`ChatsFragment created before kill: yes`） |

也就是说：**同一套复现步骤，关掉修复必崩、打开修复不崩且消息可用**，结论闭环。

> 补充观察：若恢复时前台是 `FingerPrintAuthenticationActivity`（App 自身的锁屏指纹页），
> 脚本的 “MainActivity 在前台 / ChatsFragment 已创建” 两条前置提示会变成 `[warn]`，
> 但崩溃**依然会复现**（保存状态里的 Fragment 仍会被重建）。想拿到最干净的对照，
> 就把这两条警告消掉再跑。

> **重要操作陷阱（本次实测踩到）**：用 `adb install -r` 覆盖安装模块 APK 之后，
> LSPosed 里该模块的启用状态可能被重置为关闭（配置库 `modules_state.enabled = 0`）。
> 此时模块**完全不会注入**，日志里一条 `NahangFix` 都不会有，任何 A/B 结论都不成立。
> 处理：回到 LSPosed → 模块 → 重新启用「南航崩溃修复」→ 再 `adb shell am force-stop` 目标 App；
> 刚启用后稍等几秒再启动 App（lspd 需要重载配置，否则第一次启动可能仍未注入）。
> `repro_crash.ps1` 已加前置校验：检测不到模块注入时会**直接中止并提示**，不会给出无效结论。
>
> 另外两个判定坑：
> * **不要用 `NahangFix` 判断模块是否注入**——最近任务/ActivityManager 日志里会出现模块自己的
>   包名 `com.dsh.nahangfix`，造成假阳性（本次误判过一次）；脚本改用 ASCII 标记 `InitInterceptor`。
> * **不要用中文关键字匹配管道里的 logcat**——中文会被控制台代码页弄乱导致匹配失败；
>   脚本里所有判定关键字都是 ASCII。

> 注意：即使“补丁不生效”时没有出现那个 NPE，也请检查是否出现**另一处**崩溃
> `CacheTask.getMyStaffInfo → TaskDispatcher.getCurrentUserInfo()`——那是恢复场景里的第二颗雷，
> 同样由第 5 层（过早恢复重定向）负责处理。

---

## 6. 编译

本机实测可用组合（AGP 与 Gradle 必须配套，AGP 8.x 不能跑在 Gradle 9 上）：

| 组件 | 本机路径 | 版本 |
|------|---------|------|
| JDK | `D:\Dev\JDK17` | 17.0.20.1 |
| Android SDK | `D:\Dev\AndroidSDK` | platforms/android-34、build-tools/34.0.0 |
| Gradle | `D:\Dev\Gradle\gradle-9.8.0` | 9.8.0 |
| AGP | `build.gradle` 指定 | 9.4.1 |

SDK 位置写在 `local.properties`（`sdk.dir=D:/Dev/AndroidSDK`，已被 `.gitignore` 忽略）。

```powershell
$env:JAVA_HOME        = "D:\Dev\JDK17"
$env:ANDROID_HOME     = "D:\Dev\AndroidSDK"
$env:ANDROID_SDK_ROOT = "D:\Dev\AndroidSDK"
cd "D:\数据\Deepseek Harness工作区\NahangFix"
& "D:\Dev\Gradle\gradle-9.8.0\bin\gradle.bat" :app:assembleDebug   --no-daemon --console=plain   # debug
& "D:\Dev\Gradle\gradle-9.8.0\bin\gradle.bat" :app:assembleRelease --no-daemon --console=plain   # release
```

产物：

| 变体 | 路径 | 大小 | 说明 |
|------|------|------|------|
| debug | `app/build/outputs/apk/debug/app-debug.apk` → `dist/NahangFix-v1.2.0-debug.apk` | 905,092 B | 多 dex（AGP 9 产物），带调试信息 |
| **release** | `app/build/outputs/apk/release/app-release.apk` → `dist/NahangFix-v1.2.0-release.apk` | 667,166 B | 单 `classes.dex`，`minifyEnabled false`（**不能开混淆**，Hook 依赖固定类名/方法名），v1+v2 签名 |

> release 变体在 `app/build.gradle` 中复用调试签名（`signingConfig signingConfigs.debug`），
> 因此可以**直接覆盖安装**已装的 debug 版而不必先卸载；若要改用正式签名，请自行配置
> `signingConfigs.release`（换签名后需先卸载旧模块再安装）。

> 工程路径含中文时 AGP 会直接报错退出，`gradle.properties` 里已加 `android.overridePathCheck=true`
> （构建时有一条 experimental 警告，忽略即可）。若日后 aapt2 报路径错误，把工程放到纯英文路径再构建。

> **构建环境坑：`Failed to load native library 'native-platform.dll'`**
> Gradle 启动时要把 `native-platform.dll` 解压到 `java.io.tmpdir` 再加载。在受限沙箱会话里
> `TEMP` 会被重定向到一个临时目录，导致加载失败并报
> `IOException: 系统找不到指定的路径`（表现是 "Gradle could not start your build"）。
> `gradle.properties` 里已固定 `systemProp.java.io.tmpdir=../.g/tmp`（**纯 ASCII 相对路径**，
> 非 ASCII 会破坏 daemon 的 `-D` 参数解析）。构建命令示例：
> ```powershell
> $env:JAVA_HOME="D:\Dev\JDK17"; $env:ANDROID_HOME="D:\Dev\AndroidSDK"
> $env:GRADLE_USER_HOME="<工作区>\.g\home"; $env:TEMP="<工作区>\.g\tmp"; $env:TMP=$env:TEMP
> gradle.bat :app:assembleDebug :app:assembleRelease --no-daemon --console=plain
> ```

产物校验（`verify_apk.py`）：包名 `com.dsh.nahangfix`，minSdk 21 / targetSdk 34，
`assets/xposed_init` = `com.dsh.nahangfix.FixEntry`，manifest 含 `xposedmodule`/`xposedscope`（作用域
`cn.edu.nchu.nahang` 位于 `resources.arsc`），模块类进入 `classes3.dex`，`resources.arsc` 为 STORED，
签名 v1 + v2 校验通过。

---

## 7. 安装与启用

```bash
adb install -r NahangFix/dist/NahangFix-v1.2.0-release.apk
# 在 LSPosed 中启用「南航崩溃修复」，作用域勾选南航 App（manifest 已预置作用域）
adb shell am force-stop cn.edu.nchu.nahang     # 让 Hook 生效
adb logcat -s NahangFix
```

> 重要：模块 APK **更新后也必须 force-stop 目标 App**，否则仍在用旧 dex。

---

## 8. 排错

| 现象 | 日志关键字 | 处理 |
|------|-----------|------|
| 完全没有模块日志 | —— | 检查 LSPosed 里模块的“启用”开关（配置库 `modules_state.enabled` 必须为 1）、作用域是否勾选、是否 force-stop 过 |
| 每次启动都出现重定向 | `在 App 初始化之前被恢复` | 正常冷启动不应出现；若出现说明判据被触发，请回传日志（当前判据已要求 `savedInstanceState != null`） |
| 消息仍读不出来 | `TOKEN_INCORRECT` / `31004` | 说明 SDK 的 AppKey 又被钉错。确认 `RongGuard.ALLOW_PRE_INIT=false`、`HARDCODED_APP_KEY` 为空，且 `:ipc` 进程没有任何 NahangFix 日志 |
| `已用兜底 EventBus 顶替` | `getEventBus() 返回 null` | 单例在但 EventBus 为空，已兜底；频繁出现请回传日志 |
| 子进程出现干预日志 | `非主进程不做任何干预` | 正常：`FixEntry` 只在主进程装 Hook |

---

## 9. 风险、局限与回滚

* **重定向的代价**：进程死亡后从最近任务回到 App 会重新走一次启动页（比直接闪回主页慢一点），换来的是不再崩溃、消息可用。每个进程只重定向一次，不会循环。
* **占位实例只用于保命**：仅在“Fragment 即将创建 + 单例仍为 null”的窗口内存在，App 真初始化时立即让位；期间它只保证 `getEventBus()` 等调用不为 null，不承担任何 SDK 语义。
* **不改动目标 App 安装包**：全部在运行时修复，卸载模块或取消作用域即完全回滚，不影响 App 自身更新与签名校验。
* **App 更新后的适配点**：`FixEntry.TARGET_PACKAGE`、`FragmentGuard.APP_FRAGMENT_CLASSES`（找不到类只会打警告）、`EarlyRestoreGuard.MAIN_ACTIVITY`。
* **加固 App**：业务类可能被壳替换导致挂钩失败（日志会提示），但第 3、4、5 层都在框架类上，仍然有效。

---

## 10. 备选方案（不用 Xposed）

1. 反编译重打包：把 `BaseNewContactFragment.onCreate` 的 `RongContext.getInstance().getEventBus()` 改为空判断，
   并让 `MainActivity` 在未初始化时走启动页；缺点是要重签名、App 更新后要重做。
2. 用 Magisk 模块替换 dex：本质同上。
3. 每次冷启动都彻底清后台任务再打开 App（治标，用户操作成本高）。

---

## 11. 目录结构

```text
NahangFix/
├─ settings.gradle / build.gradle / gradle.properties   # AGP 9.4.1 + Gradle 9.8.0 + 中文路径开关
├─ local.properties                      # sdk.dir=D:/Dev/AndroidSDK（本地路径，不提交）
├─ dist/NahangFix-v1.3.0-release.apk      # 真机验证过的产物（另有 -debug.apk）
├─ tools/repro_crash.ps1                  # 命令行一键复现/验证（A/B 对照，独立于 App）
├─ .github/workflows/build.yml            # 换机器/无 SDK 时用 Actions 出包
└─ app/
   ├─ build.gradle                        # compileOnly de.robv.android.xposed:api:82
   └─ src/main/
      ├─ AndroidManifest.xml               # xposedmodule / xposedscope / 前台服务声明
      ├─ assets/xposed_init                # 入口类：com.dsh.nahangfix.FixEntry
      ├─ res/layout/activity_main.xml      # Miuix 风格界面
      ├─ res/values/{colors,dimens,styles}.xml + res/color/switch_*
      ├─ res/drawable/…                    # 卡片/按钮/日志框背景 + 行矢量图标 + 自适应图标图层
      ├─ res/mipmap-*/ic_launcher*.png     # 新图标（mdpi..xxxhdpi，方形 + 圆形）
      └─ java/com/dsh/nahangfix/
         ├─ FixEntry.java                  # 入口：包名过滤 + 只主进程 + 五层安装
         ├─ AppInitHook.java               # 第 1 层：启动观测 + 写心跳文件
         ├─ InitInterceptor.java           # 第 2 层：让位 + 记录 AppKey，原样放行
         ├─ RongGuard.java                 # 第 3 层：单例/EventBus 兜底 + 占位实例 + 诊断
         ├─ FragmentGuard.java             # 第 4 层：框架层 performCreate 兜底
         ├─ EarlyRestoreGuard.java         # 第 5 层：过早恢复重定向 + 半初始化实例收尾
         ├─ StateFile.java                 # 在目标进程内写心跳（注入判定的依据）
         ├─ AppKeyFinder.java              # 读取 manifest meta-data（现仅诊断用）
         ├─ XLog.java                      # logcat + Xposed 双通道日志
         ├─ MainActivity.java              # Miuix 界面：状态面板 / 开关 / 复现按钮 / 日志
         ├─ RootShell.java                 # su 多路回退执行（su -c / sh 中转 / 绝对路径）
         ├─ PatchState.java                # 一次 su 批量探测状态（开关 / 心跳 / pid / logcat）
         ├─ ReproRunner.java               # 复现/验证流程编排 + 判定
         └─ ReproService.java              # 流程期间前台服务保活（通知显示当前步骤）

（工作区根目录另有 check_nahangfix.py 源码自检、verify_apk.py 产物校验、diag_lsposed.py LSPosed 诊断、
 tools/extract_icon.py + tools/inspect_icon.py + tools/make_icon.py 图标工具链）
```

---

## 12. v1.3.0 新增：Miuix 界面 / 新图标 / App 内一键复现

### 12.1 Miuix（MIUI/HyperOS）风格界面

`res/layout/activity_main.xml` + `res/values/colors.xml|dimens.xml|styles.xml` 按 Miuix 设计语言重做：

| 元素 | 做法 |
|------|------|
| 页面底色 / 卡片 | `#F5F6F8` 底 + 白色圆角 16dp 卡片，靠留白分层（不用阴影） |
| 头卡 | 品牌蓝渐变（`#028BF8 → #016FC6`，取自蓝航图标主色）圆角 20dp，含图标 + 标题 + 状态摘要 |
| 分组标题 | 13sp 次要色小标题（`SectionTitle` 样式），行高 52dp，行分隔线 `#EBEDF0` |
| 行图标 | 28dp 浅蓝圆底（`bg_row_icon`）+ 矢量图标（刷新/复制/清空/对勾/信息） |
| 开关 | `Switch` + 自定义 `color/switch_track`：开=品牌蓝，关=`#D8D8D8` |
| 应用主题 | `Theme.DeviceDefault.Light.NoActionBar`（无系统 ActionBar，蓝底状态栏） |

功能分区：**运行状态**（模块注入 / 修复开关 / 融云连接 / 目标进程 四个圆点行）→ **补丁控制**（启用修复开关、强制停止目标 App）→ **复现与验证**（两个按钮 + 进度 + 日志框）→ **诊断**（打开 LSPosed / 刷新状态 / 复制日志 / 清空日志）→ **关于**。

### 12.2 新图标（按蓝航图标再设计）

设计基因直接继承蓝航原图标，**中央的白色圆章与蓝色纸飞机是从原图里裁出来的**（`tools/make_icon.py` 用 `EMBLEM_R=245` 裁内圆盘，并把原图下方的 "1952" 涂白），保证一眼能看出同源：

* 底色：从原图左上角采样得到的品牌蓝（`#028BF8`）→ 深蓝竖向渐变，圆角方形；
* 中央：原图标的白色圆章 + 蓝色纸飞机；
* 右下：**修复徽标**——深蓝圆底 + 白色扳手 + 白描边（表达"修复/补丁"语义）；
* 输出：`mipmap-mdpi..xxxhdpi` 的传统图标与圆形图标、`drawable-*/ic_launcher_foreground.png` 自适应前景、`drawable-*/ic_launcher_monochrome.png`（HyperOS 主题图标），外加 `mipmap-anydpi-v26/ic_launcher.xml`。

工具：`tools/extract_icon.py`（从 APK 里抽取原图标）、`tools/inspect_icon.py`（量出圆盘半径）、`tools/make_icon.py`（生成整套图标）。

### 12.3 App 内一键复现 / 验证（需要 root）

| 按钮 | 行为 |
|------|------|
| **一键复现崩溃** | **自动关闭修复**（放置 `nahangfix.off`，界面开关同步拨到左侧并在流程期间锁住）→ 冷启动蓝航 → 校验注入 → 回桌面 → `kill -9` 主进程 → `monkey` 恢复任务栈 → 盯 crash 缓冲 5 轮判定；预期出现原始 `RongContext.getEventBus()` 空指针 |
| **一键验证补丁** | **自动开启修复**（删除 `nahangfix.off`，界面开关同步拨到右侧）→ 执行同一套步骤；预期 5 轮都不崩，`imStatus=CONNECTED` 时给出"消息可用"结论 |

> 交互约定：两个按钮都自带开关管理，**不需要你手动拨开关**；流程期间开关会置灰锁定，结束后自动放开并刷新为真实状态。
> 也可以用命令行触发（不需要 root、不会卡）：`adb shell am start -n com.dsh.nahangfix/.MainActivity --es autotest verify|repro`。

实现要点（都是这轮真机踩坑后加上的）：

1. **root 调用**：`RootShell` 依次尝试 `su -c` / `sh -c 'su -c …'` / `/system/bin/su -c`，并缓存成功方式。
   实测本机 App 直接 `ProcessBuilder("su", …)` 会拿到 `error=13 Permission denied`（SELinux 不允许直接 exec 未打标签的 su），必须走 `sh` 中转。
2. **合并 su 调用**：本机每次 `su` 启动开销 10s+，所以把多项探测（`id`/off 文件/心跳/pidof/logcat）**合并成一次 su 调用**并用 `@@标记@@` 分段解析；冷启动也合并成一条命令。超时统一放宽到 90~150s。
3. **状态回传必须走「共享路径」**（这是本轮最关键的一条）：Android 给每个 App 独立挂载命名空间，
   模块 App **即使拿到 root 也看不到目标 App 的私有目录**（`cat /data/data/cn.edu.nchu.nahang/files/…`
   直接 `No such file or directory`，而从 adb/`run-as` 看却存在）。因此状态文件与开关文件都放在
   **目标 App 的外部媒体目录**：
   * 状态：`/sdcard/Android/media/cn.edu.nchu.nahang/nahangfix.state`（模块写 → App 用 root 读）
   * 开关：`/sdcard/Android/media/cn.edu.nchu.nahang/nahangfix.off`（App 写 → 模块读；存在=只观测）
   字段：`ts=` / `pid=` / `repairsDisabled=` / `imStatus=` / `where=`。
   同时模块还会往 **logcat 打单行 ASCII 标记**（`NAHANGFIX_STATE …`）作为兜底，并每约 60 秒重发一次
   （logcat 是环形缓冲，会被冲掉）。App 侧读取顺序：共享文件 → logcat 标记 → 私有文件（仅诊断）。
   * **注入**：上报的 `pid == 当前目标进程 pid` → 已注入；pid 不一致 → 显示"未知"并给出操作提示，**不再误报"未注入"**。
   * **融云连接**：模块挂了 `io.rong.eventbus.EventBus#post(Object)`（识别 `ConnectionStatus` 事件）、
     包装 App 注册的 `ConnectionStatusListener`、并每 10 秒反射轮询 `RongIMClient.getCurrentConnectionStatus()`，
     三者取最新写入 `imStatus=`；**不再依赖 logcat**（`to = CONNECTED` 几行很快被轮转挤掉，这就是之前面板误报"未连接"的原因）。
   * 踩坑：一开始用"1 秒节流 + 只在变化时写"，结果状态恰好落在节流窗口时更新被永久丢弃（标记显示 CONNECTED、
     文件停在 CONNECTING）。改成与"已落盘值"比较，并让轮询周期性补写。
4. **熄屏会让流程"卡住"**：实测屏幕一黑，本 App 被冻结/限流，流程就停在某个步骤（先后卡在"恢复任务栈""杀掉主进程"）。
   现在流程开始时会 `input keyevent 224` 唤醒屏幕、申请 `PARTIAL_WAKE_LOCK`，并在 Activity 上置
   `FLAG_KEEP_SCREEN_ON`；另外每一步都显示已用时（`[01:23] 3/6 …`），并有 8 分钟总超时兜底，不会再"静默卡死"。
5. **`am start` 会阻塞**：恢复任务栈那条命令原本同步执行，叠加 su 启动开销后经常超时把流程挂住。
   现在改成后台放行：`logcat -c; (am start -n … >/dev/null 2>&1 &); echo restore-issued`，命令立即返回。
6. **前台服务保活**：复现要 1~3 分钟，期间目标 App 占前台、本 App 退后台，会被系统回收（实测丢过结论）。现在流程期间启动 `ReproService`（`foregroundServiceType=dataSync` + 常驻通知显示当前步骤），日志也改成 `commit()` 同步落盘。
7. **交互细节**：程序化同步开关状态时屏蔽 `OnCheckedChanged`（否则会反过来写一次开关文件）；`appendLog` 统一 marshalling 到主线程（后台线程直接改 View 会 `CalledFromWrongThreadException` 崩溃）；
   **不再做任何自动滚动/抢焦点**（点按钮后页面不会再自己跳到日志区，日志框也设了 `descendantFocusability="blocksDescendants"`）。
8. **桌面图标可隐藏 + 隐藏后仍能从 LSPosed 打开**：
   * 桌面入口用 `activity-alias`（`.Launcher`，`CATEGORY_LAUNCHER`），面板里的「隐藏桌面图标」开关只禁用这个 alias
     （`COMPONENT_ENABLED_STATE_DISABLED` + `DONT_KILL_APP`），`MainActivity` 本身保持可用；
   * **关键补丁（v1.3.0 修复）**：再注册一个 `CATEGORY_INFO` 入口 alias `.LauncherInfo`。
     `PackageManager#getLaunchIntentForPackage()` 的实现是**先查 `CATEGORY_INFO`、查不到才查 `CATEGORY_LAUNCHER`**，
     而启动器只列 `CATEGORY_LAUNCHER` —— 所以隐藏桌面图标后，LSPosed 模块列表里的「打开」依然可用，
     桌面却不会出现图标。（没有这条时，隐藏图标后 LSPosed 里就没有打开模块的入口了。）
   * 验证命令（设备在线时）：
     `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.INFO com.dsh.nahangfix`
     应输出 `com.dsh.nahangfix/.LauncherInfo`；
   * 兜底：`adb shell am start -n com.dsh.nahangfix/.MainActivity`。
9. **移除了"打开 LSPosed"入口**：LSPosed 管理器没有固定包名（通过注入 shell 启动），无法用 `getLaunchIntentForPackage` 拉起，这个入口已删除。
10. **诊断信息**：面板底部常显 `root:`、`模块状态文件：时间（注入判定细节）`、`连接状态来源 + imStatus`、
    `前台 Activity`、`stateSource/marker/fileHb/logcatLines`、`桌面图标：显示中/已隐藏`；流程日志里也带 `[diag#n]` 行与每步用时。
11. **崩溃判定重做**（解决"明明崩了却报未复现"）：
    * 不做事后大窗口扫描 —— 崩溃日志会被 2 万行/分钟级别的系统日志秒级轮转掉；
    * 改在**恢复任务栈之后、App 侧循环盯 5 轮**（每轮一条命令）：读 **crash 缓冲**（`logcat -c` 之后只会有本次崩溃，
      内容只有几十行）+ 目标进程是否还在，命中即判定复现；
    * 恢复任务栈用 **`am start -n <MainActivity>`**（把已有任务栈带到前台 = 带 `savedInstanceState` 恢复，命令立即返回）：
      **不要用 `monkey`** —— 目标 App 一启动就崩时 monkey 会一直等它"稳定"，实测阻塞好几分钟
      （表现就是"卡在清空日志并恢复任务栈"）；也**不要用 `am start … &`**（后台任务让 su 会话不关闭，后续命令排队）；
    * 命令里**不使用 `|` 管道**（改用 `echo @@标记@@` 分段、App 侧解析）——实测管道在 App 的 su 会话里会挂住；
    * 实测日志：`[watch 1] crash缓冲命中=0 目标进程数=1` → `[watch 2] crash缓冲命中=1 目标进程数=0` →
      `盯崩溃结果：npe` → `统计： RongContextNPE=1 FATAL=1` → `结论：补丁生效（对照成立）`。
12. **状态行的收尾**（解决"已经补丁生效了，界面/通知却还写着盯崩溃第 1/5 轮"）：
    * 原来 `finish()` 只更新界面那一行，**没更新 `sLastStep`、也没更新通知**，于是 `onResume`/刷新时又把界面还原成最后一次
      `step()` 的文案（`sleep()` 的倒计时更是直接绕过 `sLastStep`）；
    * 现在统一走 `setStep()`：界面行、通知、`sLastStep` 三者一起更新，`finish()` 也用它写入结论；
    * 结论额外**持久化**到 `SharedPreferences.last_step`，重进 App 仍显示结论；
    * 流程结束时 `ReproService` 会 `stopForeground(STOP_FOREGROUND_REMOVE)` + `cancel(通知)`，
      不会再留下停在半途的常驻通知（服务也改为 `START_NOT_STICKY`，避免被系统重启后残留旧通知）。
13. **流程中途"卡在恢复任务栈"的真正元凶：熄屏**。屏幕黑掉后 `monkey`/`am` 会变慢甚至长时间不返回，
    而阻塞期间没有任何刷新，看起来就是"卡住 + 通知不更新"。现在：
    * 流程开始即 `input keyevent 224` + **`svc power stayon true`**（USB 连接期间屏幕常亮），结束还原；
    * 每条慢命令都套**心跳**：`runSlow()` / `beginHeartbeat()` 每 2 秒把步骤写成
      `[00:31] 恢复任务栈（am start）（已等待 12s）`，界面与通知一直在动，不会再有"静默卡死"观感；
    * 步骤文本/结论**在 worker 线程直接落盘**（不再依赖 UI 回调），所以即使界面被占住，
      通知与 `SharedPreferences.last_step` 也一直是最新的。
14. **让系统也抓到崩溃并弹框**（复现时）：MIUI 等 ROM 常把错误对话框关掉，表现为"App 崩了但没有任何提示"。
    复现流程会先读 `settings get global hide_error_dialogs`，置 `0`，结束再还原（原本是 null 就 `settings delete`），
    这样系统自己的"蓝航已停止运行"对话框会正常弹出，与模块侧的 crash 缓冲取证互不冲突。

### 12.4 纯命令行的调试方法

**注意（实测踩过的坑）**：从电脑侧 `adb shell "su -c …"` 时，**adb 不会关闭 stdin，而 su 会一直等 stdin**
→ 命令永不返回（主机上会残留一个挂死的 `adb.exe`，表现为"整条命令卡住很久没响应"）。
对照实测：`adb shell "su -c id"` 卡死；`adb shell "su -c id < /dev/null"` **2 秒返回**。
因此优先用下面两类**不需要 root** 的入口；确实需要 root 时务必加 `< /dev/null`：

```powershell
# A) 触发流程（不需要 root，也不需要点界面）
$adb shell "am start -n com.dsh.nahangfix/.MainActivity --es autotest repro"    # 复现崩溃
$adb shell "am start -n com.dsh.nahangfix/.MainActivity --es autotest verify"   # 验证补丁

# B) 读结果（run-as 直接读本应用私有日志，不需要 root）
$adb shell "run-as com.dsh.nahangfix cat /data/data/com.dsh.nahangfix/shared_prefs/nahangfix_ui.xml"
```

需要 root 的命令（下面这些）请**在手机上的 App 里**用按钮完成，或在命令末尾加 `< /dev/null` 再执行
（见上面那条坑：不加会挂死）：

```powershell
# 模块状态（共享路径，模块 App 与目标 App 都能看到）
$adb shell "run-as com.dsh.nahangfix su -c 'cat /sdcard/Android/media/cn.edu.nchu.nahang/nahangfix.state' < /dev/null"

# 切换"只观测"开关（存在=只观测，用于复现原始崩溃）
$adb shell "run-as com.dsh.nahangfix su -c 'touch /sdcard/Android/media/cn.edu.nchu.nahang/nahangfix.off' < /dev/null"
$adb shell "run-as com.dsh.nahangfix su -c 'rm -f /sdcard/Android/media/cn.edu.nchu.nahang/nahangfix.off' < /dev/null"
$adb shell "run-as com.dsh.nahangfix su -c 'am force-stop cn.edu.nchu.nahang' < /dev/null"   # 开关按进程缓存，必须重启目标

# 看崩溃原文（crash 缓冲很小，几十行）
$adb shell "run-as com.dsh.nahangfix su -c 'logcat -d -b crash -v brief -t 300' < /dev/null"
```

期望结果：开关置为"只观测"时能看到原始空指针；删除开关文件并重启目标 App 后不再出现，且 `imStatus=CONNECTED`。
（也可以直接跑 `tools/repro_crash.ps1`：它把以上步骤 + A/B 统计都封装好了。）

**已在真机验证的结论（2026-10-05 22:3x）**：`autotest repro` 两轮均在第 2 轮命中 crash 缓冲，
`统计： RongContextNPE=1 FATAL=1 CONNECTED=0（盯崩溃=npe）` → `结论：补丁生效（对照成立）`；
`崩溃判定误报`与`卡在盯崩溃第 1 轮`两个问题都已修复（见 12.3 第 11 条）。

### 12.5 本机 root 环境备注

* KernelSU（`context=u:r:ksu:s0`），App 首次调用 `su` 需在管理器里授权；
* 命名空间隔离：模块 App 只能看到 `/sdcard/Android/media/<目标包名>/`，看不到目标私有目录（见 12.3 第 3 条）；
* 从 shell 直接 `su -c cat` 目标私有目录里的文件会被 SELinux 拒（`Permission denied`），但从**本 App 自己的 uid**
  （等价 `run-as com.dsh.nahangfix su -c …`）可以读共享路径；
* 蓝航自带指纹锁/登录页：停在 `FingerPrintAuthenticationActivity` 时 IM 不会连接，面板会显示
  "未连接（停在指纹验证页）"而不是笼统的"未连接"；
* 用 `adb install -r` 覆盖安装模块后，LSPosed 里该模块的启用状态可能被重置为关闭，需要重新启用并强制停止目标 App。

