package com.dsh.nahangfix;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * 通过 {@code su} 执行 root 命令（KernelSU / Magisk 均可）。
 *
 * <p>模块 App 需要 root 的原因：读写目标 App 私有目录下的开关文件、读取全局 logcat、
 * 结束/拉起目标 App 进程。首次执行时 KernelSU 会弹出授权提示。</p>
 *
 * <p><b>为什么要多路回退</b>：实测在本机（KernelSU）上，App 直接
 * {@code ProcessBuilder("su", ...)} 会拿到 {@code error=13, Permission denied}（SELinux
 * 拒绝直接 exec 未打标签的 su），而经由 {@code sh} 或绝对路径调用则可以成功。
 * 因此这里按顺序尝试多种调用方式，并缓存成功的那一种。</p>
 */
final class RootShell {

    static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }

        boolean ok() {
            return code == 0;
        }

        String firstLine() {
            int idx = output.indexOf('\n');
            String line = idx >= 0 ? output.substring(0, idx) : output;
            return line.trim();
        }
    }

    /** 成功过的调用方式下标，-1 表示尚未确定。 */
    private static volatile int sMethod = -1;
    private static volatile String sMethodName = "";

    private RootShell() {
    }

    static Result run(String command) {
        // 默认 90s：本机每次 su 调用有 10s+ 的启动开销（KernelSU），超时给小了会读不到输出
        return run(command, 90);
    }

    static Result run(String command, int timeoutSeconds) {
        String[][] variants = {
                {"su", "-c", command},
                {"sh", "-c", "su -c " + quote(command)},
                {"sh", "-c", "/system/bin/su -c " + quote(command)},
                {"/system/bin/su", "-c", command},
        };
        String[] names = {"su -c", "sh -c 'su -c …'", "sh -c '/system/bin/su -c …'", "/system/bin/su -c"};

        // 全局截止时间：所有回退方式共享这一次超时预算。
        // （踩过的坑：每种方式各给一次完整超时 → 40s 的命令最坏要等 160s，看着就是"卡死"。）
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        // 先用上次成功的方式
        int cached = sMethod;
        if (cached >= 0 && cached < variants.length) {
            Result r = exec(variants[cached], remainingSeconds(deadline, timeoutSeconds));
            if (r != null) {
                return r;
            }
        }

        Result last = null;
        String lastError = "";
        for (int i = 0; i < variants.length; i++) {
            int left = remainingSeconds(deadline, timeoutSeconds);
            if (left <= 0) {
                return new Result(-1, (last == null ? lastError : last.output)
                        + "\n[超过总超时 " + timeoutSeconds + "s，放弃剩余回退方式]");
            }
            Result r = exec(variants[i], left);
            if (r == null) {
                lastError = "exec 被拒绝：" + names[i];
                continue;
            }
            sMethod = i;
            sMethodName = names[i];
            return r;
        }
        return new Result(-1, last == null ? lastError : last.output);
    }

    /** 剩余秒数（至少 1，避免 0 导致立即超时）。 */
    private static int remainingSeconds(long deadline, int fallback) {
        long left = (deadline - System.currentTimeMillis()) / 1000L;
        if (left <= 0) {
            return 0;
        }
        return (int) Math.min(left, fallback);
    }

    static String methodName() {
        return sMethodName;
    }

    /**
     * 启动一个进程并抓取合并输出；返回 null 表示 exec 本身失败（用于换下一种调用方式）。
     */
    private static Result exec(String[] argv, int timeoutSeconds) {
        Process process = null;
        final StringBuffer buffer = new StringBuffer();
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (Throwable t) {
            return null;
        }
        try {
            try {
                OutputStream os = process.getOutputStream();
                os.close();
            } catch (Throwable ignore) {
                // 某些 su 实现会在 stdin 上等待，关闭即可
            }

            final Process target = process;
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(target.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            buffer.append(line).append('\n');
                        }
                    } catch (Throwable ignore) {
                        // 进程结束时读取抛异常属正常
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            Integer exit = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    exit = process.exitValue();
                    break;
                } catch (IllegalThreadStateException notYet) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (exit == null) {
                // 超时必须真正杀掉，否则流程会一直被这条命令拖住
                try {
                    process.destroyForcibly();
                } catch (Throwable ignore) {
                    process.destroy();
                }
                reader.join(500);
                return new Result(-1, buffer + "\n[命令超时 " + timeoutSeconds + "s]");
            }
            reader.join(800);
            return new Result(exit, buffer.toString());
        } catch (Throwable t) {
            return new Result(-1, "执行失败：" + t);
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignore) {
                    // 忽略
                }
            }
        }
    }

    /** 单引号安全包裹，供 sh -c 使用。 */
    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
