package com.alltoolbox.transfer;

import android.content.ContentResolver;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简 HTTP 文件服务器，通过 {@link DocumentFile} 树遍历并读取文件，
 * 天然兼容 Android 分区存储（SAF）。
 */
public class HttpServer {

    public interface Listener {
        void onLog(String line);
    }

    private static final int BUFFER = 32 * 1024;

    private final DocumentFile root;
    private final int port;
    private final ContentResolver resolver;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public HttpServer(DocumentFile root, int port, ContentResolver resolver, Listener listener) {
        this.root = root;
        this.port = port;
        this.resolver = resolver;
        this.listener = listener;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 启动服务器（绑定失败抛异常），随后异步接收连接。 */
    public synchronized boolean start() throws IOException {
        if (running.get()) return true;
        serverSocket = new ServerSocket(port, 10, getLocalAddress());
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "http-server");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log("服务器已启动：http://" + serverSocket.getInetAddress().getHostAddress()
                + ":" + serverSocket.getLocalPort() + "/  (根目录：" + root.getName() + ")");
        return true;
    }

    public synchronized void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignore) {
        }
        serverSocket = null;
        log("服务器已停止");
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running.get()) log("接受连接异常：" + e.getMessage());
                break;
            }
            new Thread(() -> handle(socket), "http-handler").start();
        }
    }

    private void handle(Socket socket) {
        try (socket; java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(socket.getInputStream()))) {
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendText(socket, 400, "Bad Request");
                return;
            }
            String line;
            do {
                line = in.readLine();
            } while (line != null && !line.isEmpty());

            String method = parts[0];
            String rawPath = URLDecoder.decode(parts[1], String.valueOf(StandardCharsets.UTF_8));
            if (!"GET".equalsIgnoreCase(method)) {
                sendText(socket, 405, "仅支持 GET");
                return;
            }

            DocumentFile node = resolve(rawPath);
            if (node == null) {
                sendText(socket, 403, "Forbidden");
                return;
            }
            if (!node.exists()) {
                sendText(socket, 404, "Not Found");
                return;
            }
            if (node.isDirectory()) {
                serveDirectory(socket, node, rawPath);
            } else {
                serveFile(socket, node);
            }
        } catch (Exception e) {
            log("请求处理异常：" + e.getMessage());
        }
    }

    private DocumentFile resolve(String rawPath) {
        String p = rawPath;
        if (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return root;
        String[] segs = p.split("/");
        DocumentFile cur = root;
        for (String seg : segs) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            DocumentFile child = cur.findFile(seg);
            if (child == null) return newNotFound();
            cur = child;
        }
        return cur;
    }

    /** 无法解析时返回一个不存在的哨兵。 */
    private DocumentFile newNotFound() {
        return DocumentFile.fromFile(new java.io.File(root.getUri().getPath(), "__missing__"));
    }

    private void serveDirectory(Socket socket, DocumentFile dir, String requestPath) throws IOException {
        String prefix = requestPath.endsWith("/") ? requestPath : requestPath + "/";
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
                .append(escape(dir.getName())).append("</title></head><body><h1>")
                .append(escape(dir.getName())).append("</h1><ul>");
        html.append("<li><a href=\"../\">..</a></li>");
        DocumentFile[] children = dir.listFiles();
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (DocumentFile f : children) {
            String name = f.getName() == null ? "?" : f.getName();
            String suffix = f.isDirectory() ? "/" : "";
            html.append("<li><a href=\"").append(prefix).append(escapeURL(name)).append(suffix)
                    .append("\">").append(escape(name)).append(suffix).append("</a></li>");
        }
        html.append("</ul></body></html>");
        byte[] body = html.toString().getBytes(StandardCharsets.UTF_8);
        sendHeader(socket, 200, "text/html; charset=utf-8", body.length);
        flush(socket, body);
    }

    private void serveFile(Socket socket, DocumentFile file) throws IOException {
        InputStream in = resolver.openInputStream(file.getUri());
        if (in == null) {
            sendText(socket, 404, "Not Found");
            return;
        }
        long length = file.length();
        sendHeader(socket, 200, contentType(file.getName()), length);
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), BUFFER);
        byte[] buf = new byte[BUFFER];
        int n;
        try (in) {
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        out.flush();
    }

    private void sendText(Socket socket, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        sendHeader(socket, code, "text/plain; charset=utf-8", body.length);
        flush(socket, body);
    }

    private void sendHeader(Socket socket, int code, String contentType, long contentLength) throws IOException {
        String reason = code == 200 ? "OK" : code == 404 ? "Not Found"
                : code == 403 ? "Forbidden" : code == 405 ? "Method Not Allowed" : "Error";
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(contentLength).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        socket.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private void flush(Socket socket, byte[] body) throws IOException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), BUFFER);
        out.write(body);
        out.flush();
    }

    private String contentType(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    // ---------------- 工具 ----------------

    public static InetAddress getLocalAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr.isSiteLocalAddress() && !addr.isLoopbackAddress()
                            && addr.getHostAddress().indexOf(':') < 0) {
                        return addr;
                    }
                }
            }
        } catch (Exception ignore) {
        }
        try {
            return InetAddress.getLocalHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeURL(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void log(String line) {
        if (listener != null) listener.onLog(line);
    }
}