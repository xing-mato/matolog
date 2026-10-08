package com.MATO.log.util;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import rikka.shizuku.Shizuku;

/**
 * 通过 Shizuku 执行一条 shell 命令。
 *
 * <p><b>为什么用 shell 而不是反射调隐藏 API</b>：要把「使用情况访问」直接开上，
 * 有两条路 —— 反射调隐藏的 {@code IPackageManager}，或者执行一条
 * {@code appops set ... allow}。前者要自己拼 Parcel、且事务码随版本漂移，
 * 写错了是静默失败；后者是稳定的命令行接口，出错时还有明确的退出码与 stderr。
 * 这个功能只是「省用户点两下」，不值得为它扛下猜事务码的风险。
 *
 * <p>所有方法都在子线程调用：命令要起进程、等结果，不能占界面线程。
 */
public final class ShizukuShell {

    /** 单条命令的超时：正常几百毫秒就回来了，超过这个时间说明出了问题 */
    private static final int TIMEOUT_MS = 10_000;

    private ShizukuShell() {
    }

    /** 一次执行的结果 */
    public static final class Result {
        public final int code;
        public final String out;
        public final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
        }

        public boolean ok() {
            return code == 0;
        }

        /** 给界面用的一句话 */
        public String message() {
            if (ok()) {
                return out.isEmpty() ? "完成" : out;
            }
            String e = err.isEmpty() ? out : err;
            return "失败（退出码 " + code + "）" + (e.isEmpty() ? "" : "：" + e);
        }
    }

    /**
     * 执行一条命令。
     *
     * @return 结果；Shizuku 不可用或起进程失败时返回非零退出码与原因
     */
    public static Result exec(String... cmd) {
        if (cmd == null || cmd.length == 0) {
            return new Result(-1, "", "没有命令要执行");
        }
        try {
            if (!Shizuku.pingBinder()) {
                return new Result(-1, "", "Shizuku 服务没在运行");
            }
            Process p = newProcess(cmd);
            if (p == null) {
                return new Result(-1, "", "Shizuku 没能起进程");
            }
            // 命令不需要 stdin，关掉以免子进程等输入卡住
            try {
                OutputStream os = p.getOutputStream();
                if (os != null) {
                    os.close();
                }
            } catch (Throwable ignored) {
            }

            final Process proc = p;
            final AtomicInteger code = new AtomicInteger(-1);
            Thread waiter = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        code.set(proc.waitFor());
                    } catch (Throwable ignored) {
                    }
                }
            }, "shizuku-wait");
            waiter.setDaemon(true);
            waiter.start();

            String out = readAll(p.getInputStream());
            String err = readAll(p.getErrorStream());

            waiter.join(TIMEOUT_MS);
            if (waiter.isAlive()) {
                // 超时：杀掉它，别留一个挂着的进程
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
                return new Result(-1, out, "命令超时");
            }
            return new Result(code.get(), out, err);
        } catch (Throwable t) {
            return new Result(-1, "", "执行出错：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 起一个远程进程。
     *
     * <p><b>为什么要用反射</b>：Shizuku 的 {@code newProcess} 是 private 的
     * —— 它只打算给自己的 {@code ShizukuRemoteProcess} 用。我们只需要「跑一条
     * 命令看看退出码」，为这点事去复刻整套 binder 协议不值得，所以直接反射调用它，
     * 失败就返回 null 由上层给出明确原因。
     *
     * <p>方法签名（从 api 包的字节码确认）：
     * {@code newProcess(String[], String[], String) -> ShizukuRemoteProcess}，
     * 而 {@code ShizukuRemoteProcess} 实现了 {@code Process}。
     */
    private static Process newProcess(String[] cmd) {
        try {
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method cand : Shizuku.class.getDeclaredMethods()) {
                if (!"newProcess".equals(cand.getName())) {
                    continue;
                }
                Class<?>[] ps = cand.getParameterTypes();
                if (ps.length == 3 && ps[0] == String[].class
                        && ps[1] == String[].class && ps[2] == String.class) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            Object out = m.invoke(null, cmd, null, null);
            return (out instanceof Process) ? (Process) out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把「使用情况访问」直接授予本应用。
     *
     * <p>这是 Shizuku 在本应用里最有价值的一处：没有它，用户得自己去
     * 「设置 → 应用 → 特殊权限 → 使用情况访问」里翻半天。
     */
    public static boolean grantUsageAccess(android.content.Context c) {
        Result r = exec("appops", "set", c.getPackageName(), "GET_USAGE_STATS", "allow");
        if (!r.ok()) {
            return false;
        }
        // 命令成功不等于状态已生效，用我们自己的判定再确认一次 —— 这才是准的
        return Perm.usageAccess(c);
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 64 * 1024) {
                    break;
                }
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
