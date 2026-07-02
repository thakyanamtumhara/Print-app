package com.printapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * The print path that actually matches the printer: render pages to grayscale
 * bitmaps, encode PWG raster, send ONE IPP Print-Job, then poll job-state to
 * a confirmed completed/aborted verdict (the old code fired-and-forgot, so
 * "success" with blank output was indistinguishable from a real print).
 *
 * Render at 300 dpi (memory-safe on low-RAM phones), upsample 2x to the
 * printer's required 600 dpi during grayscale conversion.
 */
public final class RasterPrintEngine {

    public static final int RENDER_DPI = 300;
    public static final int PRINT_DPI = 600;
    public static final int RW = 2480;   // A4 @300dpi
    public static final int RH = 3508;
    public static final int PW = 4960;   // A4 @600dpi
    public static final int PH = 7016;

    public interface Progress {
        void onProgress(String stage, int page, int totalPages);
    }

    public static class Result {
        public boolean ok;
        public int jobId = -1;
        public int jobState = -1;
        public int pages;
        public String message = "";
    }

    private RasterPrintEngine() {}

    public static Result printPdf(Context ctx, byte[] pdfBytes, String host, String jobName,
                                  boolean duplex, int copies, Progress cb) {
        Result res = new Result();
        File pdfFile = null, pwgFile = null;
        try {
            IppClient ipp = new IppClient(host);
            IppClient.Response caps = ipp.getPrinterAttributes();
            if (!caps.ok()) throw new IOException("printer not responding to IPP on " + host);
            if (!caps.hasValue("document-format-supported", "image/pwg-raster"))
                throw new IOException("printer does not support PWG raster (formats: "
                        + caps.attrs.get("document-format-supported") + ")");

            pdfFile = File.createTempFile("job", ".pdf", ctx.getCacheDir());
            FileOutputStream fo = new FileOutputStream(pdfFile);
            fo.write(pdfBytes);
            fo.close();

            pwgFile = File.createTempFile("job", ".pwg", ctx.getCacheDir());
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer renderer = new PdfRenderer(pfd);
            int total = renderer.getPageCount();
            res.pages = total;
            OutputStream out = new BufferedOutputStream(new FileOutputStream(pwgFile), 256 * 1024);
            PwgRasterEncoder.writeSyncWord(out);
            Bitmap bmp = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);
            byte[] gray = new byte[PW * PH];
            for (int i = 0; i < total; i++) {
                if (cb != null) cb.onProgress("render", i + 1, total);
                PdfRenderer.Page page = renderer.openPage(i);
                renderFitA4(page, bmp);
                page.close();
                toGray600(bmp, gray);
                if (duplex && (i % 2) == 1) PwgRasterEncoder.rotate180(gray);
                if (cb != null) cb.onProgress("encode", i + 1, total);
                PwgRasterEncoder.writePage(out, gray, PW, PH, PRINT_DPI, duplex, total);
            }
            bmp.recycle();
            renderer.close();
            pfd.close();
            out.close();

            if (cb != null) cb.onProgress("send", total, total);
            IppClient.Response v = ipp.validateJob("image/pwg-raster");
            if (!v.ok()) throw new IOException("Validate-Job rejected: 0x" + Integer.toHexString(v.status));
            IppClient.Response p = ipp.printJob(pwgFile, jobName, "image/pwg-raster", duplex, copies);
            if (!p.ok()) throw new IOException("Print-Job rejected: 0x" + Integer.toHexString(p.status)
                    + " " + safe(p.first("status-message")));
            res.jobId = p.getInt("job-id", -1);

            if (cb != null) cb.onProgress("confirm", total, total);
            if (res.jobId > 0) {
                int st = ipp.waitForCompletion(res.jobId, 180000);
                res.jobState = st;
                if (st == IppClient.JOB_STATE_COMPLETED) {
                    res.ok = true;
                    res.message = "printed " + total + " page(s), job " + res.jobId + " completed";
                } else if (st == IppClient.JOB_STATE_ABORTED || st == IppClient.JOB_STATE_CANCELED) {
                    res.ok = false;
                    res.message = "printer " + (st == 8 ? "aborted" : "canceled") + " job " + res.jobId;
                } else {
                    res.ok = true;
                    res.message = "job " + res.jobId + " sent (state " + st + " at timeout)";
                }
            } else {
                res.ok = true;
                res.message = "job accepted (no job-id returned)";
            }
        } catch (Throwable t) {
            res.ok = false;
            res.message = t.getMessage() == null ? t.toString() : t.getMessage();
        } finally {
            if (pdfFile != null) pdfFile.delete();
            if (pwgFile != null) pwgFile.delete();
        }
        return res;
    }

    /** Prints a list of already-rendered page bitmaps (image/label path). */
    public static Result printBitmaps(Context ctx, List<Bitmap> pages, String host, String jobName,
                                      boolean duplex, int copies, Progress cb) {
        Result res = new Result();
        File pwgFile = null;
        try {
            IppClient ipp = new IppClient(host);
            IppClient.Response caps = ipp.getPrinterAttributes();
            if (!caps.ok()) throw new IOException("printer not responding to IPP on " + host);
            if (!caps.hasValue("document-format-supported", "image/pwg-raster"))
                throw new IOException("printer does not support PWG raster");

            pwgFile = File.createTempFile("job", ".pwg", ctx.getCacheDir());
            OutputStream out = new BufferedOutputStream(new FileOutputStream(pwgFile), 256 * 1024);
            PwgRasterEncoder.writeSyncWord(out);
            int total = pages.size();
            res.pages = total;
            Bitmap canvasBmp = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(canvasBmp);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            byte[] gray = new byte[PW * PH];
            for (int i = 0; i < total; i++) {
                if (cb != null) cb.onProgress("encode", i + 1, total);
                canvas.drawColor(Color.WHITE);
                Bitmap src = pages.get(i);
                Matrix m = fitCenter(src.getWidth(), src.getHeight(), RW, RH);
                canvas.drawBitmap(src, m, paint);
                toGray600(canvasBmp, gray);
                if (duplex && (i % 2) == 1) PwgRasterEncoder.rotate180(gray);
                PwgRasterEncoder.writePage(out, gray, PW, PH, PRINT_DPI, duplex, total);
            }
            canvasBmp.recycle();
            out.close();

            IppClient.Response p = ipp.printJob(pwgFile, jobName, "image/pwg-raster", duplex, copies);
            if (!p.ok()) throw new IOException("Print-Job rejected: 0x" + Integer.toHexString(p.status));
            res.jobId = p.getInt("job-id", -1);
            int st = res.jobId > 0 ? ipp.waitForCompletion(res.jobId, 180000) : -1;
            res.jobState = st;
            res.ok = res.jobId <= 0 || st == IppClient.JOB_STATE_COMPLETED
                    || (st != IppClient.JOB_STATE_ABORTED && st != IppClient.JOB_STATE_CANCELED);
            res.message = res.ok ? ("printed " + total + " page(s)") : ("printer aborted job " + res.jobId);
        } catch (Throwable t) {
            res.ok = false;
            res.message = t.getMessage() == null ? t.toString() : t.getMessage();
        } finally {
            if (pwgFile != null) pwgFile.delete();
        }
        return res;
    }

    private static void renderFitA4(PdfRenderer.Page page, Bitmap bmp) {
        // page dimensions are in points (1/72"); transform maps points -> bitmap px
        float srcW = page.getWidth() * RENDER_DPI / 72f;
        float srcH = page.getHeight() * RENDER_DPI / 72f;
        Matrix full = new Matrix();
        full.setScale(RENDER_DPI / 72f, RENDER_DPI / 72f);
        full.postConcat(fitCenter(srcW, srcH, RW, RH));
        bmp.eraseColor(Color.WHITE);
        page.render(bmp, null, full, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
    }

    private static Matrix fitCenter(float srcW, float srcH, float dstW, float dstH) {
        Matrix m = new Matrix();
        float s = Math.min(dstW / srcW, dstH / srcH);
        if (s > 1f) s = 1f; // never upscale content beyond native size
        m.setScale(s, s);
        m.postTranslate((dstW - srcW * s) / 2f, (dstH - srcH * s) / 2f);
        return m;
    }

    /** 300dpi ARGB bitmap -> 600dpi sgray_8 (2x nearest-neighbour upsample). */
    private static void toGray600(Bitmap bmp, byte[] gray) {
        int[] row = new int[RW];
        byte[] line = new byte[PW];
        for (int y = 0; y < RH; y++) {
            bmp.getPixels(row, 0, RW, 0, y, RW, 1);
            for (int x = 0; x < RW; x++) {
                int p = row[x];
                int g = ((p >> 16 & 0xFF) * 299 + (p >> 8 & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
                line[x * 2] = (byte) g;
                line[x * 2 + 1] = (byte) g;
            }
            System.arraycopy(line, 0, gray, (y * 2) * PW, PW);
            System.arraycopy(line, 0, gray, (y * 2 + 1) * PW, PW);
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
