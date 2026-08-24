(() => {
  "use strict";

  const DATA_URL = "data/ecosystem.json";
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  const els = {
    canvas: $("#graph-canvas"),
    loading: $("#loading-state"),
    error: $("#error-state"),
    retry: $("#retry-button"),
    nodeCount: $("#node-count"),
    edgeCount: $("#edge-count"),
    integratedCount: $("#integrated-count"),
    visibleSummary: $("#visible-summary"),
    fieldFilters: $("#field-filters"),
    typeFilters: $("#type-filters"),
    sizeMetric: $("#size-metric"),
    metricNote: $("#metric-note"),
    yearRange: $("#year-range"),
    yearOutput: $("#year-output"),
    searchInput: $("#search-input"),
    searchResults: $("#search-results"),
    labels: $("#semantic-labels"),
    freshness: $("#data-freshness"),
    detailPanel: $("#detail-panel"),
    detailEmpty: $("#detail-empty"),
    detailContent: $("#detail-content"),
    detailGlyph: $("#detail-glyph"),
    detailType: $("#detail-type"),
    detailTitle: $("#detail-title"),
    detailStatus: $("#detail-status"),
    detailDescription: $("#detail-description"),
    detailScore: $("#detail-score"),
    detailScoreBars: $("#detail-score-bars"),
    detailMetrics: $("#detail-metrics"),
    architectureSection: $("#architecture-section"),
    detailArchitecture: $("#detail-architecture"),
    validationScore: $("#validation-score"),
    validationFill: $("#validation-meter-fill"),
    validationLevel: $("#validation-level"),
    validationSummary: $("#validation-summary"),
    relationCount: $("#relation-count"),
    detailRelations: $("#detail-relations"),
    detailLinks: $("#detail-links"),
    detailClose: $("#panel-close"),
    methodDialog: $("#methodology-dialog"),
    methodologyCopy: $("#methodology-copy"),
    gestureHint: $("#gesture-hint"),
    autoRotate: $("#auto-rotate")
  };

  const ctx = els.canvas.getContext("2d", { alpha: false, desynchronized: true });
  let data = null;
  let nodeMap = new Map();
  let fieldMap = new Map();
  let typeMap = new Map();
  let edges = [];
  let visibleNodes = [];
  let visibleEdges = [];
  let frameId = 0;
  let width = 1;
  let height = 1;
  let dpr = 1;
  let lastTime = performance.now();

  const state = {
    layout: "semantic",
    sizeMetric: "ecosystemValue",
    activeFields: new Set(),
    activeTypes: new Set(),
    maxYear: 2026,
    selected: null,
    hovered: null,
    yaw: -0.38,
    pitch: -0.12,
    zoom: 1,
    autoRotate: !reducedMotion,
    dragging: false,
    pinching: false,
    pointers: new Map(),
    pinchDistance: 0,
    pinchZoom: 1,
    moved: false,
    pointerId: null,
    startX: 0,
    startY: 0,
    lastX: 0,
    lastY: 0,
    lastInteraction: 0
  };

  const metricNotes = {
    ecosystemValue: "Punteggio editoriale: influenza, evidenza, utilità e deployability.",
    scaleScore: "Scala organizzativa normalizzata; non è una market cap.",
    stars: "GitHub stars del repository ufficiale, con scala logaritmica.",
    downloads: "Download totali esposti dall'API Hugging Face, scala logaritmica.",
    validation: "Forza dell'evidenza: benchmark → reale → umano → clinico.",
    openness: "Accesso a codice, pesi, training e condizioni di riuso."
  };

  const typeOrder = ["project", "model", "company", "research", "product", "dataset", "architecture", "runtime"];
  const typeRows = new Map(typeOrder.map((type, index) => [type, index]));

  function hashNumber(value) {
    let hash = 2166136261;
    const text = String(value);
    for (let i = 0; i < text.length; i += 1) {
      hash ^= text.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0) / 4294967295;
  }

  function seeded(id, salt = "") {
    return hashNumber(`${id}:${salt}`);
  }

  function hexToRgb(hex) {
    const clean = hex.replace("#", "");
    const value = Number.parseInt(clean.length === 3 ? clean.split("").map((x) => x + x).join("") : clean, 16);
    return { r: (value >> 16) & 255, g: (value >> 8) & 255, b: value & 255 };
  }

  function rgba(hex, alpha) {
    const { r, g, b } = hexToRgb(hex);
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
  }

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  }

  function lerp(a, b, t) {
    return a + (b - a) * t;
  }

  function formatNumber(value) {
    if (value == null || Number.isNaN(Number(value))) return "—";
    const number = Number(value);
    if (number >= 1_000_000) return `${(number / 1_000_000).toFixed(number >= 10_000_000 ? 0 : 1)}M`;
    if (number >= 1_000) return `${(number / 1_000).toFixed(number >= 100_000 ? 0 : 1)}K`;
    return new Intl.NumberFormat("it-IT").format(number);
  }

  function formatDate(value) {
    if (!value) return "data non disponibile";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "short", year: "numeric" }).format(date);
  }

  function fieldColor(node) {
    return fieldMap.get(node.primaryField)?.color || "#c8ff42";
  }

  function typeLabel(node) {
    return typeMap.get(node.type)?.label || node.type;
  }

  async function loadData() {
    els.loading.classList.remove("is-hidden");
    els.error.hidden = true;
    try {
      const response = await fetch(DATA_URL, { cache: "no-cache" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const payload = await response.json();
      initialize(payload);
      els.loading.classList.add("is-hidden");
      window.setTimeout(() => { els.loading.hidden = true; }, 420);
    } catch (error) {
      console.error("Atlas data error", error);
      els.loading.hidden = true;
      els.error.hidden = false;
    }
  }

  function initialize(payload) {
    data = payload;
    fieldMap = new Map(data.fields.map((field) => [field.id, field]));
    typeMap = new Map(data.types.map((type) => [type.id, type]));
    state.activeFields = new Set(data.fields.map((field) => field.id));
    state.activeTypes = new Set(data.types.map((type) => type.id));

    data.nodes.forEach((node) => {
      const seed = seeded(node.id);
      Object.assign(node, {
        x: (seed - 0.5) * 300,
        y: (seeded(node.id, "y") - 0.5) * 300,
        z: (seeded(node.id, "z") - 0.5) * 300,
        tx: 0,
        ty: 0,
        tz: 0,
        sx: -9999,
        sy: -9999,
        sr: 0,
        depth: 0,
        visible: true
      });
    });

    nodeMap = new Map(data.nodes.map((node) => [node.id, node]));
    edges = data.edges
      .map((edge, index) => ({ ...edge, index, sourceNode: nodeMap.get(edge.source), targetNode: nodeMap.get(edge.target) }))
      .filter((edge) => edge.sourceNode && edge.targetNode);

    const years = data.nodes.map((node) => node.year).filter((year) => Number.isFinite(year) && year >= 2010);
    const yearMax = Math.max(2026, ...years);
    els.yearRange.max = String(yearMax);
    els.yearRange.value = String(yearMax);
    els.yearOutput.value = String(yearMax);
    state.maxYear = yearMax;
    els.autoRotate.classList.toggle("is-active", state.autoRotate);
    els.autoRotate.setAttribute("aria-pressed", String(state.autoRotate));

    buildControls();
    bindEvents();
    applyLayout(true);
    applyFilters();
    updateHeader();
    resize();
    createSemanticLabels();
    els.methodologyCopy.textContent = data.meta.methodology;
    if (window.matchMedia("(pointer: coarse)").matches) els.gestureHint.innerHTML = '<span aria-hidden="true">↔</span> Trascina per esplorare · pizzica per zoom';
    lastTime = performance.now();
    frameId = requestAnimationFrame(render);
  }

  function buildControls() {
    const countsByField = new Map(data.fields.map((field) => [field.id, 0]));
    data.nodes.forEach((node) => (node.fields || [node.primaryField]).forEach((field) => countsByField.set(field, (countsByField.get(field) || 0) + 1)));
    els.fieldFilters.innerHTML = data.fields.map((field) => `
      <button class="field-filter is-active" type="button" data-field="${field.id}" style="color:${field.color}" aria-pressed="true">
        <i aria-hidden="true"></i><span>${field.label}</span><small>${countsByField.get(field.id) || 0}</small>
      </button>`).join("");

    els.typeFilters.innerHTML = typeOrder
      .filter((type) => typeMap.has(type))
      .map((type) => {
        const count = data.nodes.filter((node) => node.type === type).length;
        return `<button class="type-filter is-active" type="button" data-type="${type}" aria-pressed="true">${typeMap.get(type).label} · ${count}</button>`;
      }).join("");
  }

  function bindEvents() {
    if (els.canvas.dataset.bound) return;
    els.canvas.dataset.bound = "true";
    const resizeObserver = new ResizeObserver(resize);
    resizeObserver.observe(els.canvas);

    $$("[data-layout]").forEach((button) => button.addEventListener("click", () => {
      state.layout = button.dataset.layout;
      $$("[data-layout]").forEach((item) => item.classList.toggle("is-active", item === button));
      applyLayout();
    }));

    els.fieldFilters.addEventListener("click", (event) => {
      const button = event.target.closest("[data-field]");
      if (!button) return;
      const field = button.dataset.field;
      if (state.activeFields.has(field)) state.activeFields.delete(field);
      else state.activeFields.add(field);
      if (!state.activeFields.size) state.activeFields.add(field);
      syncFilterButtons();
      applyFilters();
    });

    els.typeFilters.addEventListener("click", (event) => {
      const button = event.target.closest("[data-type]");
      if (!button) return;
      const type = button.dataset.type;
      if (state.activeTypes.has(type)) state.activeTypes.delete(type);
      else state.activeTypes.add(type);
      if (!state.activeTypes.size) state.activeTypes.add(type);
      syncFilterButtons();
      applyFilters();
    });

    $("#toggle-fields").addEventListener("click", (event) => {
      const allActive = state.activeFields.size === data.fields.length;
      if (allActive) {
        const melinaFields = new Set(data.nodes.filter((node) => node.inMelina).flatMap((node) => node.fields || [node.primaryField]));
        state.activeFields = melinaFields;
        event.currentTarget.textContent = "Tutti";
      } else {
        state.activeFields = new Set(data.fields.map((field) => field.id));
        event.currentTarget.textContent = "Solo Melina";
      }
      syncFilterButtons();
      applyFilters();
    });

    els.sizeMetric.addEventListener("change", () => {
      state.sizeMetric = els.sizeMetric.value;
      els.metricNote.textContent = metricNotes[state.sizeMetric];
    });

    els.yearRange.addEventListener("input", () => {
      state.maxYear = Number(els.yearRange.value);
      els.yearOutput.value = String(state.maxYear);
      applyFilters();
    });

    els.searchInput.addEventListener("input", updateSearchResults);
    els.searchInput.addEventListener("focus", updateSearchResults);
    els.searchInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        const first = $(".search-result", els.searchResults);
        if (first) first.click();
      }
      if (event.key === "Escape") closeSearch();
    });

    els.searchResults.addEventListener("click", (event) => {
      const button = event.target.closest("[data-node-id]");
      if (!button) return;
      revealAndSelect(nodeMap.get(button.dataset.nodeId));
      closeSearch();
    });

    document.addEventListener("click", (event) => {
      if (!event.target.closest(".search-section")) closeSearch();
    });

    document.addEventListener("keydown", (event) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        els.searchInput.focus();
        els.searchInput.select();
      }
      if (event.key === "Escape" && document.activeElement !== els.searchInput) selectNode(null);
    });

    els.canvas.addEventListener("pointerdown", onPointerDown);
    els.canvas.addEventListener("pointermove", onPointerMove);
    els.canvas.addEventListener("pointerup", onPointerUp);
    els.canvas.addEventListener("pointercancel", onPointerUp);
    els.canvas.addEventListener("pointerleave", () => { if (!state.dragging) state.hovered = null; });
    els.canvas.addEventListener("wheel", onWheel, { passive: false });
    els.canvas.addEventListener("keydown", onCanvasKeydown);

    $("#reset-view").addEventListener("click", resetAll);
    $("#fit-view").addEventListener("click", resetCamera);
    els.autoRotate.addEventListener("click", () => setAutoRotate(!state.autoRotate));
    els.detailClose.addEventListener("click", () => selectNode(null));
    $("#help-button").addEventListener("click", () => els.methodDialog.showModal());
    els.detailRelations.addEventListener("click", (event) => {
      const button = event.target.closest("[data-node-id]");
      if (button) revealAndSelect(nodeMap.get(button.dataset.nodeId));
    });

    window.setTimeout(() => els.gestureHint.classList.add("is-hidden"), 6500);
  }

  function resetAll() {
    state.activeFields = new Set(data.fields.map((field) => field.id));
    state.activeTypes = new Set(data.types.map((type) => type.id));
    state.maxYear = Number(els.yearRange.max);
    els.yearRange.value = String(state.maxYear);
    els.yearOutput.value = String(state.maxYear);
    $("#toggle-fields").textContent = "Solo Melina";
    syncFilterButtons();
    applyFilters();
    resetCamera();
  }

  function syncFilterButtons() {
    $$("[data-field]", els.fieldFilters).forEach((button) => {
      const active = state.activeFields.has(button.dataset.field);
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-pressed", String(active));
    });
    $$("[data-type]", els.typeFilters).forEach((button) => {
      const active = state.activeTypes.has(button.dataset.type);
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-pressed", String(active));
    });
  }

  function updateSearchResults() {
    const query = els.searchInput.value.trim().toLocaleLowerCase("it");
    if (!query) {
      els.searchResults.hidden = true;
      return;
    }
    const terms = query.split(/\s+/);
    const results = data.nodes
      .map((node) => {
        const haystack = [node.label, node.description, node.status, node.license, ...(node.architecture || []), ...(node.fields || [])].join(" ").toLocaleLowerCase("it");
        const matches = terms.every((term) => haystack.includes(term));
        let score = matches ? 1 : 0;
        if (node.label.toLocaleLowerCase("it").startsWith(query)) score += 5;
        else if (node.label.toLocaleLowerCase("it").includes(query)) score += 3;
        if (node.inMelina) score += 0.4;
        return { node, score };
      })
      .filter((result) => result.score > 0)
      .sort((a, b) => b.score - a.score || b.node.ecosystemValue - a.node.ecosystemValue)
      .slice(0, 9);

    els.searchResults.innerHTML = results.length
      ? results.map(({ node }) => `<button class="search-result" type="button" role="option" data-node-id="${node.id}"><i style="color:${fieldColor(node)}"></i><span>${node.label}</span><small>${typeLabel(node)}</small></button>`).join("")
      : `<div class="search-no-result">Nessuna entità trovata</div>`;
    els.searchResults.hidden = false;
  }

  function closeSearch() {
    els.searchResults.hidden = true;
  }

  function revealAndSelect(node) {
    if (!node) return;
    (node.fields || [node.primaryField]).forEach((field) => state.activeFields.add(field));
    state.activeTypes.add(node.type);
    if (node.year > state.maxYear) {
      state.maxYear = node.year;
      els.yearRange.value = String(node.year);
      els.yearOutput.value = String(node.year);
    }
    syncFilterButtons();
    applyFilters();
    selectNode(node);
  }

  function semanticCenter(fieldId) {
    const index = Math.max(0, data.fields.findIndex((field) => field.id === fieldId));
    const angle = (index / data.fields.length) * Math.PI * 2 - Math.PI * 0.63;
    const ring = index % 2 === 0 ? 200 : 245;
    return {
      x: Math.cos(angle) * ring,
      y: Math.sin(angle) * ring * 0.66,
      z: Math.sin(angle * 1.7) * 125
    };
  }

  function applyLayout(immediate = false) {
    data.nodes.forEach((node) => {
      const spreadA = seeded(node.id, "a") * Math.PI * 2;
      const spreadR = 22 + seeded(node.id, "r") * 76;
      const jitterZ = (seeded(node.id, "jz") - 0.5) * 115;
      let target;

      if (state.layout === "timeline") {
        const year = clamp(Number(node.year) || 2017, 2017, Number(els.yearRange.max || 2026));
        const t = (year - 2017) / Math.max(1, Number(els.yearRange.max || 2026) - 2017);
        const row = typeRows.get(node.type) ?? 4;
        target = {
          x: lerp(-360, 360, t) + (seeded(node.id, "tx") - 0.5) * 32,
          y: (row - 3.5) * 52 + (seeded(node.id, "ty") - 0.5) * 26,
          z: (data.fields.findIndex((field) => field.id === node.primaryField) - 3) * 54 + jitterZ * 0.4
        };
      } else if (state.layout === "openness") {
        const row = typeRows.get(node.type) ?? 4;
        const validation = node.validation?.score ?? 30;
        target = {
          x: lerp(-350, 350, clamp(node.openness ?? 30, 0, 100) / 100),
          y: lerp(220, -220, validation / 100) + (seeded(node.id, "oy") - 0.5) * 35,
          z: (row - 3.5) * 65 + (seeded(node.id, "oz") - 0.5) * 55
        };
      } else {
        const center = semanticCenter(node.primaryField);
        const typeLift = node.type === "company" || node.type === "research" ? 38 : node.type === "architecture" ? -22 : 0;
        target = {
          x: center.x + Math.cos(spreadA) * spreadR,
          y: center.y + Math.sin(spreadA) * spreadR + typeLift,
          z: center.z + jitterZ
        };
      }

      node.tx = target.x;
      node.ty = target.y;
      node.tz = target.z;
      if (immediate) {
        node.x = node.tx;
        node.y = node.ty;
        node.z = node.tz;
      }
    });
    createSemanticLabels();
  }

  function applyFilters() {
    visibleNodes = data.nodes.filter((node) => {
      const fields = node.fields || [node.primaryField];
      const fieldVisible = fields.some((field) => state.activeFields.has(field));
      const typeVisible = state.activeTypes.has(node.type);
      const yearVisible = !Number.isFinite(node.year) || node.year <= state.maxYear;
      node.visible = fieldVisible && typeVisible && yearVisible;
      return node.visible;
    });
    visibleEdges = edges.filter((edge) => edge.sourceNode.visible && edge.targetNode.visible);
    if (state.selected && !state.selected.visible) selectNode(null);
    const fieldLabel = state.activeFields.size === data.fields.length ? "tutti i campi" : `${state.activeFields.size} campi`;
    els.visibleSummary.textContent = `${visibleNodes.length} nodi visibili · ${fieldLabel} · fino al ${state.maxYear}`;
  }

  function updateHeader() {
    els.nodeCount.textContent = data.nodes.length;
    els.edgeCount.textContent = edges.length;
    els.integratedCount.textContent = data.nodes.filter((node) => node.inMelina).length;
    els.freshness.textContent = `Aggiornato ${formatDate(data.meta.updatedAt)}`;
  }

  function createSemanticLabels() {
    if (!data || !els.labels) return;
    if (state.layout !== "semantic") {
      els.labels.innerHTML = "";
      return;
    }
    els.labels.innerHTML = data.fields.map((field) => `<span class="semantic-label" data-label-field="${field.id}" style="--label-color:${field.color}">${field.label}</span>`).join("");
  }

  function resize() {
    const rect = els.canvas.getBoundingClientRect();
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    width = Math.max(1, rect.width);
    height = Math.max(1, rect.height);
    els.canvas.width = Math.round(width * dpr);
    els.canvas.height = Math.round(height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function transformPoint(x, y, z) {
    const cosY = Math.cos(state.yaw);
    const sinY = Math.sin(state.yaw);
    const rx = x * cosY - z * sinY;
    const rz0 = x * sinY + z * cosY;
    const cosX = Math.cos(state.pitch);
    const sinX = Math.sin(state.pitch);
    const ry = y * cosX - rz0 * sinX;
    const rz = y * sinX + rz0 * cosX;
    const cameraDistance = 720 / state.zoom;
    const focal = Math.min(width, height) * 0.92;
    const perspective = focal / Math.max(180, cameraDistance - rz);
    return {
      x: width * 0.5 + rx * perspective,
      y: height * 0.5 + ry * perspective,
      z: rz,
      scale: perspective,
      visible: cameraDistance - rz > 120
    };
  }

  function metricValue(node) {
    if (state.sizeMetric === "validation") return node.validation?.score ?? 0;
    if (state.sizeMetric === "stars") return Number(node.metrics?.stars) || 0;
    if (state.sizeMetric === "downloads") return Number(node.metrics?.downloads) || 0;
    return Number(node[state.sizeMetric]) || 0;
  }

  function nodeRadius(node) {
    const value = metricValue(node);
    let normalized = 0;
    if (state.sizeMetric === "stars" || state.sizeMetric === "downloads") {
      const max = Math.max(1, ...visibleNodes.map(metricValue));
      normalized = Math.log10(value + 1) / Math.log10(max + 1);
    } else {
      normalized = clamp(value / 100, 0, 1);
    }
    const typeBoost = node.type === "company" || node.type === "research" ? 1.6 : node.type === "architecture" ? -0.6 : 0;
    return 3.4 + normalized * 7.6 + typeBoost;
  }

  function drawBackground(time) {
    ctx.fillStyle = "#070908";
    ctx.fillRect(0, 0, width, height);
    const gradient = ctx.createRadialGradient(width * 0.52, height * 0.46, 0, width * 0.52, height * 0.46, Math.max(width, height) * 0.72);
    gradient.addColorStop(0, "rgba(37, 55, 45, 0.18)");
    gradient.addColorStop(0.48, "rgba(11, 16, 13, 0.08)");
    gradient.addColorStop(1, "rgba(7, 9, 8, 0)");
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, width, height);

    ctx.save();
    for (let i = 0; i < 60; i += 1) {
      const x = seeded(i, "star-x") * width;
      const y = seeded(i, "star-y") * height;
      const flicker = 0.04 + (Math.sin(time * 0.0004 + i) + 1) * 0.025;
      ctx.fillStyle = `rgba(210, 240, 220, ${flicker})`;
      ctx.fillRect(x, y, 1, 1);
    }
    ctx.restore();
  }

  function projectNodes() {
    visibleNodes.forEach((node) => {
      const point = transformPoint(node.x, node.y, node.z);
      node.sx = point.x;
      node.sy = point.y;
      node.depth = point.z;
      node.projectionScale = point.scale;
      node.sr = clamp(nodeRadius(node) * point.scale, 2.2, 17);
      node.onScreen = point.visible && point.x > -60 && point.x < width + 60 && point.y > -60 && point.y < height + 60;
    });
  }

  function drawEdges(time) {
    const selected = state.selected;
    const hovered = state.hovered;
    visibleEdges
      .slice()
      .sort((a, b) => ((a.sourceNode.depth + a.targetNode.depth) - (b.sourceNode.depth + b.targetNode.depth)))
      .forEach((edge) => {
        const a = edge.sourceNode;
        const b = edge.targetNode;
        if (!a.onScreen && !b.onScreen) return;
        const highlighted = selected && (a === selected || b === selected);
        const hoverHighlighted = hovered && (a === hovered || b === hovered);
        const integrated = edge.type === "integrates" || edge.source === "melina";
        const alpha = highlighted ? 0.52 : hoverHighlighted ? 0.28 : integrated ? 0.14 : 0.055 + (edge.weight || 1) * 0.009;
        const color = highlighted ? fieldColor(selected) : integrated ? "#c8ff42" : "#9fb2a5";
        ctx.beginPath();
        ctx.moveTo(a.sx, a.sy);
        const dx = b.sx - a.sx;
        const dy = b.sy - a.sy;
        const bend = (seeded(edge.index, "bend") - 0.5) * Math.min(38, Math.hypot(dx, dy) * 0.15);
        const mx = (a.sx + b.sx) * 0.5 - (dy / (Math.hypot(dx, dy) || 1)) * bend;
        const my = (a.sy + b.sy) * 0.5 + (dx / (Math.hypot(dx, dy) || 1)) * bend;
        ctx.quadraticCurveTo(mx, my, b.sx, b.sy);
        ctx.strokeStyle = rgba(color, alpha);
        ctx.lineWidth = highlighted ? 1.25 : integrated ? 0.85 : 0.55;
        ctx.stroke();

        if ((highlighted || integrated) && !reducedMotion) {
          const phase = (time * 0.00014 * (1 + (edge.index % 3) * 0.15) + seeded(edge.index, "phase")) % 1;
          const oneMinus = 1 - phase;
          const px = oneMinus * oneMinus * a.sx + 2 * oneMinus * phase * mx + phase * phase * b.sx;
          const py = oneMinus * oneMinus * a.sy + 2 * oneMinus * phase * my + phase * phase * b.sy;
          ctx.beginPath();
          ctx.arc(px, py, highlighted ? 1.8 : 1.1, 0, Math.PI * 2);
          ctx.fillStyle = rgba(color, highlighted ? 0.9 : 0.45);
          ctx.fill();
        }
      });
  }

  function drawNodeShape(node, radius, color, alpha) {
    const isOrganization = node.type === "company" || node.type === "research";
    const isSquare = node.type === "dataset" || node.type === "runtime";
    ctx.beginPath();
    if (isOrganization) {
      ctx.moveTo(node.sx, node.sy - radius);
      ctx.lineTo(node.sx + radius, node.sy);
      ctx.lineTo(node.sx, node.sy + radius);
      ctx.lineTo(node.sx - radius, node.sy);
      ctx.closePath();
    } else if (isSquare) {
      const side = radius * 1.62;
      ctx.roundRect(node.sx - side / 2, node.sy - side / 2, side, side, Math.max(1, radius * 0.2));
    } else if (node.type === "architecture") {
      for (let i = 0; i < 6; i += 1) {
        const angle = -Math.PI / 2 + i * Math.PI / 3;
        const x = node.sx + Math.cos(angle) * radius;
        const y = node.sy + Math.sin(angle) * radius;
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      }
      ctx.closePath();
    } else {
      ctx.arc(node.sx, node.sy, radius, 0, Math.PI * 2);
    }
    ctx.fillStyle = rgba(color, alpha * 0.22);
    ctx.fill();
    ctx.strokeStyle = rgba(color, alpha);
    ctx.lineWidth = node.inMelina ? 1.35 : 0.85;
    ctx.stroke();
  }

  function drawNodes(time) {
    const nodes = visibleNodes.filter((node) => node.onScreen).sort((a, b) => a.depth - b.depth);
    const connected = state.selected
      ? new Set(visibleEdges.filter((edge) => edge.sourceNode === state.selected || edge.targetNode === state.selected).flatMap((edge) => [edge.sourceNode, edge.targetNode]))
      : null;

    nodes.forEach((node) => {
      const color = fieldColor(node);
      const selected = node === state.selected;
      const hovered = node === state.hovered;
      const dimmed = connected && !connected.has(node);
      const depthAlpha = clamp(0.25 + (node.depth + 380) / 900, 0.2, 0.92);
      const alpha = dimmed ? 0.16 : selected || hovered ? 1 : depthAlpha;
      const pulse = selected && !reducedMotion ? 1 + Math.sin(time * 0.004) * 0.08 : 1;
      const radius = node.sr * pulse;

      if (selected || hovered || node.inMelina) {
        const glow = ctx.createRadialGradient(node.sx, node.sy, radius * 0.2, node.sx, node.sy, radius * (selected ? 4 : 2.7));
        glow.addColorStop(0, rgba(color, selected ? 0.2 : 0.12));
        glow.addColorStop(1, rgba(color, 0));
        ctx.fillStyle = glow;
        ctx.beginPath();
        ctx.arc(node.sx, node.sy, radius * (selected ? 4 : 2.7), 0, Math.PI * 2);
        ctx.fill();
      }

      if (node.inMelina) {
        ctx.beginPath();
        ctx.arc(node.sx, node.sy, radius + 3.2, 0, Math.PI * 2);
        ctx.strokeStyle = rgba("#c8ff42", dimmed ? 0.08 : 0.35);
        ctx.lineWidth = 0.7;
        ctx.setLineDash([2, 3]);
        ctx.stroke();
        ctx.setLineDash([]);
      }

      drawNodeShape(node, radius, color, alpha);
      ctx.beginPath();
      ctx.arc(node.sx, node.sy, Math.max(1, radius * 0.24), 0, Math.PI * 2);
      ctx.fillStyle = rgba(color, alpha);
      ctx.fill();

      if (selected) {
        ctx.beginPath();
        ctx.arc(node.sx, node.sy, radius + 6, 0, Math.PI * 2);
        ctx.strokeStyle = rgba(color, 0.45);
        ctx.lineWidth = 0.8;
        ctx.stroke();
      }
    });

    drawLabels(nodes, connected);
  }

  function drawLabels(nodes, connected) {
    const occupied = [];
    const candidates = nodes
      .filter((node) => node === state.selected || node === state.hovered || (node.ecosystemValue >= 92 && node.projectionScale > 0.55) || (node.inMelina && node.projectionScale > 0.52))
      .sort((a, b) => {
        if (a === state.selected || a === state.hovered) return -1;
        if (b === state.selected || b === state.hovered) return 1;
        return b.ecosystemValue - a.ecosystemValue;
      });

    ctx.font = '600 10px "Atlas Sans", sans-serif';
    candidates.forEach((node) => {
      const dimmed = connected && !connected.has(node);
      if (dimmed && node !== state.hovered) return;
      const textWidth = ctx.measureText(node.label).width;
      const x = node.sx + node.sr + 7;
      const y = node.sy - 1;
      const box = { x, y: y - 9, width: textWidth + 8, height: 15 };
      const collision = occupied.some((item) => box.x < item.x + item.width && box.x + box.width > item.x && box.y < item.y + item.height && box.y + box.height > item.y);
      if (collision && node !== state.selected && node !== state.hovered) return;
      occupied.push(box);
      ctx.fillStyle = node === state.selected || node === state.hovered ? "rgba(245,250,246,0.96)" : "rgba(206,218,209,0.58)";
      ctx.fillText(node.label, x, y + 2);
      if (node === state.selected || node === state.hovered) {
        ctx.font = '500 7px "Atlas Sans", sans-serif';
        ctx.fillStyle = rgba(fieldColor(node), 0.78);
        ctx.fillText(typeLabel(node).toUpperCase(), x, y + 12);
        ctx.font = '600 10px "Atlas Sans", sans-serif';
      }
    });
  }

  function updateSemanticLabels() {
    if (state.layout !== "semantic") return;
    $$("[data-label-field]", els.labels).forEach((label) => {
      const field = label.dataset.labelField;
      const center = semanticCenter(field);
      const point = transformPoint(center.x, center.y - 90, center.z);
      label.style.left = `${point.x}px`;
      label.style.top = `${point.y}px`;
      label.style.opacity = state.activeFields.has(field) && point.visible ? String(clamp(point.scale * 0.85, 0.18, 0.7)) : "0";
    });
  }

  function render(time) {
    const delta = Math.min(50, time - lastTime);
    lastTime = time;
    if (state.autoRotate && !state.dragging && time - state.lastInteraction > 1600) state.yaw += delta * 0.000045;
    const settle = reducedMotion ? 1 : 1 - Math.pow(0.001, delta / 1000);
    data.nodes.forEach((node) => {
      node.x += (node.tx - node.x) * settle;
      node.y += (node.ty - node.y) * settle;
      node.z += (node.tz - node.z) * settle;
    });

    drawBackground(time);
    projectNodes();
    drawEdges(time);
    drawNodes(time);
    updateSemanticLabels();
    frameId = requestAnimationFrame(render);
  }

  function hitTest(clientX, clientY) {
    const rect = els.canvas.getBoundingClientRect();
    const x = clientX - rect.left;
    const y = clientY - rect.top;
    let hit = null;
    let bestDistance = Infinity;
    visibleNodes.forEach((node) => {
      if (!node.onScreen) return;
      const distance = Math.hypot(x - node.sx, y - node.sy);
      const threshold = Math.max(8, node.sr + 4);
      if (distance <= threshold && distance < bestDistance) {
        hit = node;
        bestDistance = distance;
      }
    });
    return hit;
  }

  function markInteraction() {
    state.lastInteraction = performance.now();
    els.gestureHint.classList.add("is-hidden");
  }

  function onPointerDown(event) {
    state.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (state.pointers.size === 1) {
      state.dragging = true;
      state.pinching = false;
      state.moved = false;
      state.pointerId = event.pointerId;
      state.startX = state.lastX = event.clientX;
      state.startY = state.lastY = event.clientY;
    } else if (state.pointers.size === 2) {
      const [a, b] = [...state.pointers.values()];
      state.pinching = true;
      state.moved = true;
      state.pinchDistance = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y));
      state.pinchZoom = state.zoom;
    }
    els.canvas.setPointerCapture(event.pointerId);
    markInteraction();
  }

  function onPointerMove(event) {
    if (state.pointers.has(event.pointerId)) state.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (state.pinching && state.pointers.size >= 2) {
      const [a, b] = [...state.pointers.values()];
      const distance = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y));
      state.zoom = clamp(state.pinchZoom * (distance / state.pinchDistance), 0.54, 2.15);
    } else if (state.dragging && event.pointerId === state.pointerId) {
      const dx = event.clientX - state.lastX;
      const dy = event.clientY - state.lastY;
      if (Math.hypot(event.clientX - state.startX, event.clientY - state.startY) > 4) state.moved = true;
      state.yaw += dx * 0.006;
      state.pitch = clamp(state.pitch + dy * 0.005, -1.25, 1.25);
      state.lastX = event.clientX;
      state.lastY = event.clientY;
    } else {
      state.hovered = hitTest(event.clientX, event.clientY);
      els.canvas.style.cursor = state.hovered ? "pointer" : "grab";
    }
  }

  function onPointerUp(event) {
    if (!state.pointers.has(event.pointerId)) return;
    const wasTap = event.type === "pointerup" && state.pointers.size === 1 && event.pointerId === state.pointerId && !state.moved;
    state.pointers.delete(event.pointerId);
    if (wasTap) selectNode(hitTest(event.clientX, event.clientY));
    if (state.pointers.size === 0) {
      state.dragging = false;
      state.pinching = false;
      state.pointerId = null;
    } else {
      const [pointerId, point] = state.pointers.entries().next().value;
      state.dragging = true;
      state.pinching = false;
      state.moved = true;
      state.pointerId = pointerId;
      state.lastX = point.x;
      state.lastY = point.y;
    }
    try { els.canvas.releasePointerCapture(event.pointerId); } catch (_) { /* already released */ }
  }

  function onWheel(event) {
    event.preventDefault();
    markInteraction();
    state.zoom = clamp(state.zoom * Math.exp(-event.deltaY * 0.001), 0.54, 2.15);
  }

  function onCanvasKeydown(event) {
    const keyMap = {
      ArrowLeft: () => { state.yaw -= 0.09; },
      ArrowRight: () => { state.yaw += 0.09; },
      ArrowUp: () => { state.pitch = clamp(state.pitch - 0.07, -1.25, 1.25); },
      ArrowDown: () => { state.pitch = clamp(state.pitch + 0.07, -1.25, 1.25); },
      "+": () => { state.zoom = clamp(state.zoom * 1.1, 0.54, 2.15); },
      "=": () => { state.zoom = clamp(state.zoom * 1.1, 0.54, 2.15); },
      "-": () => { state.zoom = clamp(state.zoom / 1.1, 0.54, 2.15); }
    };
    if (keyMap[event.key]) {
      event.preventDefault();
      markInteraction();
      keyMap[event.key]();
    }
  }

  function resetCamera() {
    state.yaw = -0.38;
    state.pitch = -0.12;
    state.zoom = 1;
    markInteraction();
  }

  function setAutoRotate(active) {
    state.autoRotate = active;
    els.autoRotate.classList.toggle("is-active", active);
    els.autoRotate.setAttribute("aria-pressed", String(active));
    markInteraction();
  }

  function selectNode(node) {
    state.selected = node || null;
    if (!node) {
      els.detailEmpty.hidden = false;
      els.detailContent.hidden = true;
      els.detailPanel.classList.remove("is-open");
      return;
    }
    populateDetail(node);
    els.detailEmpty.hidden = true;
    els.detailContent.hidden = false;
    els.detailPanel.classList.add("is-open");
  }

  function detailMetric(label, value, full = false) {
    if (value == null || value === "") return "";
    return `<div${full ? ' class="full"' : ""}><dt>${label}</dt><dd title="${String(value).replaceAll('"', '&quot;')}">${value}</dd></div>`;
  }

  function populateDetail(node) {
    const color = fieldColor(node);
    els.detailGlyph.style.color = color;
    els.detailType.textContent = `${typeLabel(node)} · ${node.year || "anno n/d"}`;
    els.detailTitle.textContent = node.label;
    els.detailDescription.textContent = node.description || "Descrizione non disponibile.";
    els.detailScore.textContent = node.ecosystemValue ?? "—";
    els.detailScoreBars.innerHTML = Array.from({ length: 10 }, (_, index) => `<i class="${index < Math.round((node.ecosystemValue || 0) / 10) ? "on" : ""}"></i>`).join("");

    const statuses = [
      node.inMelina ? '<span class="status-chip integrated">In Melina</span>' : "",
      node.status ? `<span class="status-chip">${node.status}</span>` : "",
      node.license ? `<span class="status-chip">${node.license}</span>` : "",
      Number.isFinite(node.openness) ? `<span class="status-chip">Open ${node.openness}/100</span>` : ""
    ].filter(Boolean);
    els.detailStatus.innerHTML = statuses.join("");

    const metrics = node.metrics || {};
    els.detailMetrics.innerHTML = [
      detailMetric("Anno", node.year || "—"),
      detailMetric("Scala org.", node.scaleScore != null ? `${node.scaleScore}/100` : "—"),
      detailMetric("GitHub stars", node.githubRepo ? formatNumber(metrics.stars) : "—"),
      detailMetric("HF download", node.hfModel ? formatNumber(metrics.downloads) : "—"),
      detailMetric("Parametri", metrics.parameters, !metrics.macs),
      detailMetric("Compute", metrics.macs),
      detailMetric("Latenza", metrics.latencyMs != null ? (typeof metrics.latencyMs === "number" ? `${metrics.latencyMs} ms` : metrics.latencyMs) : null, true),
      detailMetric("Sample rate", metrics.sampleRate, true),
      detailMetric("Potenza", metrics.power, true),
      detailMetric("Valore economico verificato", node.valueLabel, true)
    ].filter(Boolean).join("");

    const architecture = node.architecture || [];
    els.architectureSection.hidden = architecture.length === 0;
    els.detailArchitecture.innerHTML = architecture.map((item) => `<span>${item}</span>`).join("");

    const validation = node.validation || { score: 0, level: "non mappata", summary: "Nessuna validazione mappata." };
    els.validationScore.textContent = `${validation.score || 0}/100`;
    els.validationFill.style.width = `${validation.score || 0}%`;
    els.validationLevel.textContent = validation.level || "non mappata";
    els.validationSummary.textContent = validation.summary || "Nessuna sintesi disponibile.";

    const relations = edges
      .filter((edge) => edge.sourceNode === node || edge.targetNode === node)
      .map((edge) => ({ edge, other: edge.sourceNode === node ? edge.targetNode : edge.sourceNode }))
      .sort((a, b) => (b.edge.weight || 1) - (a.edge.weight || 1) || b.other.ecosystemValue - a.other.ecosystemValue);
    els.relationCount.textContent = `· ${relations.length}`;
    els.detailRelations.innerHTML = relations.slice(0, 12).map(({ edge, other }) => `
      <button class="relation-item" type="button" data-node-id="${other.id}">
        <i style="color:${fieldColor(other)}"></i><span>${other.label}</span><small>${edge.label || edge.type}</small>
      </button>`).join("");

    const links = [...(node.links || [])];
    if (node.githubRepo && !links.some((link) => link.url.includes("github.com"))) links.push({ label: "GitHub", url: `https://github.com/${node.githubRepo}` });
    if (node.hfModel && !links.some((link) => link.url.includes("huggingface.co"))) links.push({ label: "Hugging Face", url: `https://huggingface.co/${node.hfModel}` });
    els.detailLinks.innerHTML = links.map((link) => `<a href="${link.url}" target="_blank" rel="noreferrer"><span>${link.label}</span><span aria-hidden="true">↗</span></a>`).join("");
    els.detailPanel.scrollTo({ top: 0, behavior: reducedMotion ? "auto" : "smooth" });
  }

  els.retry.addEventListener("click", loadData);
  loadData();
})();
