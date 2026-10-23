package com.wkq.iptc.feature.press.metadata;

/** 独立 JVM 子进程，测试真实的退出、阻塞和大输出。 */
public class MetadataProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("hang")) Thread.sleep(60000);
        else if (args[0].equals("warning")) System.err.print("private metadata warning");
        else if (args[0].equals("readWarning")) { System.err.print("Warning: private optional metadata missing"); System.out.print("ok"); }
        else if (args[0].equals("failedReadWarning")) { System.err.print("Warning: private failure"); System.exit(1); }
        else if (args[0].equals("overflow")) System.out.write(new byte[65536]);
        else System.out.print("ok");
    }
}
