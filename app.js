// ── Register Service Worker ──
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

// ── Global error handler (visible in logcat via console.error) ──
window.onerror = function(msg, url, line, col, err) {
  console.error('JS Error: ' + msg + ' at ' + url + ':' + line + ':' + col);
};

document.addEventListener('DOMContentLoaded', () => {
  var copiesValue = document.getElementById('copiesValue');
  var copiesMinus = document.getElementById('copiesMinus');
  var copiesPlus = document.getElementById('copiesPlus');
  var printBtn = document.getElementById('printBtn');
  var layoutSelect = document.getElementById('layoutSelect');
  var labelContainer = document.getElementById('labelContainer');
  var previewWrapper = document.getElementById('previewWrapper');
  var whiteoutCanvas = document.getElementById('whiteoutCanvas');
  var undoBtn = document.getElementById('undoBtn');
  var eraserToggle = document.getElementById('eraserToggle');
  var printArea = document.getElementById('printArea');
  var printerDot = document.getElementById('printerDot');
  var printerText = document.getElementById('printerText');
  var openFileBtn = document.getElementById('openFileBtn');
  var fileInput = document.getElementById('fileInput');

  var copies = 1;
  var pageImages = [];     // data-URL per PDF/image page (for printing)
  var contentType = 'label'; // 'label' | 'pdf' | 'image'
  var selectedPages = [];  // boolean array — true = page selected for printing
  var eraserEnabled = false;

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

  // ── Copies ──
  function updateCopiesUI() {
    copiesValue.textContent = copies;
    copiesMinus.disabled = copies <= 1;
    copiesPlus.disabled = copies >= 99;
  }

  copiesMinus.addEventListener('click', function() {
    if (copies > 1) { copies--; updateCopiesUI(); }
  });
  copiesPlus.addEventListener('click', function() {
    if (copies < 99) { copies++; updateCopiesUI(); }
  });

  // ── Layout change → update in-app preview ──
  layoutSelect.addEventListener('change', function() {
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
    for (var i = 0; i < allPages.length; i++) {
      if (selectedPages[i] !== false) selPages.push(allPages[i]);
    }

    var container = document.createElement('div');
    container.className = 'preview-sheets';

    // ── Page selector strip (thumbnails with checkmarks) ──
    if (allPages.length > 1 || contentType === 'pdf') {
      var strip = document.createElement('div');
      strip.className = 'page-selector-strip';
      for (var i = 0; i < allPages.length; i++) {
        var thumb = document.createElement('div');
        thumb.className = 'page-thumb' + (selectedPages[i] !== false ? ' selected' : '');
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
    // Two fingers → pinch zoom
    if (e.touches && e.touches.length >= 2) {
      e.preventDefault();
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
      whiteoutCanvas.style.cursor = 'crosshair';
      eraserToggle.classList.add('active');
      thicknessBar.style.display = 'flex';
    } else {
      whiteoutCanvas.style.pointerEvents = 'none';
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
      + ' contentType=' + contentType + ' copies=' + copies
      + ' pageImages.length=' + pageImages.length
      + ' isAndroid=' + !!(window.AndroidBridge && window.AndroidBridge.isAndroid()));

    if (isPrinting) {
      console.log('[PRINT-DEBUG] Print BLOCKED — already printing');
      return;
    }
    isPrinting = true;
    printBtn.disabled = true;
    try {
      var layout = parseInt(layoutSelect.value, 10);
      // Filter to only selected pages
      var selectedImgs = [];
      for (var si = 0; si < pageImages.length; si++) {
        if (selectedPages[si] !== false) selectedImgs.push(pageImages[si]);
      }
      console.log('[PRINT-DEBUG] selectedImgs=' + selectedImgs.length + '/' + pageImages.length + ' layout=' + layout);
      buildPrintArea(selectedImgs, layout);
      if (window.AndroidBridge && window.AndroidBridge.isAndroid()) {
        var allSelected = selectedImgs.length === pageImages.length;
        if (layout === 1 && allSelected && contentType === 'pdf'
            && window.AndroidBridge.hasOriginalPdf && window.AndroidBridge.hasOriginalPdf()) {
          // Send original PDF bytes directly — only when 1-per-sheet and all pages selected
          console.log('[PRINT-DEBUG] Calling AndroidBridge.printDirectPdf() copies=' + copies);
          window.AndroidBridge.printDirectPdf(copies);
        } else if ((contentType === 'pdf' || contentType === 'image') && selectedImgs.length > 0) {
          console.log('[PRINT-DEBUG] Calling AndroidBridge.printDirect() layout=' + layout
            + ' copies=' + copies + ' pages=' + selectedImgs.length);
          window.AndroidBridge.printDirect(JSON.stringify(selectedImgs), layout, copies);
        } else {
          console.log('[PRINT-DEBUG] Calling AndroidBridge.print() copies=' + copies);
          window.AndroidBridge.print(copies);
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
          window.AndroidBridge.print(copies);
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
    ctx.clearRect(0, 0, whiteoutCanvas.width, whiteoutCanvas.height);
    updateUndoBtn();
  }

  function renderPdfPages(pdfData) {
    labelContainer.innerHTML = '';
    pageImages = [];
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
          var scale = (containerWidth / vp.width) * 2; // 2x for print quality
          var scaled = page.getViewport({ scale: scale });

          var canvas = document.createElement('canvas');
          canvas.width = scaled.width;
          canvas.height = scaled.height;
          canvas.style.cssText = 'width:100%;display:block;border-radius:4px;background:#fff';
          container.appendChild(canvas);

          page.render({ canvasContext: canvas.getContext('2d'), viewport: scaled }).promise.then(function() {
            pageImages.push(canvas.toDataURL('image/png'));
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
    if (file.type.startsWith('image/')) {
      contentType = 'image';
      pageImages = [];
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
      labelContainer.innerHTML =
        '<div style="padding:32px 16px;text-align:center;color:#555">' +
        '<div style="font-size:15px;font-weight:600">' + file.name + '</div>' +
        '<div style="font-size:13px;margin-top:4px;color:#888">' + (file.size / 1024).toFixed(1) + ' KB</div>' +
        '</div>';
    }
  }

  function displayFileFromBase64(name, mimeType, base64Data) {
    clearWhiteout();
    if (mimeType.startsWith('image/')) {
      contentType = 'image';
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

  updateCopiesUI();
});
