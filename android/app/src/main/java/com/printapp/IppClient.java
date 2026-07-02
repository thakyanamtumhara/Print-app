package com.printapp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal IPP 2.0 client: Get-Printer-Attributes, Validate-Job, Print-Job,
 * Get-Job-Attributes. Talks plain HTTP POST application/ipp on port 631.
 */
public final class IppClient {

    public static final int OP_PRINT_JOB = 0x0002;
    public static final int OP_VALIDATE_JOB = 0x0004;
    public static final int OP_GET_JOB_ATTRS = 0x0009;
    public static final int OP_GET_PRINTER_ATTRS = 0x000B;

    private static final byte TAG_OP_ATTRS = 0x01;
    private static final byte TAG_JOB_ATTRS = 0x02;
    private static final byte TAG_END = 0x03;
    private static final byte TAG_INTEGER = 0x21;
    private static final byte TAG_ENUM = 0x23;
    private static final byte TAG_NAME = 0x42;
    private static final byte TAG_KEYWORD = 0x44;
    private static final byte TAG_URI = 0x45;
    private static final byte TAG_CHARSET = 0x47;
    private static final byte TAG_LANG = 0x48;
    private static final byte TAG_MIME = 0x49;

    /** job-state enum values (RFC 8011). */
    public static final int JOB_STATE_COMPLETED = 9;
    public static final int JOB_STATE_ABORTED = 8;
    public static final int JOB_STATE_CANCELED = 7;

    public static class Response {
        public int status = -1;
        public Map<String, List<String>> attrs = new HashMap<String, List<String>>();
        public Map<String, Integer> intAttrs = new HashMap<String, Integer>();

        public boolean ok() { return status >= 0 && status <= 0xFF; }
        public String first(String name) {
            List<String> v = attrs.get(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
        public int getInt(String name, int def) {
            Integer v = intAttrs.get(name);
            return v == null ? def : v.intValue();
        }
        public boolean hasValue(String name, String value) {
            List<String> v = attrs.get(name);
            return v != null && v.contains(value);
        }
    }

    private final String host;
    private final int port;
    private final String path;

    public IppClient(String host) { this(host, 631, "/ipp/print"); }

    public IppClient(String host, int port, String path) {
        this.host = host;
        this.port = port;
        this.path = path.startsWith("/") ? path : "/" + path;
    }

    private String printerUri() { return "ipp://" + host + path; }

    public Response getPrinterAttributes() throws IOException {
        byte[] req = header(OP_GET_PRINTER_ATTRS, null, null, null);
        return send(req, null, 0, 15000);
    }

    public Response validateJob(String docFormat) throws IOException {
        byte[] req = header(OP_VALIDATE_JOB, "validate", docFormat, null);
        return send(req, null, 0, 15000);
    }

    public Response printJob(File doc, String jobName, String docFormat,
                             boolean duplex, int copies) throws IOException {
        ByteArrayOutputStream jobAttrs = new ByteArrayOutputStream();
        attr(jobAttrs, TAG_KEYWORD, "media", "iso_a4_210x297mm");
        attr(jobAttrs, TAG_KEYWORD, "sides", duplex ? "two-sided-long-edge" : "one-sided");
        attr(jobAttrs, TAG_KEYWORD, "print-color-mode", "monochrome");
        if (copies > 1) intAttr(jobAttrs, "copies", copies);
        byte[] req = header(OP_PRINT_JOB, jobName, docFormat, jobAttrs.toByteArray());
        InputStream in = new FileInputStream(doc);
        try {
            return send(req, in, doc.length(), 180000);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    public Response getJobAttributes(int jobId) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(new byte[]{0x02, 0x00, 0x00, (byte) OP_GET_JOB_ATTRS, 0, 0, 0, 1}, 0, 8);
        b.write(TAG_OP_ATTRS);
        attr(b, TAG_CHARSET, "attributes-charset", "utf-8");
        attr(b, TAG_LANG, "attributes-natural-language", "en");
        attr(b, TAG_URI, "printer-uri", printerUri());
        intAttrTagged(b, TAG_INTEGER, "job-id", jobId);
        attr(b, TAG_NAME, "requesting-user-name", "printapp");
        b.write(TAG_END);
        return send(b.toByteArray(), null, 0, 15000);
    }

    /** Polls job-state until completed/aborted/canceled or timeout. Returns last state, -1 if unknown. */
    public int waitForCompletion(int jobId, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        int last = -1;
        while (System.currentTimeMillis() < end) {
            try {
                Response r = getJobAttributes(jobId);
                int st = r.getInt("job-state", -1);
                if (st > 0) last = st;
                if (st == JOB_STATE_COMPLETED || st == JOB_STATE_ABORTED || st == JOB_STATE_CANCELED) return st;
            } catch (IOException ignored) {}
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return last; }
        }
        return last;
    }

    private byte[] header(int op, String jobName, String docFormat, byte[] jobAttrGroup) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(new byte[]{0x02, 0x00, (byte) (op >> 8), (byte) op, 0, 0, 0, 1}, 0, 8);
        b.write(TAG_OP_ATTRS);
        attr(b, TAG_CHARSET, "attributes-charset", "utf-8");
        attr(b, TAG_LANG, "attributes-natural-language", "en");
        attr(b, TAG_URI, "printer-uri", printerUri());
        attr(b, TAG_NAME, "requesting-user-name", "printapp");
        if (jobName != null) attr(b, TAG_NAME, "job-name", jobName);
        if (docFormat != null) attr(b, TAG_MIME, "document-format", docFormat);
        if (jobAttrGroup != null && jobAttrGroup.length > 0) {
            b.write(TAG_JOB_ATTRS);
            b.write(jobAttrGroup);
        }
        b.write(TAG_END);
        return b.toByteArray();
    }

    private static void attr(ByteArrayOutputStream b, byte tag, String name, String value) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        b.write(tag);
        b.write(n.length >> 8); b.write(n.length); b.write(n, 0, n.length);
        b.write(v.length >> 8); b.write(v.length); b.write(v, 0, v.length);
    }

    private static void intAttr(ByteArrayOutputStream b, String name, int value) throws IOException {
        intAttrTagged(b, TAG_INTEGER, name, value);
    }

    private static void intAttrTagged(ByteArrayOutputStream b, byte tag, String name, int value) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        b.write(tag);
        b.write(n.length >> 8); b.write(n.length); b.write(n, 0, n.length);
        b.write(0); b.write(4);
        b.write(value >>> 24); b.write(value >>> 16); b.write(value >>> 8); b.write(value);
    }

    private Response send(byte[] ippHeader, InputStream doc, long docLen, int timeoutMs) throws IOException {
        URL url = new URL("http", host, port, path);
        final HttpURLConnection c = (HttpURLConnection) url.openConnection();
        // OutputStream.write is not covered by readTimeout and cannot be
        // interrupted — a printer dying mid-upload would wedge the caller
        // forever. The watchdog force-disconnects past the deadline.
        final java.util.Timer watchdog = new java.util.Timer("ipp-watchdog", true);
        watchdog.schedule(new java.util.TimerTask() {
            @Override public void run() {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }, (long) timeoutMs + 30000L);
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/ipp");
            long total = ippHeader.length + (doc != null ? docLen : 0);
            if (total <= Integer.MAX_VALUE) c.setFixedLengthStreamingMode((int) total);
            else c.setChunkedStreamingMode(64 * 1024);
            OutputStream out = c.getOutputStream();
            out.write(ippHeader);
            if (doc != null) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = doc.read(buf)) > 0) out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            int http = c.getResponseCode();
            if (http != 200) {
                InputStream err = c.getErrorStream();
                if (err != null) {
                    byte[] buf = new byte[4096];
                    while (err.read(buf) > 0) { /* drain so the socket can be reused/closed */ }
                    err.close();
                }
                throw new IOException("IPP HTTP " + http);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) resp.write(buf, 0, n);
            in.close();
            return parse(resp.toByteArray());
        } finally {
            watchdog.cancel();
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static Response parse(byte[] b) {
        Response r = new Response();
        if (b.length < 8) return r;
        r.status = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        int off = 8;
        String lastName = null;
        while (off < b.length) {
            int tag = b[off] & 0xFF;
            if (tag == TAG_END) break;
            if (tag <= 0x0F) { off += 1; continue; }
            off += 1;
            if (off + 2 > b.length) break;
            int nlen = ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
            off += 2;
            String name = nlen > 0 ? new String(b, off, nlen, StandardCharsets.UTF_8) : lastName;
            off += nlen;
            if (off + 2 > b.length) break;
            int vlen = ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
            off += 2;
            if (off + vlen > b.length) break;
            if (name != null) {
                if ((tag == TAG_INTEGER || tag == TAG_ENUM) && vlen == 4) {
                    int v = ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                            | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
                    if (!r.intAttrs.containsKey(name)) r.intAttrs.put(name, v);
                } else {
                    String v = new String(b, off, vlen, StandardCharsets.UTF_8);
                    List<String> list = r.attrs.get(name);
                    if (list == null) { list = new ArrayList<String>(); r.attrs.put(name, list); }
                    list.add(v);
                }
            }
            off += vlen;
            lastName = name;
        }
        return r;
    }
}
