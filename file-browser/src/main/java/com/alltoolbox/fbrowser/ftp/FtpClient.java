package com.alltoolbox.fbrowser.ftp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 FTP 客户端（主动 + 被动模式，PASV 数据连接），无第三方依赖。
 * 支持：连接/登录、列表(LIST)、切换目录、下载、上传、关闭。
 */
public final class FtpClient {

    private Socket control;
    private BufferedReader reader;
    private OutputStream controlOut;
    private String host;
    private boolean loggedIn = false;

    public static final int DEFAULT_PORT = 21;

    public FtpClient() {
    }

    /** 建立控制连接。 */
    public void connect(String host, int port, int timeoutMs) throws IOException {
        this.host = host;
        control = new Socket();
        control.connect(new InetSocketAddress(host, port), timeoutMs);
        control.setSoTimeout(timeoutMs);
        reader = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.ISO_8859_1));
        controlOut = control.getOutputStream();
        FtpReply reply = readReply();
        if (reply.code / 100 != 2) {
            throw new IOException("服务器拒绝连接: " + reply.line);
        }
    }

    public boolean isConnected() {
        return control != null && control.isConnected() && !control.isClosed();
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public void login(String user, String pass) throws IOException {
        send("USER " + (user == null || user.isEmpty() ? "anonymous" : user));
        FtpReply r = readReply();
        if (r.code == 331 || r.code / 100 == 2) {
            send("PASS " + (pass == null ? "anonymous@example.com" : pass));
            FtpReply r2 = readReply();
            if (r2.code / 100 != 2) throw new IOException("登录失败: " + r2.line);
        } else if (r.code / 100 != 2) {
            throw new IOException("用户名阶段失败: " + r.line);
        }
        loggedIn = true;
    }

    public void sendTypeBinary() throws IOException {
        send("TYPE I");
        readReply();
    }

    public String pwd() throws IOException {
        send("PWD");
        FtpReply r = readReply();
        int q1 = r.line.indexOf('"');
        int q2 = q1 >= 0 ? r.line.indexOf('"', q1 + 1) : -1;
        return (q1 >= 0 && q2 > q1) ? r.line.substring(q1 + 1, q2) : r.line;
    }

    public void cwd(String path) throws IOException {
        send("CWD " + quote(path));
        FtpReply r = readReply();
        if (r.code / 100 != 2) throw new IOException("无法进入目录: " + r.line);
    }

    /** 返回当前目录下的条目。dir 前缀为 true 表示目录。 */
    public List<FtpEntry> list() throws IOException {
        List<String> lines = dataCommand("LIST");
        List<FtpEntry> out = new ArrayList<>();
        for (String line : lines) {
            FtpEntry e = parseListLine(line);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** 发起需要数据连接的命令（如 LIST），返回数据连接收到的所有行。 */
    private List<String> dataCommand(String cmd) throws IOException {
        Socket data = openPassive();
        send(cmd);
        FtpReply r = readReply();
        List<String> result = new ArrayList<>();
        if (r.code == 150 || r.code / 100 == 2) {
            try (InputStream in = data.getInputStream();
                 BufferedReader dr = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = dr.readLine()) != null) result.add(line);
            }
            data.close();
            FtpReply end = readReply();
            if (end.code / 100 != 2) throw new IOException("列表结束异常: " + end.line);
        } else {
            data.close();
            throw new IOException("命令失败: " + r.line);
        }
        return result;
    }

    /** 下载远程文件到本地输出流。 */
    public void download(String remotePath, OutputStream localOut) throws IOException {
        Socket data = openPassive();
        send("RETR " + quote(remotePath));
        FtpReply r = readReply();
        if (r.code != 150 && r.code != 125) {
            data.close();
            throw new IOException("下载失败: " + r.line);
        }
        try (InputStream in = data.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) localOut.write(buf, 0, n);
        }
        data.close();
        FtpReply end = readReply();
        if (end.code / 100 != 2) throw new IOException("传输未正常结束: " + end.line);
    }

    /** 上传本地流到远程文件。 */
    public void upload(String remotePath, InputStream localIn) throws IOException {
        Socket data = openPassive();
        send("STOR " + quote(remotePath));
        FtpReply r = readReply();
        if (r.code != 150 && r.code != 125) {
            data.close();
            throw new IOException("上传失败: " + r.line);
        }
        try (OutputStream out = data.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = localIn.read(buf)) != -1) out.write(buf, 0, n);
        }
        data.close();
        FtpReply end = readReply();
        if (end.code / 100 != 2) throw new IOException("上传未正常结束: " + end.line);
    }

    // ------------------------------------------------------------------
    // 底层控制命令
    // ------------------------------------------------------------------

    /** PASV 建立数据连接。 */
    private Socket openPassive() throws IOException {
        send("PASV");
        FtpReply r = readReply();
        if (r.code / 100 != 2) throw new IOException("PASV 失败: " + r.line);
        int start = r.line.lastIndexOf('(');
        int end = r.line.lastIndexOf(')');
        if (start < 0 || end <= start) throw new IOException("无法解析 PASV 响应");
        String[] nums = r.line.substring(start + 1, end).split(",");
        if (nums.length < 6) throw new IOException("PASV 响应格式错误");
        String dataHost = nums[0] + "." + nums[1] + "." + nums[2] + "." + nums[3];
        int port = Integer.parseInt(nums[4]) * 256 + Integer.parseInt(nums[5]);
        Socket data = new Socket();
        data.setSoTimeout(30000);
        data.connect(new InetSocketAddress(dataHost, port), 15000);
        return data;
    }

    private void send(String cmd) throws IOException {
        controlOut.write((cmd + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        controlOut.flush();
    }

    private FtpReply readReply() throws IOException {
        StringBuilder raw = new StringBuilder();
        int code = 0;
        while (true) {
            String line = reader.readLine();
            if (line == null) throw new IOException("FTP 连接已断开");
            if (line.length() >= 4 && line.charAt(3) == '-') {
                if (code == 0) code = parseCode(line);
                raw.append(line).append('\n');
                String term = line.substring(0, 3) + ' ';
                // 继续读直到遇到终止行
                String next;
                boolean done = false;
                while (!done) {
                    next = reader.readLine();
                    if (next == null) throw new IOException("FTP 连接已断开");
                    raw.append(next).append('\n');
                    done = next.length() >= 4 && (next.substring(0, 4).equals(line.substring(0, 3) + " "));
                }
                break;
            }
            if (code == 0) code = parseCode(line);
            raw.append(line).append('\n');
            break;
        }
        return new FtpReply(code, raw.toString().trim());
    }

    private int parseCode(String line) {
        try {
            return Integer.parseInt(line.substring(0, 3));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String quote(String s) {
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }

    /** 解析 LIST 输出行，返回条目；无法解析返回 null。 */
    private static FtpEntry parseListLine(String line) {
        if (line == null || line.isEmpty()) return null;
        boolean isDir;
        String name;
        long size = 0;
        // 先尝试类 Unix 长格式：权限 链接 用户 组 大小 时间 名称
        if (line.startsWith("-") || line.startsWith("d") || line.startsWith("l")
                || line.startsWith("b") || line.startsWith("c")) {
            isDir = line.charAt(0) == 'd';
            String[] parts = line.split("\\s+");
            if (parts.length >= 9) {
                try {
                    size = Long.parseLong(parts[4]);
                } catch (Exception ignore) {
                    size = 0;
                }
                int start = line.indexOf(parts[8]);
                name = line.substring(start);
                return new FtpEntry(name, isDir, size);
            }
            return null;
        }
        // 某些服务器返回裸文件名列表
        name = line.trim();
        return new FtpEntry(name, false, 0);
    }

    public void close() {
        if (control != null) {
            try { send("QUIT"); } catch (Exception ignore) {
            }
            try { control.close(); } catch (Exception ignore) {
            }
            control = null;
        }
        loggedIn = false;
    }

    public static final class FtpEntry {
        public final String name;
        public final boolean directory;
        public final long size;

        FtpEntry(String name, boolean directory, long size) {
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }

    private static final class FtpReply {
        final int code;
        final String line;

        FtpReply(int code, String line) {
            this.code = code;
            this.line = line;
        }
    }
}