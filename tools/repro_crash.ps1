# repro_crash.ps1 - reliably reproduce / verify the crash fixed by NahangFix
#
# Why this reproduces the bug (verified on a real device):
#   The app initialises RongCloud inside its own start-up flow (tens of ms AFTER
#   Application.onCreate), while MainActivity's fragments are re-created SYNCHRONOUSLY
#   in FragmentActivity.onCreate (dispatchCreate -> Fragment.performCreate ->
#   ChatsFragment/ContactFragment.onCreate). So the crash needs:
#     (a) a fragment re-creation that happens before the app's own RongIM.init(),
#     (b) a *saved instance state* (i.e. process killed, task stack restored) -
#         "adb shell am force-stop" + fresh launch gives an EMPTY task and will NOT hit it,
#     (c) the message tab must have been visited before the kill (so the fragment exists).
#
# Recipe: cold start -> open message tab -> HOME (save state) -> kill -9 main process
#         -> restore the task stack (this is what "recents" does).
#
# Usage (Windows):
#   powershell -ExecutionPolicy Bypass -File repro_crash.ps1 -DisableRepairs   # reproduce original crash
#   powershell -ExecutionPolicy Bypass -File repro_crash.ps1 -EnableRepairs    # verify the patch
#
# The switch is implemented by a marker file inside the target app's private dir:
#   /data/data/<pkg>/files/nahangfix.off   (present => module only observes, repairs disabled)
# Nothing in the LSPosed configuration is touched, so you can A/B test repeatedly.

param(
    [string]$Adb = "D:\Dev\AndroidSDK\platform-tools\adb.exe",
    [string]$Package = "cn.edu.nchu.nahang",
    [string]$MainActivity = "cn.edu.nchu.nahang/.ui.MainActivity",
    [switch]$DisableRepairs,
    [switch]$EnableRepairs,
    [int]$ReadyWaitSeconds = 25,
    [int]$AfterRestoreWaitSeconds = 30,
    # Coordinates of the bottom "messages" tab (default fits a 1220x2656 screen)
    [int]$MessageTabX = 152,
    [int]$MessageTabY = 2570
)

$ErrorActionPreference = "Continue"
# PowerShell 7.4+ turns native stderr into errors; adb prints progress there.
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Say([string]$message) { Write-Host $message }

if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }
$state = ((& $Adb get-state 2>$null) 2>&1) -join ""
if ($state -notmatch "device") { throw "no authorized device (adb get-state => $state)" }

# NOTE: the switch lives in the SHARED path (target app's external media dir), because the module
# app cannot see the target's private dir (per-app mount namespace), see README 12.3 item 3.
# NOTE 2: every `su -c` MUST be followed by `< /dev/null`: adb does not close stdin and su waits on
# it forever -> the whole script hangs (observed: a stuck adb.exe). See README 12.4.
$offPath = "/sdcard/Android/media/$Package/nahangfix.off"
if ($DisableRepairs) {
    & $Adb shell "su -c 'mkdir -p /sdcard/Android/media/$Package; touch $offPath; chmod 666 $offPath' < /dev/null" 2>$null | Out-Null
    Say "[switch] repairs DISABLED -> $offPath"
}
if ($EnableRepairs) {
    & $Adb shell "su -c 'rm -f $offPath' < /dev/null" 2>$null | Out-Null
    Say "[switch] repairs ENABLED (off file removed)"
}
# 只观测时模块会进入"只观测"模式；这里用模块写下的共享状态文件确认（同步读，不需要 su）
$stateFile = "/sdcard/Android/media/$Package/nahangfix.state"
$offPresent = (((& $Adb shell "run-as com.dsh.nahangfix su -c 'cat $stateFile 2>/dev/null' < /dev/null" 2>$null) -join "") -match "repairsDisabled=true")
Say ("[switch] module reports repairsDisabled=true: " + $(if ($offPresent) { "YES -> module only observes" } else { "no -> module repairs normally" }))

# ---------- 1) cold start ----------
Say "[1/6] cold start ..."
& $Adb shell "am force-stop $Package" 2>$null | Out-Null
& $Adb logcat -c 2>$null | Out-Null
& $Adb shell "monkey -p $Package -c android.intent.category.LAUNCHER 1" 2>$null | Out-Null
Start-Sleep -Seconds $ReadyWaitSeconds

# ---------- 1b) precondition: the module must really be injected ----------
# NOTE: adb-installing (overwriting) the module APK can reset its "enabled" flag in LSPosed,
# in which case the module is NOT injected at all and any A/B result would be meaningless.
$preLog = & $Adb logcat -d 2>$null
# NOTE: match an ASCII-only marker from the module's own log output.
#   - "NahangFix" alone is NOT safe: recents/ActivityManager logs contain the module's
#     own package name com.dsh.nahangfix (false positive).
#   - Chinese markers are NOT safe either: logcat text piped through the console gets
#     mangled by the active code page, so the comparison silently fails.
$moduleLines = ($preLog | Select-String -SimpleMatch "InitInterceptor" | Measure-Object).Count
$moduleActive = $moduleLines -gt 0
Say ("      module injected: " + $(if ($moduleActive) { "YES ($moduleLines log lines)" } else { "NO  <-- fix this first!" }))
if (-not $moduleActive) {
    Say "      [ERROR] NahangFix was not injected into the app process. In LSPosed check:"
    Say "              1) module 'NahangFix' is ENABLED  (adb install -r can reset it to disabled;"
    Say "                 verify: modules_state.enabled must be 1)"
    Say "              2) scope includes cn.edu.nchu.nahang"
    Say "              3) the app was force-stopped after enabling the module"
    Say "      Aborting: without the module an A/B comparison proves nothing."
    exit 2
}

# ---------- 2) make sure MainActivity is on top ----------
$top = ""
for ($i = 0; $i -lt 3; $i++) {
    $top = (((& $Adb shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>$null) -join " ")).Trim()
    if ($top -match "MainActivity") { break }
    & $Adb shell "input keyevent 4" 2>$null | Out-Null
    Start-Sleep -Seconds 3
}
Say "      top activity: $top"
if ($top -notmatch "MainActivity") { Say "      [warn] MainActivity is not on top; the repro may miss" }

# ---------- 3) switch to the messages tab so the chat fragment exists ----------
& $Adb shell "input tap $MessageTabX $MessageTabY" 2>$null | Out-Null
Start-Sleep -Seconds 5
$preLog = & $Adb logcat -d 2>$null
$hasChats = ($preLog | Select-String -SimpleMatch "ChatsFragment" | Measure-Object).Count -gt 0
Say ("      ChatsFragment created before kill: " + $(if ($hasChats) { "yes" } else { "NO [warn] (adjust -MessageTabX/-MessageTabY)" }))

# ---------- 4) HOME: let the activity save its instance state ----------
Say "[2/6] HOME (so the task keeps saved instance state) ..."
& $Adb shell "input keyevent 3" 2>$null | Out-Null
Start-Sleep -Seconds 4

# ---------- 5) kill the main process (simulates LMK / manual clean-up) ----------
$procId = ((((& $Adb shell "pidof $Package" 2>$null) -split "\s+") | Where-Object { $_ }) | Select-Object -First 1)
if (-not $procId) { throw "main process of $Package not found" }
Say "[3/6] kill -9 $procId ..."
& $Adb shell "su -c 'kill -9 $procId'" 2>$null | Out-Null
Start-Sleep -Seconds 3
$left = (((& $Adb shell "pidof $Package" 2>$null) -split "\s+") | Where-Object { $_ }) -join ","
Say "      remaining pids: [$left]"

# ---------- 6) restore the task stack (exactly what "recents" does) ----------
& $Adb logcat -c 2>$null | Out-Null
Say "[4/6] restore task stack: am start -n $MainActivity ..."
& $Adb shell "am start -n $MainActivity" 2>$null | Out-Null
Start-Sleep -Seconds $AfterRestoreWaitSeconds

# ---------- verdict ----------
Say "[5/6] analyze logcat ..."
$log = & $Adb logcat -d 2>$null
$rongNpe = ($log | Select-String -SimpleMatch "getEventBus()' on a null" | Measure-Object).Count
$fatal   = ($log | Select-String -SimpleMatch "FATAL EXCEPTION" | Measure-Object).Count
$conn    = ($log | Select-String -SimpleMatch "to = CONNECTED" | Measure-Object).Count
$appKey  = ($log | Select-String -SimpleMatch "appKey=" | Measure-Object).Count
Say ("      RongContextNPE={0}  FATAL={1}  CONNECTED={2}  appKeyLines={3}" -f $rongNpe, $fatal, $conn, $appKey)

Say "[6/6] verdict:"
if ($rongNpe -gt 0) {
    Say "  >> BUG REPRODUCED: 'RongContext.getEventBus() on a null object reference' + FATAL EXCEPTION"
} elseif ($fatal -gt 0) {
    $cause = ($log | Select-String -SimpleMatch "Caused by" | Select-Object -First 1)
    Say "  >> CRASHED, but not the RongContext NPE. First cause line:"
    Say "     $cause"
} elseif ($conn -gt 0) {
    Say "  >> NO CRASH + IM CONNECTED  =>  patch effective"
} else {
    Say "  >> no crash, but IM not connected - inspect 'adb logcat -s NahangFix' manually"
}
