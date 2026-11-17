class PlanTree {
  constructor(host, onSelect) {
    this.onSelect = onSelect;
    this.buttons = new Map();
    this.zoom = 1;
    this.mode = "width";
    host.classList.add("plan-graph");
    const toolbar = document.createElement("div");
    toolbar.className = "graph-toolbar";
    const actions = [
      ["−", "Уменьшить масштаб", () => this.setZoom(this.zoom / 1.2)],
      ["100%", "Масштаб 100%", () => this.setZoom(1)],
      ["+", "Увеличить масштаб", () => this.setZoom(this.zoom * 1.2)],
      ["Вписать", "Показать весь план", () => { this.mode = "fit"; this.fit(); }]
    ];
    for (const [text, title, action] of actions) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = text;
      button.title = title;
      button.setAttribute("aria-label", title);
      button.addEventListener("click", action);
      toolbar.append(button);
    }
    this.zoomLabel = document.createElement("span");
    this.zoomLabel.className = "graph-scale";
    this.zoomLabel.setAttribute("aria-live", "polite");
    toolbar.append(this.zoomLabel);
    host.append(toolbar);
    this.viewport = document.createElement("div");
    host.append(this.viewport);
    const resizeObserver = new ResizeObserver(() => {
      if (this.mode !== "manual") this.fit();
      else this.centerSelection();
    });
    resizeObserver.observe(host);
    this.clear();
  }

  clear() {
    this.buttons.clear();
    this.selected = null;
    this.tree = null;
    this.canvas = null;
    this.viewport.replaceChildren();
    this.viewport.textContent = "Постройте план, чтобы увидеть граф.";
    this.zoomLabel.textContent = "";
  }

  render(root, matchedIds, provedIds) {
    const matched = new Set(matchedIds);
    const proved = new Set(provedIds);
    this.buttons.clear();
    let count = 0;
    const branch = (node, subquery) => {
      count++;
      const item = document.createElement("li");
      if (matched.has(node.id)) item.classList.add("matched");
      if (proved.has(node.id)) item.classList.add("proved");
      const button = document.createElement("button");
      button.type = "button";
      button.className = "node";
      button.dataset.nodeId = String(node.id);
      button.setAttribute("aria-pressed", "false");
      button.setAttribute("aria-label", `${node.type} #${node.id}${matched.has(node.id) ? ", совпадение с правилом" : ""}`);
      const label = node.type === "LogicalJoin" ? `${node.attributes.joinType.toUpperCase()} JOIN`
        : node.type.replace(/^Logical/, "");
      const title = document.createElement("strong");
      title.textContent = subquery ? `${subquery} → ${label}` : label;
      const summary = document.createElement("span");
      summary.textContent = Object.entries(node.attributes).map(([key, value]) => `${key}=${value}`).join(" · ");
      button.title = summary.textContent;
      button.append(title, summary);
      button.addEventListener("click", () => this.select(node.id));
      this.buttons.set(node.id, { button, node });
      item.append(button);
      if (node.inputs.length || node.subqueries.length) {
        const children = document.createElement("ul");
        children.append(...node.inputs.map(input => branch(input)),
          ...node.subqueries.map(query => branch(query.plan, query.operator)));
        item.append(children);
      }
      return item;
    };
    this.tree = document.createElement("ul");
    this.zoom = 1;
    this.tree.className = "tree";
    this.tree.append(branch(root));
    const viewport = document.createElement("div");
    viewport.className = "tree-scroll";
    viewport.tabIndex = 0;
    viewport.setAttribute("role", "region");
    viewport.setAttribute("aria-label", "Граф логического плана");
    this.canvas = document.createElement("div");
    this.canvas.className = "graph-canvas";
    this.canvas.append(this.tree);
    viewport.append(this.canvas);
    this.viewport.replaceWith(viewport);
    this.viewport = viewport;
    let drag = null;
    viewport.addEventListener("pointerdown", event => {
      if (event.button !== 0 || event.target.closest("button")) return;
      drag = { x: event.clientX, y: event.clientY };
      viewport.setPointerCapture(event.pointerId);
    });
    viewport.addEventListener("pointermove", event => {
      if (!drag) return;
      viewport.scrollBy(drag.x - event.clientX, drag.y - event.clientY);
      drag = { x: event.clientX, y: event.clientY };
    });
    viewport.addEventListener("pointerup", () => { drag = null; });
    viewport.addEventListener("pointercancel", () => { drag = null; });
    viewport.addEventListener("wheel", event => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      this.setZoom(this.zoom * (event.deltaY < 0 ? 1.1 : 1 / 1.1));
    }, { passive: false });
    this.mode = "width";
    this.select(root.id);
    requestAnimationFrame(() => this.fit());
    return count;
  }

  select(id) {
    const selected = this.buttons.get(id);
    if (!selected) return;
    for (const { button } of this.buttons.values()) {
      button.setAttribute("aria-pressed", String(button === selected.button));
    }
    this.selected = selected.button;
    this.onSelect(selected.node);
    this.centerSelection();
  }

  setZoom(value) {
    if (!this.tree) return;
    this.mode = "manual";
    this.zoom = Math.max(0.4, Math.min(2, value));
    this.tree.style.transform = `scale(${this.zoom})`;
    this.canvas.style.width = `${this.tree.offsetWidth * this.zoom}px`;
    this.canvas.style.height = `${this.tree.offsetHeight * this.zoom}px`;
    this.zoomLabel.textContent = `${Math.round(this.zoom * 100)}%`;
    this.centerSelection();
  }

  fit() {
    if (!this.tree || !this.viewport.clientWidth || !this.viewport.clientHeight) return;
    const mode = this.mode;
    const horizontal = this.viewport.clientHeight < 300 && this.viewport.clientWidth > this.viewport.clientHeight * 2;
    this.tree.dataset.direction = horizontal ? "right" : "down";
    this.tree.style.setProperty("--node-width", horizontal && this.viewport.clientWidth < 700 ? "125px" : "190px");
    const style = getComputedStyle(this.viewport);
    const paddingX = parseFloat(style.paddingLeft) + parseFloat(style.paddingRight);
    const paddingY = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom);
    const widthScale = (this.viewport.clientWidth - paddingX - 8) / this.tree.offsetWidth;
    const heightScale = mode === "fit" ? (this.viewport.clientHeight - paddingY - 8) / this.tree.offsetHeight : 1;
    this.setZoom(Math.min(1, widthScale, heightScale));
    this.mode = mode;
    if (mode === "fit") this.viewport.scrollTop = 0;
  }

  centerSelection() {
    if (!this.selected || !this.viewport.clientWidth) return;
    const node = this.selected.getBoundingClientRect();
    const viewport = this.viewport.getBoundingClientRect();
    const left = this.viewport.scrollLeft + node.left - viewport.left
      - (this.viewport.clientWidth - node.width) / 2;
    let top = this.viewport.scrollTop;
    if (node.top < viewport.top) top += node.top - viewport.top - 8;
    if (node.bottom > viewport.bottom) top += node.bottom - viewport.bottom + 8;
    this.viewport.scrollTo({ left, top, behavior: "instant" });
  }
}
