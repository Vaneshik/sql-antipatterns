const sql = document.querySelector("#sql");
const submit = document.querySelector("#analyze");
const result = document.querySelector("#result");
const empty = document.querySelector("#empty");
const error = document.querySelector("#error");
const finding = document.querySelector("#finding");
const state = document.querySelector("#state");
const tree = document.querySelector("#tree");
const example = document.querySelector("#example");
const before = document.querySelector("#before");
const after = document.querySelector("#after");
let pending;
let analysis;

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function clearResult(message) {
  pending?.abort();
  pending = null;
  submit.disabled = false;
  result.hidden = true;
  before.disabled = true;
  after.disabled = true;
  before.setAttribute("aria-pressed", "false");
  after.setAttribute("aria-pressed", "false");
  analysis = null;
  finding.hidden = true;
  error.hidden = true;
  empty.hidden = false;
  empty.querySelector("p").textContent = message;
  state.textContent = "Ожидание";
  document.querySelector("#node-count").textContent = "";
  document.querySelector("#optimization-state").textContent = "";
}

function inspect(node, button) {
  tree.querySelectorAll("button").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
  document.querySelector("#selected-title").textContent = `${node.type} #${node.id}`;
  const attributes = document.querySelector("#attributes");
  attributes.replaceChildren();
  for (const [name, value] of Object.entries(node.attributes)) {
    attributes.append(element("dt", name), element("dd", value));
  }
  const fields = document.querySelector("#fields");
  fields.replaceChildren(element("h4", "Выходные колонки"),
    element("p", node.fields.map((field, index) => `$${index} · ${field}`).join("\n")));
  if (node.inputs.length) {
    fields.append(element("h4", "Входные колонки (ссылки в выражениях узла)"),
      element("p", node.inputs.flatMap(input => input.fields)
        .map((field, index) => `$${index} · ${field}`).join("\n")));
  }
}

function render(data, submittedSql) {
  analysis = { ...data, sql: submittedSql };
  document.querySelector("#optimization-state").textContent = data.optimized.changed ? "" : "План не изменился.";
  before.disabled = false;
  after.disabled = false;
  showPlan(analysis);
}

function showPlan(data) {
  sql.value = data.sql;
  before.setAttribute("aria-pressed", String(data === analysis));
  after.setAttribute("aria-pressed", String(data === analysis.optimized));
  const matches = data.rules.filter(rule => rule.status === "FOUND");
  const matched = new Set(matches.flatMap(rule => rule.matchedNodeIds));
  let count = 0;
  function branch(node, subqueryOperator) {
    count++;
    const item = element("li", undefined, matched.has(node.id) ? "matched" : "");
    const button = element("button", undefined, "node");
    button.type = "button";
    button.setAttribute("aria-pressed", "false");
    button.setAttribute("aria-label", `${node.type} #${node.id}${matched.has(node.id) ? ", совпадение с правилом" : ""}`);
    const summary = Object.entries(node.attributes).map(([key, value]) => `${key}=${value}`).join(" · ");
    const label = node.type === "LogicalJoin" ? `${node.attributes.joinType.toUpperCase()} JOIN`
      : node.type.replace(/^Logical/, "");
    button.append(element("strong", subqueryOperator ? `${subqueryOperator} → ${label}` : label),
      element("span", summary || "Нет атрибутов"));
    button.title = summary;
    button.addEventListener("click", () => inspect(node, button));
    item.append(button);
    if (node.inputs.length || node.subqueries.length) {
      const children = element("ul");
      children.append(...node.inputs.map(input => branch(input)),
        ...node.subqueries.map(subquery => branch(subquery.plan, subquery.operator)));
      item.append(children);
    }
    return item;
  }
  tree.replaceChildren(branch(data.tree));
  document.querySelector("#node-count").textContent = `Узлов: ${count}`;
  document.querySelector("#plan").textContent = data.plan;
  finding.replaceChildren();
  for (const match of matches) {
    const item = element("article", undefined, "found");
    item.append(element("h3", match.title), element("code", match.pattern, "pattern"),
      element("p", match.explanation), element("p", match.recommendation));
    finding.append(item);
  }
  if (!matches.length) {
    finding.append(element("p", "Совпадений нет."));
    for (const rule of data.rules) {
      if (rule.status === "NOT_FOUND" && rule.explanation) {
        finding.append(element("p", rule.explanation));
      }
    }
  }
  empty.hidden = true;
  result.hidden = false;
  finding.hidden = false;
  state.textContent = "План построен";
  inspect(data.tree, tree.querySelector("button"));
}

async function analyze() {
  const submittedSql = sql.value;
  clearResult("Calcite строит логический план…");
  const request = new AbortController();
  pending = request;
  submit.disabled = true;
  state.textContent = "Анализ…";
  try {
    const response = await fetch("/api/analyze", {
      method: "POST", headers: { "Content-Type": "text/plain; charset=utf-8" },
      body: submittedSql, signal: request.signal
    });
    const data = await response.json();
    if (pending !== request) return;
    if (!response.ok) throw new Error(data.error);
    render(data, submittedSql);
  } catch (failure) {
    if (pending !== request) return;
    error.textContent = failure.message;
    error.hidden = false;
    state.textContent = "Ошибка";
    empty.querySelector("p").textContent = "Исправьте запрос и постройте план заново.";
  } finally {
    if (pending === request) submit.disabled = false;
  }
}

async function loadExample(name) {
  clearResult("Загрузка примера…");
  const request = new AbortController();
  pending = request;
  submit.disabled = true;
  try {
    const response = await fetch(`/examples/${name}.sql`, { signal: request.signal });
    if (!response.ok) throw new Error("Не удалось загрузить пример.");
    const text = await response.text();
    if (pending !== request) return;
    sql.value = text;
    await analyze();
  } catch (failure) {
    if (pending !== request) return;
    error.textContent = failure.message;
    error.hidden = false;
    submit.disabled = false;
    empty.querySelector("p").textContent = "Введите запрос или выберите пример ещё раз.";
  }
}

example.addEventListener("change", () => loadExample(example.value));
before.addEventListener("click", () => {
  if (analysis) showPlan(analysis);
});
after.addEventListener("click", () => {
  if (analysis) showPlan(analysis.optimized);
});
document.querySelector("#sql-form").addEventListener("submit", event => {
  event.preventDefault();
  analyze();
});
sql.addEventListener("input", () => {
  clearResult("Запрос изменён. Постройте план заново.");
  example.selectedIndex = -1;
});
sql.addEventListener("keydown", event => {
  if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    analyze();
  }
});
loadExample(example.value);
