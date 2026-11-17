const sql = document.querySelector("#sql");
const submit = document.querySelector("#analyze");
const result = document.querySelector("#result");
const empty = document.querySelector("#empty");
const error = document.querySelector("#error");
const finding = document.querySelector("#finding");
const state = document.querySelector("#state");
const example = document.querySelector("#example");
const before = document.querySelector("#before");
const after = document.querySelector("#after");
const planGraph = new PlanTree(document.querySelector("#plan-graph"), inspect);
let pending;
let analysis;
const propertyLabels = {
  empty: ["Пустой фильтр", "Строка, удовлетворяющая условию, отсутствует.", "Проверьте, нужен ли пустой результат."],
  distinct: ["Избыточный DISTINCT / GROUP BY", "Вход уникален по ключам дедупликации, включая NULL.", "Дедупликацию можно удалить."],
  join_remove: ["Неиспользуемая сторона внешнего JOIN", "Удаление стороны JOIN сохраняет строки и их кратность в данном контексте.", "Соединение можно удалить, сохранив используемую сторону."],
  outer_inner: ["Внешний JOIN → INNER JOIN", "NULL-дополненные строки не проходят контекст над соединением.", "Соединение можно заменить на INNER JOIN."],
  union: ["UNION → UNION ALL", "Ветви уникальны, совпадения между ними исключены.", "Можно использовать UNION ALL."]
};
const propertyStatuses = {
  PROVED: "Доказано в модели",
  UNKNOWN: "Не доказано",
  UNSUPPORTED: "Вне поддерживаемого фрагмента",
  TIMEOUT: "Исчерпан бюджет проверки"
};

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

function inspect(node) {
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
  document.querySelector("#optimization-state").textContent = data.optimized.changed ? "SQL и дерево пересчитаны." : "Нет применимого SQL-преобразования.";
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
  const proved = new Set(data.properties.filter(property => property.status === "PROVED").map(property => property.nodeId));
  const count = planGraph.render(data.tree, [...matched], [...proved]);
  document.querySelector("#node-count").textContent = `Узлов: ${count}`;
  document.querySelector("#plan").textContent = data.plan;
  finding.replaceChildren();
  const unresolved = element("details", undefined, "unresolved");
  const pendingProperties = data.properties.filter(property => property.status !== "PROVED");
  unresolved.append(element("summary", `Без доказательства · ${pendingProperties.length}`));
  for (const property of data.properties) {
    const [title, explanation, recommendation] = propertyLabels[property.kind];
    const item = element("article", undefined, "property");
    const link = element("button", `${title} · #${property.nodeId}`, "property-link");
    link.type = "button";
    link.addEventListener("click", () => {
      planGraph.select(property.nodeId);
      planGraph.buttons.get(property.nodeId)?.button.focus();
    });
    item.append(element("h3"), element("p", propertyStatuses[property.status], "property-status"));
    item.firstChild.append(link);
    if (property.status === "PROVED") {
      item.append(element("p", explanation), element("p", recommendation));
    } else {
      item.append(element("p", property.status === "UNKNOWN"
        ? "Модель допускает нарушение свойства. Для вывода требуется дополнительное основание."
        : property.reason));
    }
    if (property.representation) {
      const representation = property.representation === "enumeration" ? "перебор" : "DAG с условиями присутствия";
      item.append(element("p", `Контекст #${property.scopeId} · обязательства ${property.obligations} · ${property.elapsedMillis.toFixed(1)} мс · ${representation}`, "property-metrics"));
    }
    (property.status === "PROVED" ? finding : unresolved).append(item);
  }
  if (pendingProperties.length) finding.append(unresolved);
  for (const match of matches) {
    const item = element("article", undefined, "found");
    item.append(element("h3", match.title), element("code", match.pattern, "pattern"),
      element("p", match.explanation), element("p", match.recommendation));
    finding.append(item);
  }
  if (!matches.length && !proved.size) {
    finding.append(element("p", "Подтверждённых рекомендаций нет."));
    for (const rule of data.rules) {
      if (rule.status === "NOT_FOUND" && rule.explanation) {
        finding.append(element("p", rule.explanation));
      }
    }
  }
  const unsupported = data.rules.filter(rule => rule.status === "UNSUPPORTED");
  if (unsupported.length) {
    const scope = element("details", undefined, "rule-scope");
    scope.append(element("summary", "Область правил каталога"));
    for (const rule of unsupported) scope.append(element("p", `${rule.title}. ${rule.explanation}`));
    finding.append(scope);
  }
  empty.hidden = true;
  result.hidden = false;
  finding.hidden = false;
  state.textContent = "План построен";
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
