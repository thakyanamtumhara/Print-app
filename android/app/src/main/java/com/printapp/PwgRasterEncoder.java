package com.printapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * PWG raster (PWG 5102.4) encoder, sgray_8. This is the format the Brother
 * HL-B2080DW actually accepts (document-format-supported = octet-stream,
 * image/urf, image/pwg-raster — it has NO PDF/JPEG interpreter, which is why
 * every PDF/JPEG send produced blank pages). Validated against the live
 * printer from the laptop reference implementation on 2026-07-02.
 */
public final class PwgRasterEncoder {

    private static final byte[] SYNC = {'R', 'a', 'S', '2'};
    private static final int HDR = 1796;

    private PwgRasterEncoder() {}

    public static void writeSyncWord(OutputStream out) throws IOException {
        out.write(SYNC);
    }

    /**
     * Writes one page: 1796-byte header + RLE-compressed sgray_8 scanlines.
     * gray: width*height bytes, 0=black 255=white, row-major.
     * pageIndex is 0-based; when duplex, odd (back) pages must already be
     * rotated 180° by the caller (pwg-raster-document-sheet-back=rotated).
     */
    public static void writePage(OutputStream out, byte[] gray, int width, int height,
                                 int dpi, boolean duplex, int totalPages) throws IOException {
        byte[] h = new byte[HDR];
        putCString(h, 0, "PwgRaster");
        putU32(h, 272, duplex ? 1 : 0);
        putU32(h, 276, dpi);
        putU32(h, 280, dpi);
        putU32(h, 340, 1);                                  // NumCopies (copies go via IPP)
        putU32(h, 352, Math.round(width * 72f / dpi));      // PageSize points
        putU32(h, 356, Math.round(height * 72f / dpi));
        putU32(h, 368, 0);                                  // Tumble (long-edge duplex)
        putU32(h, 372, width);
        putU32(h, 376, height);
        putU32(h, 384, 8);                                  // BitsPerColor
        putU32(h, 388, 8);                                  // BitsPerPixel
        putU32(h, 392, width);                              // BytesPerLine
        putU32(h, 396, 0);                                  // ColorOrder chunky
        putU32(h, 400, 18);                                 // ColorSpace sgray
        putU32(h, 420, 1);                                  // NumColors
        putU32(h, 452, totalPages);
        putU32(h, 456, 1);                                  // CrossFeedTransform
        putU32(h, 460, 1);                                  // FeedTransform
        putCString(h, 1732, "iso_a4_210x297mm");
        out.write(h);
        compress(out, gray, width, height);
    }

    private static void compress(OutputStream rawOut, byte[] gray, int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        int y = 0;
        while (y < height) {
            int rep = 0;
            while (rep < 255 && y + rep + 1 < height
                    && rowsEqual(gray, width, y, y + rep + 1)) rep++;
            out.write(rep);
            encodeRow(out, gray, y * width, width);
            y += rep + 1;
            if (out.size() > 512 * 1024) { out.writeTo(rawOut); out.reset(); }
        }
        out.writeTo(rawOut);
    }

    private static boolean rowsEqual(byte[] g, int width, int y1, int y2) {
        int a = y1 * width, b = y2 * width;
        for (int i = 0; i < width; i++) if (g[a + i] != g[b + i]) return false;
        return true;
    }

    private static void encodeRow(ByteArrayOutputStream out, byte[] g, int off, int width) {
        int x = 0;
        while (x < width) {
            int run = 1;
            while (run < 128 && x + run < width && g[off + x + run] == g[off + x]) run++;
            if (run >= 2) {
                out.write(run - 1);
                out.write(g[off + x]);
                x += run;
            } else {
                int lit = 1;
                while (lit < 128 && x + lit < width
                        && !(x + lit + 1 < width && g[off + x + lit] == g[off + x + lit + 1])) lit++;
                out.write(257 - lit);
                out.write(g, off + x, lit);
                x += lit;
            }
        }
    }

    public static void rotate180(byte[] gray) {
        for (int i = 0, j = gray.length - 1; i < j; i++, j--) {
            byte t = gray[i]; gray[i] = gray[j]; gray[j] = t;
        }
    }

    private static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static void putCString(byte[] b, int off, String s) {
        for (int i = 0; i < s.length() && i < 63; i++) b[off + i] = (byte) s.charAt(i);
    }
}
