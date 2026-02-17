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
  var previewArea = document.getElementById('previewArea');
  var whiteoutCanvas = document.getElementById('whiteoutCanvas');
  var undoBtn = document.getElementById('undoBtn');
  var printArea = document.getElementById('printArea');
  var printerDot = document.getElementById('printerDot');
  var printerText = document.getElementById('printerText');
  var openFileBtn = document.getElementById('openFileBtn');
  var fileInput = document.getElementById('fileInput');
  var eraserToggle = document.getElementById('eraserToggle');

  var copies = 1;
  var pageImages = [];     // data-URL per PDF/image page (for printing)
  var contentType = 'label'; // 'label' | 'pdf' | 'image'
  var eraserActive = false;

  // ── Printer status ──
  function updatePrinterStatus(status) {
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

  setInterval(checkPrinter, 5000);
  checkPrinter();

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

  function updatePreviewLayout() {
    if (contentType === 'label' || pageImages.length === 0) return;
    var layout = parseInt(layoutSelect.value, 10);
    labelContainer.innerHTML = '';

    // For images: duplicate the single image to fill the layout
    var pages = (contentType === 'image') ? Array(layout).fill(pageImages[0]) : pageImages;

    if (layout === 1) {
      // 1 per sheet: show each page as a separate A4 sheet preview
      var container = document.createElement('div');
      container.className = 'preview-sheets';
      for (var i = 0; i < pages.length; i++) {
        var sheet = document.createElement('div');
        sheet.className = 'preview-sheet preview-layout-1';
        var tile = document.createElement('div');
        tile.className = 'preview-tile';
        var img = document.createElement('img');
        img.src = pages[i];
        tile.appendChild(img);
        sheet.appendChild(tile);
        container.appendChild(sheet);
        if (pages.length > 1) {
          var lbl = document.createElement('div');
          lbl.className = 'preview-sheet-label';
          lbl.textContent = 'Page ' + (i + 1) + ' of ' + pages.length;
          container.appendChild(lbl);
        }
      }
      labelContainer.appendChild(container);
    } else {
      // 2/3/4 per sheet: show pages grouped into sheet previews
      var container = document.createElement('div');
      container.className = 'preview-sheets';

      var totalSheets = Math.ceil(pages.length / layout);
      for (var i = 0; i < pages.length; i += layout) {
        var sheet = document.createElement('div');
        sheet.className = 'preview-sheet preview-layout-' + layout;

        for (var j = 0; j < layout && (i + j) < pages.length; j++) {
          var tile = document.createElement('div');
          tile.className = 'preview-tile';
          var img = document.createElement('img');
          img.src = pages[i + j];
          tile.appendChild(img);
          sheet.appendChild(tile);
        }
        container.appendChild(sheet);

        if (totalSheets > 1) {
          var lbl = document.createElement('div');
          lbl.className = 'preview-sheet-label';
          lbl.textContent = 'Page ' + (Math.floor(i / layout) + 1) + ' of ' + totalSheets;
          container.appendChild(lbl);
        }
      }
      labelContainer.appendChild(container);
    }
    resizeCanvas();
  }

  // ══════════════════════════════════════════
  // ── PINCH ZOOM (preview only) ──
  // Eraser OFF → scroll freely, pinch zoom via previewArea
  // Eraser ON  → canvas handles draw + pinch zoom
  // ══════════════════════════════════════════
  var zoom = 1;
  var isPinching = false;
  var pinchStartDist = 0;
  var pinchStartZoom = 1;
  var pinchCenterX = 0;
  var pinchCenterY = 0;
  var pinchStartScrollX = 0;
  var pinchStartScrollY = 0;

  function getPinchDist(touches) {
    var dx = touches[0].clientX - touches[1].clientX;
    var dy = touches[0].clientY - touches[1].clientY;
    return Math.sqrt(dx * dx + dy * dy);
  }

  function applyZoom() {
    previewWrapper.style.transform = zoom === 1 ? '' : 'scale(' + zoom + ')';
    var w = previewWrapper.offsetWidth;
    var h = previewWrapper.offsetHeight;
    previewWrapper.style.marginRight = zoom === 1 ? '' : (w * (zoom - 1)) + 'px';
    previewWrapper.style.marginBottom = zoom === 1 ? '' : (h * (zoom - 1)) + 'px';
  }

  function startPinch(touches) {
    isPinching = true;
    pinchStartDist = getPinchDist(touches);
    pinchStartZoom = zoom;
    var rect = previewArea.getBoundingClientRect();
    var center = {
      x: (touches[0].clientX + touches[1].clientX) / 2,
      y: (touches[0].clientY + touches[1].clientY) / 2
    };
    pinchCenterX = center.x - rect.left;
    pinchCenterY = center.y - rect.top;
    pinchStartScrollX = previewArea.scrollLeft;
    pinchStartScrollY = previewArea.scrollTop;
  }

  function movePinch(touches) {
    var dist = getPinchDist(touches);
    zoom = Math.max(1, Math.min(5, pinchStartZoom * (dist / pinchStartDist)));
    applyZoom();
    // Keep pinch center point stationary
    var contentX = (pinchStartScrollX + pinchCenterX) / pinchStartZoom;
    var contentY = (pinchStartScrollY + pinchCenterY) / pinchStartZoom;
    previewArea.scrollLeft = Math.max(0, contentX * zoom - pinchCenterX);
    previewArea.scrollTop = Math.max(0, contentY * zoom - pinchCenterY);
  }

  function resetZoom() {
    zoom = 1;
    isPinching = false;
    previewWrapper.style.transform = '';
    previewWrapper.style.marginRight = '';
    previewWrapper.style.marginBottom = '';
    previewArea.scrollLeft = 0;
    previewArea.scrollTop = 0;
  }

  // Pinch zoom on preview area (when eraser is off, canvas has pointer-events:none)
  previewArea.addEventListener('touchstart', function(e) {
    if (eraserActive) return;
    if (e.touches.length >= 2) {
      e.preventDefault();
      startPinch(e.touches);
    }
  }, { passive: false });

  previewArea.addEventListener('touchmove', function(e) {
    if (eraserActive) return;
    if (e.touches.length >= 2 && isPinching) {
      e.preventDefault();
      movePinch(e.touches);
    }
  }, { passive: false });

  previewArea.addEventListener('touchend', function(e) {
    if (eraserActive) return;
    if (e.touches.length < 2) isPinching = false;
  }, { passive: false });

  // ══════════════════════════════════════════
  // ── WHITE-OUT CANVAS ──
  // Active only when eraser is ON.
  // One finger = draw white. Two fingers = pinch zoom.
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
      currentStroke = null;
      startPinch(e.touches);
      return;
    }
    // Don't start drawing right after a pinch ends
    if (isPinching) return;
    // One finger → record start
    var p = pos(e);
    currentStroke = { pts: [p], brush: getBrush() };
  }

  function drawMove(e) {
    // Pinch zoom — prevent scroll during pinch
    if (e.touches && e.touches.length >= 2 && isPinching) {
      e.preventDefault();
      movePinch(e.touches);
      currentStroke = null;
      return;
    }
    if (!e.touches) e.preventDefault();
    // If second finger was just added, cancel drawing
    if (e.touches && e.touches.length >= 2) {
      currentStroke = null;
      startPinch(e.touches);
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
  function updateEraserState() {
    eraserToggle.classList.toggle('active', eraserActive);
    thicknessBar.style.display = eraserActive ? 'flex' : 'none';
    whiteoutCanvas.style.pointerEvents = eraserActive ? 'auto' : 'none';
    whiteoutCanvas.style.touchAction = eraserActive ? 'none' : 'auto';
    whiteoutCanvas.style.cursor = eraserActive ? 'crosshair' : 'default';
  }

  eraserToggle.addEventListener('click', function() {
    eraserActive = !eraserActive;
    updateEraserState();
  });

  updateEraserState();

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
  function buildPrintArea() {
    var layout = parseInt(layoutSelect.value, 10);
    printArea.innerHTML = '';
    printArea.className = '';

    if ((contentType === 'pdf' || contentType === 'image') && pageImages.length > 0) {
      // PDF: arrange actual pages onto sheets (e.g. 5 pages @ 4-per-sheet = 2 sheets)
      // Image: N copies of the same image on one sheet
      var pages = contentType === 'image'
        ? Array(layout).fill(pageImages[0])
        : pageImages;

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
    } else {
      // Default label: N copies on one sheet
      var sheet = document.createElement('div');
      sheet.className = 'print-sheet layout-' + layout;
      var html = labelContainer.innerHTML;
      var whiteoutImg = whiteoutCanvas.toDataURL('image/png');
      var hasMarks = strokes.length > 0;

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
    }
  }

  var isPrinting = false;
  printBtn.addEventListener('click', function() {
    if (isPrinting) return;
    isPrinting = true;
    printBtn.disabled = true;
    try {
      buildPrintArea();
      if (window.AndroidBridge && window.AndroidBridge.isAndroid()) {
        if ((contentType === 'pdf' || contentType === 'image') && pageImages.length > 0) {
          // Direct IPP print — no system dialog
          var layout = parseInt(layoutSelect.value, 10);
          window.AndroidBridge.printDirect(JSON.stringify(pageImages), layout, copies);
        } else {
          // Label/HTML — fallback to system dialog
          window.AndroidBridge.print(copies);
        }
      } else {
        window.print();
      }
    } catch (err) {
      console.error('Print failed: ' + err.message);
      // Try system dialog as last resort
      try {
        if (window.AndroidBridge) {
          window.AndroidBridge.print(copies);
        } else {
          window.print();
        }
      } catch (e2) {
        console.error('Fallback print also failed: ' + e2.message);
      }
    } finally {
      // Block re-prints for 5 seconds to prevent duplicate jobs
      // (IPP printing is async and takes time to reach the printer)
      setTimeout(function() {
        isPrinting = false;
        printBtn.disabled = false;
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
    resetZoom();
  }

  function renderPdfPages(pdfData) {
    labelContainer.innerHTML = '<div style="padding:32px;text-align:center;color:#888"><div style="font-size:13px">Loading PDF...</div></div>';
    pageImages = [];
    contentType = 'pdf';
    layoutSelect.value = '1'; // default 1 per sheet for PDFs

    var containerWidth = labelContainer.offsetWidth - 16;
    if (containerWidth <= 0) containerWidth = 400;

    pdfjsLib.getDocument(pdfData).promise.then(function(pdf) {
      function renderPage(num) {
        if (num > pdf.numPages) {
          updatePreviewLayout();
          return;
        }
        pdf.getPage(num).then(function(page) {
          var vp = page.getViewport({ scale: 1 });
          var scale = (containerWidth / vp.width) * 2; // 2x for print quality
          var scaled = page.getViewport({ scale: scale });

          var canvas = document.createElement('canvas');
          canvas.width = scaled.width;
          canvas.height = scaled.height;

          page.render({ canvasContext: canvas.getContext('2d'), viewport: scaled }).promise.then(function() {
            pageImages.push(canvas.toDataURL('image/png'));
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
      imgReader.onload = function() { pageImages = [imgReader.result]; };
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
