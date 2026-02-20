// ── Register Service Worker ──
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

// ── Global error handler (visible in logcat via console.error) ──
window.onerror = function(msg, url, line, col, err) {
  console.error('JS Error: ' + msg + ' at ' + url + ':' + line + ':' + col);
};

// ── Fix app height for Android WebView ──
// CSS height units can fail in WebView. Set pixels + overflow:hidden together
// so content is never clipped before height is established.
(function() {
  function fixHeight() {
    var el = document.querySelector('.app');
    if (el) {
      var h = window.innerHeight || 0;
      if (h > 0) { el.style.height = h + 'px'; el.style.overflow = 'hidden'; }
    }
  }
  fixHeight();
  window.addEventListener('resize', fixHeight);
  window.addEventListener('load', fixHeight);
})();

document.addEventListener('DOMContentLoaded', () => {
  var printBtn = document.getElementById('printBtn');
  var layoutSelect = document.getElementById('layoutSelect');
  var labelContainer = document.getElementById('labelContainer');
  var previewWrapper = document.getElementById('previewWrapper');
  var previewArea = document.getElementById('previewArea');
  var whiteoutCanvas = document.getElementById('whiteoutCanvas');
  var undoBtn = document.getElementById('undoBtn');
  var eraserToggle = document.getElementById('eraserToggle');
  var printArea = document.getElementById('printArea');
  var printerDot = document.getElementById('printerDot');
  var printerText = document.getElementById('printerText');
  var openFileBtn = document.getElementById('openFileBtn');
  var downloadBtn = document.getElementById('downloadBtn');
  var shareBtn = document.getElementById('shareBtn');
  var fileInput = document.getElementById('fileInput');

  var pageImages = [];     // data-URL per PDF/image page (for printing)
  var pageOrientations = []; // 'landscape' | 'portrait' per page
  var contentType = 'label'; // 'label' | 'pdf' | 'image'
  var selectedPages = [];  // boolean array — true = page selected for printing
  var eraserEnabled = false;
  var eraserEverUsed = false; // tracks if eraser was used on current file

  // ── Printer status ──
  // Android app: green/red blink based on actual WiFi detection
  // Web browser: neutral gray (browser print dialog picks printer)
  function updatePrinterStatus(status) {
    // status: 'connected' | 'disconnected' | 'neutral'
    printerDot.classList.remove('connected', 'neutral');
    if (status === 'connected') {
      printerDot.classList.add('connected');
      printerText.textContent = 'Brother HL-B2080DW';
    } else if (status === 'disconnected') {
      printerText.textContent = 'Printer not found';
    } else {
      printerDot.classList.add('neutral');
      printerText.textContent = 'Prints via system dialog';
    }
  }

  function checkPrinter() {
    if (window.AndroidBridge && typeof window.AndroidBridge.isPrinterConnected === 'function') {
      updatePrinterStatus(window.AndroidBridge.isPrinterConnected() ? 'connected' : 'disconnected');
    } else {
      updatePrinterStatus('neutral');
    }
  }

  window.updatePrinterConnected = function(connected) {
    updatePrinterStatus(connected ? 'connected' : 'disconnected');
  };

  // Poll every 5s (only useful when Android bridge is present)
  setInterval(checkPrinter, 5000);
  checkPrinter();

  // Tap printer bar to retry discovery when printer not found
  var printerBar = document.querySelector('.printer-bar');
  if (printerBar) {
    printerBar.style.cursor = 'pointer';
    printerBar.addEventListener('click', function() {
      if (window.AndroidBridge && typeof window.AndroidBridge.rediscoverPrinter === 'function') {
        console.log('[PRINT-DEBUG] Tapped printer bar → rediscoverPrinter()');
        window.AndroidBridge.rediscoverPrinter();
        printerText.textContent = 'Searching...';
      }
    });
  }

  // ── Layout change → update in-app preview ──
  layoutSelect.addEventListener('change', function() {
    bakeStrokes(); // preserve eraser marks before rebuilding layout
    updatePreviewLayout();
  });

  function initSelectedPages() {
    selectedPages = [];
    for (var i = 0; i < pageImages.length; i++) selectedPages.push(true);
  }

  function togglePage(idx) {
    selectedPages[idx] = !selectedPages[idx];
    // Ensure at least one page is selected
    var anySelected = false;
    for (var i = 0; i < selectedPages.length; i++) {
      if (selectedPages[i]) { anySelected = true; break; }
    }
    if (!anySelected) { selectedPages[idx] = true; }
    bakeStrokes(); // preserve eraser marks before rebuilding layout
    updatePreviewLayout();
  }

  function updatePreviewLayout() {
    if (contentType === 'label' || pageImages.length === 0) return;
    var layout = parseInt(layoutSelect.value, 10);
    labelContainer.innerHTML = '';

    // For images: duplicate the single image to fill the layout
    var allPages = (contentType === 'image') ? Array(layout).fill(pageImages[0]) : pageImages;

    // Build selected-only list for N-up preview
    var selPages = [];
    var selOrients = [];
    for (var i = 0; i < allPages.length; i++) {
      if (selectedPages[i] !== false) {
        selPages.push(allPages[i]);
        selOrients.push(pageOrientations[i] || 'portrait');
      }
    }

    var container = document.createElement('div');
    container.className = 'preview-sheets';

    // ── Page selector strip (thumbnails with checkmarks) ──
    if (allPages.length > 1 || contentType === 'pdf') {
      var strip = document.createElement('div');
      strip.className = 'page-selector-strip';
      for (var i = 0; i < allPages.length; i++) {
        var thumb = document.createElement('div');
        thumb.className = 'page-thumb' + (selectedPages[i] !== false ? ' selected' : '') + (pageOrientations[i] === 'landscape' ? ' page-thumb-landscape' : '');
        thumb.setAttribute('data-page', i);
        var thumbImg = document.createElement('img');
        thumbImg.src = allPages[i];
        thumb.appendChild(thumbImg);
        var check = document.createElement('div');
        check.className = 'page-check';
        check.innerHTML = selectedPages[i] !== false ? '&#10003;' : '';
        thumb.appendChild(check);
        var pageNum = document.createElement('div');
        pageNum.className = 'page-thumb-label';
        pageNum.textContent = (i + 1);
        thumb.appendChild(pageNum);
        (function(idx) {
          thumb.addEventListener('click', function() { togglePage(idx); });
        })(i);
        strip.appendChild(thumb);
      }
      container.appendChild(strip);
    }

    // ── A4 sheet previews ──
    var totalSheets = Math.ceil(selPages.length / layout);
    for (var i = 0; i < selPages.length; i += layout) {
      var sheet = document.createElement('div');
      sheet.className = 'preview-sheet preview-layout-' + layout;
      // Show landscape pages in landscape orientation in preview
      if (layout === 1 && selOrients[i] === 'landscape') {
        sheet.classList.add('preview-sheet-landscape');
      }

      for (var j = 0; j < layout && (i + j) < selPages.length; j++) {
        var tile = document.createElement('div');
        tile.className = 'preview-tile';
        var img = document.createElement('img');
        img.src = selPages[i + j];
        tile.appendChild(img);
        sheet.appendChild(tile);
      }
      container.appendChild(sheet);

      var lbl = document.createElement('div');
      lbl.className = 'preview-sheet-label';
      lbl.textContent = 'Sheet ' + (Math.floor(i / layout) + 1) + ' of ' + totalSheets;
      container.appendChild(lbl);
    }

    if (selPages.length === 0) {
      var msg = document.createElement('div');
      msg.style.cssText = 'text-align:center;color:#888;padding:20px;font-size:14px';
      msg.textContent = 'No pages selected';
      container.appendChild(msg);
    }

    labelContainer.appendChild(container);
    resizeCanvas();
  }

  // ══════════════════════════════════════════
  // ── PINCH ZOOM (preview only) ──
  // Two fingers on preview = zoom. One finger = draw white.
  // ══════════════════════════════════════════
  var zoom = 1;
  var isPinching = false;
  var pinchStartDist = 0;
  var pinchStartZoom = 1;

  function getPinchDist(touches) {
    var dx = touches[0].clientX - touches[1].clientX;
    var dy = touches[0].clientY - touches[1].clientY;
    return Math.sqrt(dx * dx + dy * dy);
  }

  function applyZoom() {
    previewWrapper.style.transform = 'scale(' + zoom + ')';
    var extraW = previewWrapper.scrollWidth * (zoom - 1);
    var extraH = previewWrapper.scrollHeight * (zoom - 1);
    previewWrapper.style.marginRight = extraW + 'px';
    previewWrapper.style.marginBottom = extraH + 'px';
  }

  // ── Pinch zoom on preview area (works when eraser is OFF) ──
  // When eraser is ON, canvas captures touches instead (with stopPropagation).
  previewArea.addEventListener('touchstart', function(e) {
    if (e.touches.length >= 2) {
      e.preventDefault();
      isPinching = true;
      pinchStartDist = getPinchDist(e.touches);
      pinchStartZoom = zoom;
    }
  }, { passive: false });

  previewArea.addEventListener('touchmove', function(e) {
    if (e.touches.length >= 2 && isPinching) {
      e.preventDefault();
      var dist = getPinchDist(e.touches);
      zoom = Math.max(1, Math.min(5, pinchStartZoom * (dist / pinchStartDist)));
      applyZoom();
    }
  }, { passive: false });

  previewArea.addEventListener('touchend', function(e) {
    if (e.touches.length < 2) isPinching = false;
  });

  // ══════════════════════════════════════════
  // ── WHITE-OUT CANVAS (always active) ──
  // One finger = draw white. Two fingers = pinch zoom.
  // touch-action: none — we handle ALL touches in JS.
  // ══════════════════════════════════════════
  var strokes = [];
  var currentStroke = null;
  var BRUSH_SIZES = [6, 10, 18, 28];
  var brushIndex = 1;
  var ctx = whiteoutCanvas.getContext('2d');

  function getBrush() { return BRUSH_SIZES[brushIndex]; }

  function resizeCanvas() {
    var w = labelContainer.offsetWidth;
    var h = labelContainer.offsetHeight;
    if (w === 0 || h === 0) return;
    whiteoutCanvas.width = w;
    whiteoutCanvas.height = h;
    redrawStrokes();
  }

  function redrawStrokes() {
    ctx.clearRect(0, 0, whiteoutCanvas.width, whiteoutCanvas.height);
    for (var i = 0; i < strokes.length; i++) {
      paintStroke(strokes[i]);
    }
  }

  function paintStroke(s) {
    if (!s.pts.length) return;
    var r = s.brush;
    ctx.fillStyle = '#FFFFFF';
    ctx.strokeStyle = '#FFFFFF';
    ctx.lineWidth = r * 2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.arc(s.pts[0].x, s.pts[0].y, r, 0, Math.PI * 2);
    ctx.fill();
    if (s.pts.length > 1) {
      ctx.beginPath();
      ctx.moveTo(s.pts[0].x, s.pts[0].y);
      for (var i = 1; i < s.pts.length; i++) {
        ctx.lineTo(s.pts[i].x, s.pts[i].y);
      }
      ctx.stroke();
    }
  }

  function pos(e) {
    var r = whiteoutCanvas.getBoundingClientRect();
    var cx = e.touches ? e.touches[0].clientX : e.clientX;
    var cy = e.touches ? e.touches[0].clientY : e.clientY;
    return {
      x: ((cx - r.left) / r.width) * whiteoutCanvas.width,
      y: ((cy - r.top) / r.height) * whiteoutCanvas.height
    };
  }

  function drawStart(e) {
    // Two fingers → pinch zoom (handled here when eraser is on)
    if (e.touches && e.touches.length >= 2) {
      e.preventDefault();
      e.stopPropagation(); // prevent previewArea from double-handling
      isPinching = true;
      currentStroke = null;
      pinchStartDist = getPinchDist(e.touches);
      pinchStartZoom = zoom;
      return;
    }
    // Don't start drawing right after a pinch ends
    if (isPinching) return;
    // One finger → record start (don't preventDefault yet, let browser decide scroll vs draw)
    var p = pos(e);
    currentStroke = { pts: [p], brush: getBrush() };
  }

  function drawMove(e) {
    // Pinch zoom — prevent scroll during pinch
    if (e.touches && e.touches.length >= 2 && isPinching) {
      e.preventDefault();
      e.stopPropagation(); // prevent previewArea from double-handling
      var dist = getPinchDist(e.touches);
      zoom = Math.max(1, Math.min(5, pinchStartZoom * (dist / pinchStartDist)));
      applyZoom();
      currentStroke = null;
      return;
    }
    if (!e.touches) e.preventDefault();
    // If second finger was just added, cancel drawing
    if (e.touches && e.touches.length >= 2) {
      isPinching = true;
      currentStroke = null;
      pinchStartDist = getPinchDist(e.touches);
      pinchStartZoom = zoom;
      return;
    }
    if (!currentStroke) return;
    var p = pos(e);
    currentStroke.pts.push(p);
    // Paint initial dot on first move
    if (currentStroke.pts.length === 2) {
      ctx.fillStyle = '#FFFFFF';
      ctx.beginPath();
      ctx.arc(currentStroke.pts[0].x, currentStroke.pts[0].y, currentStroke.brush, 0, Math.PI * 2);
      ctx.fill();
    }
    var prev = currentStroke.pts[currentStroke.pts.length - 2];
    var br = currentStroke.brush;
    ctx.strokeStyle = '#FFFFFF';
    ctx.lineWidth = br * 2;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(prev.x, prev.y);
    ctx.lineTo(p.x, p.y);
    ctx.stroke();
  }

  function drawEnd(e) {
    // If fingers still down, don't finalize
    if (e.touches && e.touches.length >= 2) return;
    if (e.touches && e.touches.length === 1 && isPinching) return;
    if (e.touches && e.touches.length === 0) isPinching = false;
    if (currentStroke && currentStroke.pts.length > 0) {
      eraserEverUsed = true;
      // If it was just a tap (no move), paint the dot now
      if (currentStroke.pts.length === 1) {
        ctx.fillStyle = '#FFFFFF';
        ctx.beginPath();
        ctx.arc(currentStroke.pts[0].x, currentStroke.pts[0].y, currentStroke.brush, 0, Math.PI * 2);
        ctx.fill();
      }
      strokes.push(currentStroke);
      updateUndoBtn();
    }
    currentStroke = null;
  }

  function drawCancel() {
    // Browser took over (scrolling) — discard partial stroke
    currentStroke = null;
    isPinching = false;
  }

  // Touch
  whiteoutCanvas.addEventListener('touchstart', drawStart, { passive: false });
  whiteoutCanvas.addEventListener('touchmove', drawMove, { passive: false });
  whiteoutCanvas.addEventListener('touchend', drawEnd, { passive: false });
  whiteoutCanvas.addEventListener('touchcancel', drawCancel);
  // Mouse (desktop)
  whiteoutCanvas.addEventListener('mousedown', drawStart);
  whiteoutCanvas.addEventListener('mousemove', drawMove);
  whiteoutCanvas.addEventListener('mouseup', drawEnd);
  whiteoutCanvas.addEventListener('mouseleave', drawEnd);

  // ── Brush thickness selector ──
  var thicknessBar = document.getElementById('thicknessBar');
  var thicknessBtns = thicknessBar.querySelectorAll('.thick-btn');

  function updateThicknessUI() {
    for (var i = 0; i < thicknessBtns.length; i++) {
      thicknessBtns[i].classList.toggle('active', i === brushIndex);
    }
  }

  for (var ti = 0; ti < thicknessBtns.length; ti++) {
    (function(idx) {
      thicknessBtns[idx].addEventListener('click', function() {
        brushIndex = idx;
        updateThicknessUI();
      });
    })(ti);
  }
  updateThicknessUI();

  // ── Eraser toggle ──
  thicknessBar.style.display = 'none'; // hidden by default
  eraserToggle.addEventListener('click', function() {
    eraserEnabled = !eraserEnabled;
    if (eraserEnabled) {
      whiteoutCanvas.style.pointerEvents = 'auto';
      whiteoutCanvas.style.touchAction = 'none'; // we handle all gestures in JS
      whiteoutCanvas.style.cursor = 'crosshair';
      eraserToggle.classList.add('active');
      thicknessBar.style.display = 'flex';
    } else {
      whiteoutCanvas.style.pointerEvents = 'none';
      whiteoutCanvas.style.touchAction = 'pan-y';
      whiteoutCanvas.style.cursor = 'default';
      eraserToggle.classList.remove('active');
      thicknessBar.style.display = 'none';
    }
  });

  // Floating undo button
  function updateUndoBtn() {
    undoBtn.style.display = strokes.length > 0 ? 'flex' : 'none';
  }

  undoBtn.addEventListener('click', function() {
    strokes.pop();
    redrawStrokes();
    updateUndoBtn();
  });

  // Auto-resize canvas
  new ResizeObserver(function() { resizeCanvas(); }).observe(labelContainer);

  // ══════════════════════════════════════════
  // ── PRINT ──
  // ══════════════════════════════════════════
  function buildPrintArea(selectedImgs, layout) {
    console.log('[PRINT-DEBUG] buildPrintArea: contentType=' + contentType
      + ' selectedImgs=' + selectedImgs.length + ' layout=' + layout);
    printArea.innerHTML = '';
    printArea.className = '';

    if ((contentType === 'pdf' || contentType === 'image') && selectedImgs.length > 0) {
      var pages = contentType === 'image'
        ? Array(layout).fill(selectedImgs[0])
        : selectedImgs;
      console.log('[PRINT-DEBUG] buildPrintArea: PDF/Image path, pages=' + pages.length);

      for (var i = 0; i < pages.length; i += layout) {
        var sheet = document.createElement('div');
        sheet.className = 'print-sheet layout-' + layout;
        for (var j = 0; j < layout && (i + j) < pages.length; j++) {
          var tile = document.createElement('div');
          tile.className = 'print-tile';
          tile.innerHTML = '<img src="' + pages[i + j] + '">';
          sheet.appendChild(tile);
        }
        printArea.appendChild(sheet);
      }
      console.log('[PRINT-DEBUG] buildPrintArea: created ' + printArea.children.length + ' sheets');
    } else {
      console.log('[PRINT-DEBUG] buildPrintArea: Label path, layout=' + layout);
      var sheet = document.createElement('div');
      sheet.className = 'print-sheet layout-' + layout;
      var html = labelContainer.innerHTML;
      console.log('[PRINT-DEBUG] buildPrintArea: labelContainer HTML length=' + html.length);
      var whiteoutImg = whiteoutCanvas.toDataURL('image/png');
      var hasMarks = strokes.length > 0;
      console.log('[PRINT-DEBUG] buildPrintArea: hasMarks=' + hasMarks + ' strokes=' + strokes.length);

      for (var i = 0; i < layout; i++) {
        var tile = document.createElement('div');
        tile.className = 'print-tile';
        var inner = '<div class="print-tile-content"><div class="label-container">' + html + '</div></div>';
        if (hasMarks) {
          inner += '<img class="print-tile-overlay" src="' + whiteoutImg + '">';
        }
        tile.innerHTML = inner;
        sheet.appendChild(tile);
      }
      printArea.appendChild(sheet);
      console.log('[PRINT-DEBUG] buildPrintArea: created 1 sheet with ' + layout + ' tiles');
    }
  }

  var isPrinting = false;
  printBtn.addEventListener('click', function() {
    console.log('[PRINT-DEBUG] Print button clicked. isPrinting=' + isPrinting
      + ' contentType=' + contentType
      + ' pageImages.length=' + pageImages.length
      + ' isAndroid=' + !!(window.AndroidBridge && window.AndroidBridge.isAndroid()));

    if (isPrinting) {
      console.log('[PRINT-DEBUG] Print BLOCKED — already printing');
      return;
    }
    isPrinting = true;
    printBtn.disabled = true;

    // Visual press feedback
    printBtn.classList.add('print-btn-pressed');
    setTimeout(function() { printBtn.classList.remove('print-btn-pressed'); }, 200);

    try {
      // Bake any eraser marks into page images before printing
      bakeStrokes();

      var layout = parseInt(layoutSelect.value, 10);
      // Filter to only selected pages
      var selectedImgs = [];
      for (var si = 0; si < pageImages.length; si++) {
        if (selectedPages[si] !== false) selectedImgs.push(pageImages[si]);
      }
      console.log('[PRINT-DEBUG] selectedImgs=' + selectedImgs.length + '/' + pageImages.length + ' layout=' + layout + ' orientations=' + JSON.stringify(pageOrientations));
      buildPrintArea(selectedImgs, layout);
      if (window.AndroidBridge && window.AndroidBridge.isAndroid()) {
        var allSelected = selectedImgs.length === pageImages.length;
        if (layout === 1 && allSelected && contentType === 'pdf'
            && window.AndroidBridge.hasOriginalPdf && window.AndroidBridge.hasOriginalPdf()) {
          console.log('[PRINT-DEBUG] Calling AndroidBridge.printDirectPdf()');
          window.AndroidBridge.printDirectPdf(1);
        } else if ((contentType === 'pdf' || contentType === 'image') && selectedImgs.length > 0) {
          console.log('[PRINT-DEBUG] Calling AndroidBridge.printDirect() layout=' + layout
            + ' pages=' + selectedImgs.length);
          window.AndroidBridge.printDirect(JSON.stringify(selectedImgs), layout, 1);
        } else {
          console.log('[PRINT-DEBUG] Calling AndroidBridge.print()');
          window.AndroidBridge.print(1);
        }
      } else {
        console.log('[PRINT-DEBUG] Calling window.print() (browser path)');
        window.print();
      }
    } catch (err) {
      console.error('[PRINT-DEBUG] Print FAILED: ' + err.message + '\n' + err.stack);
      try {
        if (window.AndroidBridge) {
          console.log('[PRINT-DEBUG] Trying fallback: AndroidBridge.print()');
          window.AndroidBridge.print(1);
        } else {
          console.log('[PRINT-DEBUG] Trying fallback: window.print()');
          window.print();
        }
      } catch (e2) {
        console.error('[PRINT-DEBUG] Fallback also FAILED: ' + e2.message);
      }
    } finally {
      // Block re-prints for 5 seconds to prevent duplicate jobs
      setTimeout(function() {
        isPrinting = false;
        printBtn.disabled = false;
        console.log('[PRINT-DEBUG] Print button re-enabled');
      }, 5000);
    }
  });

  // ══════════════════════════════════════════
  // ── FILE HANDLING ──
  // ══════════════════════════════════════════
  function clearWhiteout() {
    strokes = [];
    currentStroke = null;
    // NOTE: do NOT reset eraserEverUsed here — bakeStrokes calls clearWhiteout
    // after baking marks into pageImages. eraserEverUsed is only reset in displayFile/displayFileFromBase64.
    ctx.clearRect(0, 0, whiteoutCanvas.width, whiteoutCanvas.height);
    updateUndoBtn();
  }

  // Bake white-out strokes permanently into pageImages so eraser marks
  // survive layout changes and appear in print output for PDF/image content.
  function bakeStrokes() {
    if (strokes.length === 0 || pageImages.length === 0) return;
    if (contentType === 'label') return;

    var containerRect = labelContainer.getBoundingClientRect();
    if (containerRect.width === 0 || containerRect.height === 0) return;

    var scaleX = whiteoutCanvas.width / containerRect.width;
    var scaleY = whiteoutCanvas.height / containerRect.height;

    // Collect displayed page <img> elements, skipping thumbnail strip
    var allImgs = labelContainer.querySelectorAll('img');
    var pageImgEls = [];
    for (var i = 0; i < allImgs.length; i++) {
      if (!allImgs[i].closest('.page-thumb')) pageImgEls.push(allImgs[i]);
    }
    if (pageImgEls.length === 0) return;

    // Map displayed tile index → pageImages index (accounting for deselected pages)
    var selIndexes = [];
    for (var i = 0; i < selectedPages.length; i++) {
      if (selectedPages[i] !== false) selIndexes.push(i);
    }

    var baked = {};
    for (var t = 0; t < pageImgEls.length; t++) {
      var pageIdx = (contentType === 'image') ? 0 : (t < selIndexes.length ? selIndexes[t] : -1);
      if (pageIdx < 0 || baked[pageIdx]) continue;

      var imgEl = pageImgEls[t];
      var imgRect = imgEl.getBoundingClientRect();

      // Map image position to canvas pixel coordinates
      var cx = (imgRect.left - containerRect.left) * scaleX;
      var cy = (imgRect.top - containerRect.top) * scaleY;
      var cw = imgRect.width * scaleX;
      var ch = imgRect.height * scaleY;
      if (cw <= 0 || ch <= 0) continue;

      // Composite: draw original page then overlay whiteout region
      var tmpCanvas = document.createElement('canvas');
      tmpCanvas.width = imgEl.naturalWidth || Math.round(cw);
      tmpCanvas.height = imgEl.naturalHeight || Math.round(ch);
      var tmpCtx = tmpCanvas.getContext('2d');
      tmpCtx.drawImage(imgEl, 0, 0, tmpCanvas.width, tmpCanvas.height);
      tmpCtx.drawImage(whiteoutCanvas, cx, cy, cw, ch, 0, 0, tmpCanvas.width, tmpCanvas.height);

      pageImages[pageIdx] = tmpCanvas.toDataURL('image/png');
      baked[pageIdx] = true;
    }

    clearWhiteout();
  }

  function renderPdfPages(pdfData) {
    labelContainer.innerHTML = '';
    pageImages = [];
    pageOrientations = [];
    contentType = 'pdf';
    var container = document.createElement('div');
    container.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:4px;padding:4px';
    labelContainer.appendChild(container);

    pdfjsLib.getDocument(pdfData).promise.then(function(pdf) {
      function renderPage(num) {
        if (num > pdf.numPages) { resizeCanvas(); return; }
        pdf.getPage(num).then(function(page) {
          var containerWidth = labelContainer.offsetWidth - 16;
          var vp = page.getViewport({ scale: 1 });
          var scale = Math.min(4960 / vp.width, 10); // 600 DPI (A4) for max print quality
          console.log('[RENDER] Page ' + num + ': vp=' + vp.width.toFixed(0) + 'x' + vp.height.toFixed(0) + ' scale=' + scale.toFixed(2));
          var scaled = page.getViewport({ scale: scale });

          var canvas = document.createElement('canvas');
          canvas.width = scaled.width;
          canvas.height = scaled.height;
          canvas.style.cssText = 'width:100%;display:block;border-radius:4px;background:#fff';
          container.appendChild(canvas);

          page.render({ canvasContext: canvas.getContext('2d'), viewport: scaled }).promise.then(function() {
            var orient = scaled.width > scaled.height ? 'landscape' : 'portrait';
            pageOrientations.push(orient);
            console.log('[RENDER] Page ' + num + ': canvas=' + canvas.width + 'x' + canvas.height + ' orient=' + orient);
            pageImages.push(canvas.toDataURL('image/jpeg', 0.98));
            if (num === pdf.numPages) {
              initSelectedPages();
              updatePreviewLayout();
            }
            renderPage(num + 1);
          });
        });
      }
      renderPage(1);
    }).catch(function(err) {
      labelContainer.innerHTML =
        '<div style="padding:32px 16px;text-align:center;color:#c00">' +
        '<div style="font-size:15px;font-weight:600">Could not load PDF</div>' +
        '<div style="font-size:13px;margin-top:4px;color:#888">' + (err.message || err) + '</div>' +
        '</div>';
    });
  }

  function base64ToUint8Array(b64) {
    var raw = atob(b64);
    var arr = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) arr[i] = raw.charCodeAt(i);
    return arr;
  }

  function displayFile(file) {
    clearWhiteout();
    eraserEverUsed = false;
    zoom = 1;
    applyZoom();
    if (file.type.startsWith('image/')) {
      contentType = 'image';
      pageImages = [];
      pageOrientations = [];
      // Read as data URL for printing
      var imgReader = new FileReader();
      imgReader.onload = function() { pageImages = [imgReader.result]; initSelectedPages(); };
      imgReader.readAsDataURL(file);
      var url = URL.createObjectURL(file);
      labelContainer.innerHTML =
        '<div style="padding:12px;text-align:center">' +
        '<img src="' + url + '" style="max-width:100%;max-height:70vh;border-radius:4px;object-fit:contain">' +
        '</div>';
    } else if (file.type === 'application/pdf') {
      var reader = new FileReader();
      reader.onload = function() {
        renderPdfPages({ data: new Uint8Array(reader.result) });
      };
      reader.readAsArrayBuffer(file);
    } else {
      contentType = 'label';
      pageImages = [];
      pageOrientations = [];
      labelContainer.innerHTML =
        '<div style="padding:32px 16px;text-align:center;color:#555">' +
        '<div style="font-size:15px;font-weight:600">' + file.name + '</div>' +
        '<div style="font-size:13px;margin-top:4px;color:#888">' + (file.size / 1024).toFixed(1) + ' KB</div>' +
        '</div>';
    }
  }

  function displayFileFromBase64(name, mimeType, base64Data) {
    clearWhiteout();
    eraserEverUsed = false;
    zoom = 1;
    applyZoom();
    if (mimeType.startsWith('image/')) {
      contentType = 'image';
      pageOrientations = [];
      var dataUrl = 'data:' + mimeType + ';base64,' + base64Data;
      pageImages = [dataUrl];
      initSelectedPages();
      labelContainer.innerHTML =
        '<div style="padding:12px;text-align:center">' +
        '<img src="' + dataUrl + '" style="max-width:100%;max-height:70vh;border-radius:4px;object-fit:contain">' +
        '</div>';
    } else if (mimeType === 'application/pdf') {
      renderPdfPages({ data: base64ToUint8Array(base64Data) });
    } else {
      contentType = 'label';
      pageImages = [];
      pageOrientations = [];
      labelContainer.innerHTML =
        '<div style="padding:32px 16px;text-align:center;color:#555">' +
        '<div style="font-size:15px;font-weight:600">' + name + '</div>' +
        '</div>';
    }
  }

  // ── Open File button (file picker) ──
  openFileBtn.addEventListener('click', function() {
    console.log('Open File button clicked');
    // Use native Android file picker if available (WebView fileInput.click() is unreliable)
    if (window.AndroidBridge && typeof window.AndroidBridge.openFilePicker === 'function') {
      window.AndroidBridge.openFilePicker();
    } else {
      fileInput.click();
    }
  });
  fileInput.addEventListener('change', function() {
    console.log('File selected:', fileInput.files[0] ? fileInput.files[0].name : 'none');
    if (fileInput.files && fileInput.files[0]) {
      displayFile(fileInput.files[0]);
      fileInput.value = '';
    }
  });

  // ── Helper: export selected pages (download or share) ──
  // ── Web-native PDF builder from JPEG data URLs ──
  // Builds a valid PDF with embedded JPEG images (no external library needed)
  function buildPdfFromImages(imageDataUrls) {
    console.log('[PDF-BUILD] Building PDF from ' + imageDataUrls.length + ' images');
    var objects = [];
    var xref = [];

    function addObj(content) {
      var num = objects.length + 1;
      objects.push(num + ' 0 obj\n' + content + '\nendobj\n');
      return num;
    }

    // Decode base64 JPEG data URLs to binary arrays
    var imgBinaries = [];
    for (var i = 0; i < imageDataUrls.length; i++) {
      var dataUrl = imageDataUrls[i];
      var base64 = dataUrl.indexOf(',') >= 0 ? dataUrl.split(',')[1] : dataUrl;
      var raw = atob(base64);
      var bytes = new Uint8Array(raw.length);
      for (var j = 0; j < raw.length; j++) bytes[j] = raw.charCodeAt(j);
      imgBinaries.push(bytes);
      console.log('[PDF-BUILD] Image ' + i + ': base64len=' + base64.length + ' bytes=' + bytes.length);
    }

    // Get image dimensions from JPEG SOF marker
    function getJpegDimensions(data) {
      var pos = 2;
      while (pos < data.length - 1) {
        if (data[pos] !== 0xFF) break;
        var marker = data[pos + 1];
        if (marker === 0xC0 || marker === 0xC1 || marker === 0xC2) {
          var h = (data[pos + 5] << 8) | data[pos + 6];
          var w = (data[pos + 7] << 8) | data[pos + 8];
          return { width: w, height: h };
        }
        var len = (data[pos + 2] << 8) | data[pos + 3];
        pos += 2 + len;
      }
      return { width: 595, height: 842 }; // fallback A4
    }

    // Object 1: Catalog
    var catalogNum = addObj('<< /Type /Catalog /Pages 2 0 R >>');

    // Build page tree — we need to know all objects first
    // Reserve object 2 for Pages
    objects.push(''); // placeholder for Pages object
    var pagesNum = 2;

    var pageObjNums = [];
    var imgObjNums = [];

    for (var p = 0; p < imageDataUrls.length; p++) {
      var dim = getJpegDimensions(imgBinaries[p]);
      console.log('[PDF-BUILD] Page ' + p + ': imgDim=' + dim.width + 'x' + dim.height);

      // Determine page size in points (A4 = 595x842)
      var landscape = dim.width > dim.height;
      var pageW = landscape ? 842 : 595;
      var pageH = landscape ? 595 : 842;

      // Image XObject — stored as obj num
      var imgNum = objects.length + 1;
      // We'll build the stream content after, using a special marker
      imgObjNums.push({ num: imgNum, dataIdx: p, w: dim.width, h: dim.height });
      objects.push('IMG_PLACEHOLDER_' + p); // will be replaced

      // Content stream: draw image scaled to full page
      var contentStr = 'q ' + pageW + ' 0 0 ' + pageH + ' 0 0 cm /Img' + p + ' Do Q';
      var contentNum = addObj('<< /Length ' + contentStr.length + ' >>\nstream\n' + contentStr + '\nendstream');

      // Page object
      var pageNum = addObj('<< /Type /Page /Parent ' + pagesNum + ' 0 R /MediaBox [0 0 ' + pageW + ' ' + pageH + '] /Contents ' + contentNum + ' 0 R /Resources << /XObject << /Img' + p + ' ' + imgNum + ' 0 R >> >> >>');
      pageObjNums.push(pageNum);
    }

    // Now fill in Pages object (object 2)
    var kidsStr = '';
    for (var k = 0; k < pageObjNums.length; k++) {
      kidsStr += pageObjNums[k] + ' 0 R ';
    }
    objects[1] = pagesNum + ' 0 obj\n<< /Type /Pages /Kids [' + kidsStr.trim() + '] /Count ' + pageObjNums.length + ' >>\nendobj\n';

    // Now build the actual PDF binary
    // First, build all non-image object strings
    var header = '%PDF-1.4\n%\xFF\xFF\xFF\xFF\n';
    var chunks = [header];
    var offsets = [];

    for (var o = 0; o < objects.length; o++) {
      if (typeof objects[o] === 'string' && objects[o].indexOf('IMG_PLACEHOLDER_') === 0) {
        // This is an image object — we'll handle it specially
        var idx = parseInt(objects[o].replace('IMG_PLACEHOLDER_', ''));
        var imgInfo = imgObjNums[idx];
        var imgHeader = (o + 1) + ' 0 obj\n<< /Type /XObject /Subtype /Image /Width ' + imgInfo.w + ' /Height ' + imgInfo.h + ' /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ' + imgBinaries[idx].length + ' >>\nstream\n';
        var imgFooter = '\nendstream\nendobj\n';

        offsets.push(chunks.reduce(function(sum, c) { return sum + (typeof c === 'string' ? c.length : c.length); }, 0));
        chunks.push(imgHeader);
        chunks.push(imgBinaries[idx]); // raw binary
        chunks.push(imgFooter);
      } else {
        offsets.push(chunks.reduce(function(sum, c) { return sum + (typeof c === 'string' ? c.length : c.length); }, 0));
        chunks.push(objects[o]);
      }
    }

    // Cross-reference table
    var xrefOffset = chunks.reduce(function(sum, c) { return sum + (typeof c === 'string' ? c.length : c.length); }, 0);
    var xrefStr = 'xref\n0 ' + (objects.length + 1) + '\n0000000000 65535 f \n';
    for (var x = 0; x < offsets.length; x++) {
      var off = offsets[x].toString();
      while (off.length < 10) off = '0' + off;
      xrefStr += off + ' 00000 n \n';
    }
    xrefStr += 'trailer\n<< /Size ' + (objects.length + 1) + ' /Root ' + catalogNum + ' 0 R >>\nstartxref\n' + xrefOffset + '\n%%EOF';
    chunks.push(xrefStr);

    // Combine all chunks into a single Uint8Array
    var totalLen = 0;
    for (var ci = 0; ci < chunks.length; ci++) {
      totalLen += (typeof chunks[ci] === 'string') ? chunks[ci].length : chunks[ci].length;
    }
    var result = new Uint8Array(totalLen);
    var pos = 0;
    for (var ci2 = 0; ci2 < chunks.length; ci2++) {
      var chunk = chunks[ci2];
      if (typeof chunk === 'string') {
        for (var si = 0; si < chunk.length; si++) {
          result[pos++] = chunk.charCodeAt(si);
        }
      } else {
        result.set(chunk, pos);
        pos += chunk.length;
      }
    }

    console.log('[PDF-BUILD] Final PDF size: ' + result.length + ' bytes');
    return result;
  }

  function exportPdf(action) {
    try {
      var isAndroid = !!(window.AndroidBridge && window.AndroidBridge.isAndroid && window.AndroidBridge.isAndroid());
      console.log('[EXPORT] ' + action + ' clicked. contentType=' + contentType
        + ' pageImages=' + pageImages.length + ' eraserEverUsed=' + eraserEverUsed
        + ' isAndroid=' + isAndroid);

      // Check if anything is loaded
      if (pageImages.length === 0) {
        console.log('[EXPORT] ' + action + ': no pages loaded, aborting');
        alert('Open a file first');
        return;
      }

      // Save eraser state BEFORE baking (bakeStrokes→clearWhiteout clears strokes)
      var hadEraser = eraserEverUsed;
      console.log('[EXPORT] ' + action + ': hadEraser=' + hadEraser + ' strokes=' + strokes.length);
      bakeStrokes();

      // Build selected pages list
      var selectedImgs = [];
      for (var i = 0; i < pageImages.length; i++) {
        if (selectedPages[i] !== false) selectedImgs.push(pageImages[i]);
      }
      console.log('[EXPORT] ' + action + ': selectedImgs=' + selectedImgs.length
        + '/' + pageImages.length + ' contentType=' + contentType);

      if (selectedImgs.length === 0) {
        console.log('[EXPORT] ' + action + ': no pages selected, aborting');
        alert('No pages selected');
        return;
      }

      // ── Android path ──
      if (isAndroid) {
        var allSelected = selectedImgs.length === pageImages.length;
        var hasOriginal = window.AndroidBridge.hasOriginalPdf && window.AndroidBridge.hasOriginalPdf();
        var useOriginal = !hadEraser && allSelected && contentType === 'pdf' && hasOriginal;

        console.log('[EXPORT] Android path: allSelected=' + allSelected
          + ' hasOriginal=' + hasOriginal + ' useOriginal=' + useOriginal);

        if (action === 'download') {
          if (useOriginal) {
            console.log('[EXPORT] calling downloadOriginalPdf()');
            window.AndroidBridge.downloadOriginalPdf();
          } else {
            console.log('[EXPORT] calling downloadPdf() with ' + selectedImgs.length + ' images');
            window.AndroidBridge.downloadPdf(JSON.stringify(selectedImgs));
          }
        } else {
          if (useOriginal) {
            console.log('[EXPORT] calling shareOriginalPdf()');
            window.AndroidBridge.shareOriginalPdf();
          } else {
            console.log('[EXPORT] calling sharePdf() with ' + selectedImgs.length + ' images');
            window.AndroidBridge.sharePdf(JSON.stringify(selectedImgs));
          }
        }
        return;
      }

      // ── Web path (browser / GitHub Pages) ──
      console.log('[EXPORT] Web path: building PDF from ' + selectedImgs.length + ' images');
      var pdfBytes = buildPdfFromImages(selectedImgs);
      var blob = new Blob([pdfBytes], { type: 'application/pdf' });
      var fileName = 'print-document-' + Date.now() + '.pdf';
      console.log('[EXPORT] PDF blob created: ' + blob.size + ' bytes, fileName=' + fileName);

      if (action === 'download') {
        // Trigger browser download
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(function() { URL.revokeObjectURL(url); }, 5000);
        console.log('[EXPORT] Download triggered');
      } else {
        // Share via Web Share API
        if (navigator.share && navigator.canShare) {
          var file = new File([blob], fileName, { type: 'application/pdf' });
          var shareData = { files: [file], title: 'Print Document' };
          if (navigator.canShare(shareData)) {
            navigator.share(shareData).then(function() {
              console.log('[EXPORT] Share completed');
            }).catch(function(err) {
              console.log('[EXPORT] Share cancelled/failed: ' + err.message);
            });
          } else {
            console.log('[EXPORT] canShare returned false, falling back to download');
            alert('Sharing not supported on this device. Downloading instead.');
            exportPdf('download');
          }
        } else {
          console.log('[EXPORT] Web Share API not available, falling back to download');
          alert('Share not available in this browser. Downloading instead.');
          exportPdf('download');
        }
      }
    } catch (err) {
      console.error('[EXPORT] ' + action + ' ERROR: ' + err.message + '\n' + err.stack);
      alert('Export failed: ' + err.message);
    }
  }

  // ── Download PDF button ──
  downloadBtn.addEventListener('click', function() { exportPdf('download'); });

  // ── Share PDF button ──
  shareBtn.addEventListener('click', function() { exportPdf('share'); });

  window.handleNativeFile = displayFileFromBase64;

  if (window._pendingFile) {
    displayFileFromBase64(window._pendingFile.name, window._pendingFile.type, window._pendingFile.data);
    window._pendingFile = null;
  }

  if ('launchQueue' in window) {
    window.launchQueue.setConsumer(async function(launchParams) {
      if (launchParams.files && launchParams.files.length > 0) {
        var handle = launchParams.files[0];
        var file = await handle.getFile();
        displayFile(file);
      }
    });
  }
});
