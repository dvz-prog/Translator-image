// Tradutor de Imagens — script injetado no WebView.
// Desenha o texto traduzido POR CIMA da imagem, na própria página. O OCR e a tradução
// acontecem no app (PitBridge); nada é baixado nem salvo na galeria.
(() => {
  if (window.__pitLoaded) return;
  window.__pitLoaded = true;

  const CSS = [
    '.pit-root{position:absolute;z-index:2147483000;pointer-events:none;margin:0;padding:0;box-sizing:border-box}',
    '.pit-layer{position:absolute;inset:0;overflow:hidden;pointer-events:none}',
    '.pit-block{position:absolute;display:flex;align-items:center;justify-content:center;text-align:center;',
    'overflow:hidden;border-radius:8px;box-sizing:border-box;line-height:1.15;',
    'font-family:"Comic Sans MS","Segoe UI",Arial,sans-serif;font-weight:700;pointer-events:none}',
    '.pit-block span{display:block;width:100%;padding:2px 4px;box-sizing:border-box;word-break:break-word;overflow-wrap:anywhere}',
    '.pit-badge{position:absolute;top:6px;left:6px;max-width:80%;padding:4px 8px;background:rgba(20,20,20,.85);',
    'color:#fff;font:12px/1.3 system-ui,sans-serif;border-radius:999px;pointer-events:none;transition:opacity .4s}',
    '.pit-badge.pit-click{pointer-events:auto;cursor:pointer;background:#b3261e}',
    '.pit-toggle{position:absolute;top:6px;right:6px;width:30px;height:30px;padding:0;border:0;border-radius:50%;',
    'background:rgba(20,20,20,.75);color:#fff;font:700 14px system-ui,sans-serif;pointer-events:auto;opacity:.4}'
  ].join('');
  const styleEl = document.createElement('style');
  styleEl.textContent = CSS;
  (document.head || document.documentElement).appendChild(styleEl);

  const MIN_W = 120, MIN_H = 70;
  const MAX_PARALLEL = 2;

  let auto = false;
  const recs = new WeakMap();
  const active = new Set();
  const observed = new WeakSet();
  const suppressed = new WeakSet();
  const queue = [];
  let running = 0;

  // ---------- ponte com o app ----------
  const pending = new Map();
  let seq = 0;
  window.__pitDone = (id, blocks) => {
    const p = pending.get(id);
    if (p) { pending.delete(id); clearTimeout(p.timer); p.ok(blocks); }
  };
  window.__pitFail = (id, msg) => {
    const p = pending.get(id);
    if (p) { pending.delete(id); clearTimeout(p.timer); p.no(new Error(msg)); }
  };

  async function callNative(src) {
    let dataUrl = null;
    if (src.startsWith('data:')) {
      dataUrl = src;
    } else if (src.startsWith('blob:')) {
      const blob = await (await fetch(src)).blob();
      dataUrl = await new Promise((ok, no) => {
        const fr = new FileReader();
        fr.onload = () => ok(fr.result);
        fr.onerror = () => no(fr.error);
        fr.readAsDataURL(blob);
      });
    }
    return new Promise((ok, no) => {
      const id = 'r' + (++seq);
      const timer = setTimeout(() => {
        if (pending.delete(id)) no(new Error('Tempo esgotado'));
      }, 120000);
      pending.set(id, { ok, no, timer });
      try {
        if (dataUrl) window.PitBridge.translateData(id, dataUrl);
        else window.PitBridge.translate(id, src, location.href);
      } catch (e) {
        pending.delete(id);
        clearTimeout(timer);
        no(e);
      }
    });
  }

  // ---------- detecção de imagens ----------
  const io = new IntersectionObserver((entries) => {
    for (const e of entries) if (e.isIntersecting && auto) request(e.target);
  }, { rootMargin: '400px 0px' });

  function observe(img) {
    if (observed.has(img)) return;
    observed.add(img);
    io.observe(img);
    img.addEventListener('load', () => { if (auto && nearViewport(img, 400)) request(img); });
  }

  function scan() {
    document.querySelectorAll('img').forEach(observe);
  }

  let scanTimer = null;
  new MutationObserver(() => {
    clearTimeout(scanTimer);
    scanTimer = setTimeout(scan, 300);
  }).observe(document.documentElement, { childList: true, subtree: true });

  function nearViewport(img, margin) {
    const r = img.getBoundingClientRect();
    return r.bottom > -margin && r.top < innerHeight + margin && r.right > 0 && r.left < innerWidth;
  }

  function eligible(img) {
    if (!img.complete || !img.naturalWidth) return false;
    const r = img.getBoundingClientRect();
    return r.width >= MIN_W && r.height >= MIN_H;
  }

  // ---------- fila ----------
  function request(img, force = false, manual = false) {
    const src = img.currentSrc || img.src;
    if (!src) return;
    if (!force && suppressed.has(img)) return;
    if (force) suppressed.delete(img);
    let r = recs.get(img);
    if (r && r.src === src && !force) return;
    if (!force && !eligible(img)) return;
    if (r) destroy(r);
    r = { img, src, state: 'queued', manual: manual || force, blocks: [], hidden: false, dead: false, w: 0, h: 0 };
    recs.set(img, r);
    active.add(r);
    makeRoot(r);
    setBadge(r, '⏳ Na fila…');
    position(r);
    queue.push(r);
    pump();
  }

  function pump() {
    while (running < MAX_PARALLEL && queue.length) {
      const r = queue.shift();
      if (r.dead) continue;
      running++;
      run(r).finally(() => { running--; pump(); });
    }
  }

  async function run(r) {
    r.state = 'loading';
    setBadge(r, '🌐 Traduzindo…');
    try {
      const blocks = await callNative(r.src);
      if (r.dead) return;
      r.blocks = blocks || [];
      r.state = 'done';
      render(r);
      if (r.blocks.length) setBadge(r, '✓ ' + r.blocks.length + ' trecho(s)', 1800);
      else setBadge(r, 'Nenhum texto encontrado', 2500);
    } catch (e) {
      if (r.dead) return;
      r.state = 'error';
      setBadge(r, '⚠ ' + String((e && e.message) || e).slice(0, 90) + ' — toque para tentar de novo', 0, true);
    }
  }

  // ---------- overlay ----------
  function makeRoot(r) {
    const root = document.createElement('div');
    root.className = 'pit-root';
    const layer = document.createElement('div');
    layer.className = 'pit-layer';
    const badge = document.createElement('div');
    badge.className = 'pit-badge';
    badge.style.display = 'none';
    const toggle = document.createElement('button');
    toggle.className = 'pit-toggle';
    toggle.type = 'button';
    toggle.textContent = 'T';
    toggle.style.display = 'none';
    toggle.addEventListener('click', (ev) => {
      ev.preventDefault(); ev.stopPropagation();
      r.hidden = !r.hidden;
      layer.style.visibility = r.hidden ? 'hidden' : 'visible';
    });
    badge.addEventListener('click', (ev) => {
      if (r.state === 'error') { ev.preventDefault(); ev.stopPropagation(); request(r.img, true, true); }
    });
    root.append(layer, badge, toggle);
    document.documentElement.appendChild(root);
    r.root = root; r.layer = layer; r.badge = badge; r.toggle = toggle;
  }

  function setBadge(r, text, hideAfter = 0, clickable = false) {
    const b = r.badge;
    if (!b) return;
    clearTimeout(r.badgeTimer);
    b.textContent = text;
    b.style.display = 'block';
    b.style.opacity = '1';
    b.classList.toggle('pit-click', clickable);
    if (hideAfter) {
      r.badgeTimer = setTimeout(() => {
        b.style.opacity = '0';
        setTimeout(() => { if (b.style.opacity === '0') b.style.display = 'none'; }, 450);
      }, hideAfter);
    }
  }

  function render(r) {
    r.layer.textContent = '';
    r.els = [];
    for (const b of r.blocks) {
      const el = document.createElement('div');
      el.className = 'pit-block';
      el.style.background = b.bg;
      el.style.color = b.fg;
      el.style.boxShadow = '0 0 4px 2px ' + b.bg;
      const sp = document.createElement('span');
      sp.textContent = b.text;
      el.appendChild(sp);
      r.layer.appendChild(el);
      r.els.push({ el, sp, b });
    }
    r.toggle.style.display = r.blocks.length ? 'block' : 'none';
    r.w = r.h = 0;
    position(r);
  }

  function position(r) {
    const img = r.img;
    if (!img.isConnected) return;
    const rect = img.getBoundingClientRect();
    if (rect.width < 2 || rect.height < 2) { r.root.style.display = 'none'; return; }
    r.root.style.display = 'block';
    const s = r.root.style;
    s.left = (rect.left + scrollX) + 'px';
    s.top = (rect.top + scrollY) + 'px';
    s.width = rect.width + 'px';
    s.height = rect.height + 'px';
    const w = Math.round(rect.width), h = Math.round(rect.height);
    if ((w !== r.w || h !== r.h) && r.els) {
      r.w = w; r.h = h;
      fitAll(r, w, h);
    }
  }

  function fitAll(r, LW, LH) {
    for (const { el, sp, b } of r.els) {
      let scale = 1;
      for (let k = 0; k < 4; k++) {
        const w = Math.min(LW, b.w * LW * scale);
        const h = Math.min(LH, b.h * LH * scale);
        const cx = (b.x + b.w / 2) * LW, cy = (b.y + b.h / 2) * LH;
        const left = Math.min(Math.max(0, cx - w / 2), LW - w);
        const top = Math.min(Math.max(0, cy - h / 2), LH - h);
        el.style.left = left + 'px'; el.style.top = top + 'px';
        el.style.width = w + 'px'; el.style.height = h + 'px';
        if (fitFont(el, sp) >= 11) break;
        scale *= 1.3;
      }
    }
  }

  function fitFont(el, sp) {
    let lo = 6, hi = Math.min(44, Math.max(8, el.clientHeight));
    while (lo < hi) {
      const mid = Math.ceil((lo + hi) / 2);
      sp.style.fontSize = mid + 'px';
      if (sp.offsetHeight <= el.clientHeight) lo = mid; else hi = mid - 1;
    }
    sp.style.fontSize = lo + 'px';
    return lo;
  }

  function destroy(r) {
    r.dead = true;
    clearTimeout(r.badgeTimer);
    if (r.root) r.root.remove();
    active.delete(r);
    if (recs.get(r.img) === r) recs.delete(r.img);
  }

  // ---------- manter o overlay colado na imagem ----------
  let raf = 0;
  const schedule = () => {
    if (raf) return;
    raf = requestAnimationFrame(() => { raf = 0; for (const r of active) position(r); });
  };
  addEventListener('scroll', schedule, { capture: true, passive: true });
  addEventListener('resize', schedule, { passive: true });

  setInterval(() => {
    for (const r of [...active]) {
      if (!r.img.isConnected) { destroy(r); continue; }
      const cur = r.img.currentSrc || r.img.src;
      if (cur && cur !== r.src && r.img.complete) {
        const manual = r.manual, img = r.img;
        destroy(r);
        if (auto || manual) request(img, false, manual);
        continue;
      }
      position(r);
    }
  }, 400);

  // ---------- comandos do app ----------
  window.__pitCmd = (type, arg) => {
    if (type === 'auto') {
      const was = auto;
      auto = !!arg;
      if (auto && !was) {
        scan();
        document.querySelectorAll('img').forEach((img) => { if (nearViewport(img, 400)) request(img); });
      }
      if (!auto && was) {
        for (const r of [...active]) if (r.state === 'queued' && !r.manual) destroy(r);
      }
    } else if (type === 'translateAll') {
      document.querySelectorAll('img').forEach((img) => {
        suppressed.delete(img);
        if (nearViewport(img, 1500)) request(img, false, true);
      });
    } else if (type === 'translateSrc') {
      document.querySelectorAll('img').forEach((img) => {
        if ((img.currentSrc || img.src) === arg) request(img, true, true);
      });
    } else if (type === 'clear') {
      for (const r of [...active]) { suppressed.add(r.img); destroy(r); }
      queue.length = 0;
    } else if (type === 'toggleAll') {
      for (const r of active) {
        r.hidden = !r.hidden;
        if (r.layer) r.layer.style.visibility = r.hidden ? 'hidden' : 'visible';
      }
    }
  };

  scan();
})();
