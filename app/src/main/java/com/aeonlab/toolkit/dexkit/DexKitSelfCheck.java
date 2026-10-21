package com.aeonlab.toolkit.dexkit;

import android.content.Context;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

/**
 * 一次性 DeKit 自检：把关键定位能力的结果写入宿主数据目录，
 * 用于排查「类定位全部为空」这类只能在真机上观察的问题。
 */
public final class DexKitSelfCheck {

    private static final String FILE_NAME = "diag_dexkit.txt";

    private DexKitSelfCheck() {
    }

    public static void dump(Context hostContext,
                            DexKitBridge dexKit,
                            ClassLoader hostClassLoader,
                            String apkPath) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("=== DexKit 自检 ").append(new java.util.Date()).append(" ===\n");
            File apk = apkPath == null ? null : new File(apkPath);
            sb.append("apkPath=").append(apkPath).append('\n');
            sb.append("apkExists=").append(apk != null && apk.exists()).append('\n');
            sb.append("apkLength=").append(apk != null && apk.exists() ? apk.length() : -1L).append('\n');
            sb.append("apkCanRead=").append(apk != null && apk.canRead()).append('\n');
            sb.append("hostContext=").append(hostContext == null ? "null" : hostContext.getClass().getName()).append('\n');
            sb.append("packageName=").append(hostContext == null ? "null" : hostContext.getPackageName()).append('\n');
            sb.append("dataDir=").append(hostContext == null ? "null" : String.valueOf(hostContext.getDataDir())).append('\n');
            sb.append("hostClassLoader=").append(hostClassLoader == null ? "null" : hostClassLoader.getClass().getName()).append('\n');
            sb.append("hostClassLoaderString=").append(String.valueOf(hostClassLoader)).append('\n');
            sb.append("dexKit=").append(String.valueOf(dexKit)).append('\n');
            try {
                sb.append("runtimeCacheKey=").append(DexMethodCache.INSTANCE.runtimeKey(hostContext, hostClassLoader)).append('\n');
            } catch (Throwable e) {
                sb.append("runtimeCacheKey=EX ").append(e).append('\n');
            }

            // 类加载能力：宿主 ClassLoader 能不能加载微信类
            try {
                Class<?> loaded = Class.forName("com.tencent.mm.plugin.messenger.foundation.u0", false, hostClassLoader);
                sb.append("loadClass_u0=").append(loaded != null).append('\n');
            } catch (Throwable e) {
                sb.append("loadClass_u0=EX ").append(e).append('\n');
            }

            // 查询1：字符串定位
            dumpQuery(sb, "q_strings_receivewxhb", dexKit, "cgi-bin/mmpay-bin/receivewxhb");
            // 查询2：另一处已知字符串
            dumpQuery(sb, "q_strings_aeskey", dexKit, "aeskey must be 32 bytes");
            // 查询3：另一个功能锚点
            dumpQuery(sb, "q_strings_mars", dexKit, "must set marscdnBizType,apptype");
        } catch (Throwable e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            sb.append("FATAL=").append(sw).append('\n');
        }
        write(hostContext, sb.toString());
    }

    private static void dumpQuery(StringBuilder sb, String label, DexKitBridge dexKit, String anchor) {
        long start = System.currentTimeMillis();
        try {
            FindClass query = new FindClass();
            ClassMatcher matcher = new ClassMatcher();
            matcher.usingStrings(Arrays.asList(anchor));
            query.matcher(matcher);
            List<ClassData> classes = dexKit.findClass(query);
            sb.append(label).append('=').append(classes == null ? -1 : classes.size())
                    .append(" cost=").append(System.currentTimeMillis() - start).append("ms\n");
            if (classes != null) {
                int limit = Math.min(5, classes.size());
                for (int i = 0; i < limit; i++) {
                    sb.append("  ").append(label).append('[').append(i).append("]=")
                            .append(classes.get(i).getName()).append('\n');
                }
            }
        } catch (Throwable e) {
            sb.append(label).append("=EX ").append(e)
                    .append(" cost=").append(System.currentTimeMillis() - start).append("ms\n");
        }
    }

    private static void write(Context hostContext, String content) {
        try {
            File dir = new File(hostContext.getDataDir(), "Hchat");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File out = new File(dir, FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(out, false)) {
                fos.write(content.getBytes("UTF-8"));
                fos.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            out.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }
}
