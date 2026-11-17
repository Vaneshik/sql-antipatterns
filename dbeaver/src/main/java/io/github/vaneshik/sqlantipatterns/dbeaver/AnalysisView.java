package io.github.vaneshik.sqlantipatterns.dbeaver;

import io.github.vaneshik.sqlantipatterns.Analyzer;
import io.github.vaneshik.sqlantipatterns.PlanNode;
import io.github.vaneshik.sqlantipatterns.QueryAdvice;
import java.util.List;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.part.ViewPart;

public final class AnalysisView extends ViewPart {
    public static final String ID = "io.github.vaneshik.sqlantipatterns.view";
    private Label status;
    private Table findings;
    private Tree tree;
    private StyledText details;
    private StyledText originalSql;
    private StyledText suggestedSql;
    private Button apply;
    private Button after;
    private EditorSnapshot source;
    private QueryAdvice advice;
    private Job job;
    private long generation;
    private final IDocumentListener edits = new IDocumentListener() {
        public void documentAboutToBeChanged(DocumentEvent event) { }
        public void documentChanged(DocumentEvent event) {
            if (!status.isDisposed()) {
                apply.setEnabled(false);
                status.setText("Текст изменён. Повторите анализ через меню SQL-антипаттерны.");
            }
        }
    };

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Откройте SELECT в PostgreSQL и выберите меню SQL-антипаттерны → Анализ.");
        var toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(2, false));
        after = new Button(toolbar, SWT.CHECK);
        after.setText("План после исправления");
        after.setEnabled(false);
        after.addListener(SWT.Selection, event -> renderPlan());
        apply = new Button(toolbar, SWT.PUSH);
        apply.setText("Применить исправление в редакторе");
        apply.setEnabled(false);
        apply.addListener(SWT.Selection, event -> {
            try {
                source.apply(advice.suggestedSql());
                apply.setEnabled(false);
                status.setText("Исправление применено. Undo доступен в SQL-редакторе. Повторите анализ для нового текста.");
            } catch (Exception exception) {
                MessageDialog.openError(getSite().getShell(), "SQL-антипаттерны", exception.getMessage());
            }
        });
        var tabs = new TabFolder(parent, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var analysis = new SashForm(tabs, SWT.HORIZONTAL);
        tree = new Tree(analysis, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        var right = new SashForm(analysis, SWT.VERTICAL);
        findings = new Table(right, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        findings.setHeaderVisible(true);
        for (String title : List.of("Статус", "Диагностика")) {
            var column = new TableColumn(findings, SWT.NONE);
            column.setText(title);
            column.setWidth(title.equals("Статус") ? 140 : 330);
        }
        details = new StyledText(right, SWT.BORDER | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        findings.addListener(SWT.Selection, event -> showFinding());
        tree.addListener(SWT.Selection, event -> {
            if (tree.getSelectionCount() > 0 && tree.getSelection()[0].getData() instanceof PlanNode node) {
                details.setText(node.type() + " #" + node.id() + "\n\n"
                        + node.attributes().entrySet().stream().map(entry -> entry.getKey() + " = " + entry.getValue())
                            .collect(java.util.stream.Collectors.joining("\n"))
                        + "\n\n" + String.join("\n", node.fields()));
            }
        });
        right.setWeights(40, 60);
        analysis.setWeights(45, 55);
        var planTab = new TabItem(tabs, SWT.NONE);
        planTab.setText("План и диагностика");
        planTab.setControl(analysis);
        var comparison = new SashForm(tabs, SWT.HORIZONTAL);
        originalSql = sqlPane(comparison, "Исходный запрос");
        suggestedSql = sqlPane(comparison, "Предлагаемый запрос");
        comparison.setWeights(50, 50);
        var sqlTab = new TabItem(tabs, SWT.NONE);
        sqlTab.setText("Исправление SQL");
        sqlTab.setControl(comparison);
    }

    private StyledText sqlPane(Composite parent, String title) {
        var pane = new Composite(parent, SWT.NONE);
        pane.setLayout(new GridLayout(1, false));
        new Label(pane, SWT.NONE).setText(title);
        var text = new StyledText(pane, SWT.BORDER | SWT.READ_ONLY | SWT.H_SCROLL | SWT.V_SCROLL);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        text.setFont(JFaceResources.getTextFont());
        return text;
    }

    long beginAnalysis(EditorSnapshot snapshot) {
        if (job != null) {
            job.cancel();
        }
        if (source != null) {
            source.document().removeDocumentListener(edits);
        }
        source = snapshot;
        source.document().addDocumentListener(edits);
        advice = null;
        tree.removeAll();
        findings.removeAll();
        details.setText("");
        originalSql.setText(snapshot.sql());
        suggestedSql.setText("");
        after.setSelection(false);
        after.setEnabled(false);
        apply.setEnabled(false);
        status.setText("Загрузка метаданных PostgreSQL и анализ Calcite…");
        return ++generation;
    }

    void setAnalysisJob(Job job) {
        this.job = job;
    }

    void complete(long request, QueryAdvice result) {
        if (status.isDisposed() || request != generation) {
            return;
        }
        advice = result;
        boolean current = source.isCurrent();
        apply.setEnabled(current && result.suggestedSql() != null);
        after.setEnabled(result.after() != null);
        suggestedSql.setText(result.suggestedSql() == null ? "Для этого запроса автоматическое исправление отсутствует."
                : result.suggestedSql());
        long count = result.before().rules().stream().filter(rule -> rule.status() == Analyzer.Status.FOUND).count();
        status.setText(current ? "Найдено " + count + ". PostgreSQL · Calcite 1.42 · ключи PK / UNIQUE NOT NULL. "
                + "Эффект на время выполнения требует измерения."
                : "Результат относится к предыдущему тексту или подключению. Повторите анализ.");
        renderPlan();
    }

    void failed(long request, Exception exception) {
        if (status.isDisposed() || request != generation) {
            return;
        }
        status.setText("Анализ завершился ошибкой.");
        details.setText(exception.getClass().getSimpleName() + "\n" + exception.getMessage());
        apply.setEnabled(false);
    }

    void cancelled(long request) {
        if (!status.isDisposed() && request == generation) {
            status.setText("Анализ отменён.");
            apply.setEnabled(false);
        }
    }

    private void renderPlan() {
        if (advice == null) {
            return;
        }
        Analyzer.Result result = after.getSelection() ? advice.after() : advice.before();
        tree.removeAll();
        addNode(new TreeItem(tree, SWT.NONE), result.tree());
        findings.removeAll();
        for (Analyzer.RuleResult rule : result.rules()) {
            var item = new TableItem(findings, SWT.NONE);
            item.setText(new String[] {switch (rule.status()) {
                case FOUND -> "Найдено";
                case NOT_FOUND -> "Не обнаружено";
                case UNSUPPORTED -> "Вне области правила";
            }, rule.title()});
            item.setData(rule);
        }
        int selected = 0;
        for (int i = 0; i < findings.getItemCount(); i++) {
            if (((Analyzer.RuleResult) findings.getItem(i).getData()).status() == Analyzer.Status.FOUND) {
                selected = i;
                break;
            }
        }
        findings.setSelection(selected);
        showFinding();
    }

    private void addNode(TreeItem item, PlanNode node) {
        item.setText(node.type() + " #" + node.id());
        item.setData(node);
        for (PlanNode input : node.inputs()) {
            addNode(new TreeItem(item, SWT.NONE), input);
        }
        for (PlanNode.Subquery subquery : node.subqueries()) {
            var child = new TreeItem(item, SWT.NONE);
            addNode(child, subquery.plan());
            child.setText(subquery.operator() + " · " + child.getText());
        }
        item.setExpanded(true);
    }

    private void showFinding() {
        if (findings.getSelectionCount() == 0) {
            return;
        }
        var rule = (Analyzer.RuleResult) findings.getSelection()[0].getData();
        details.setText(rule.title() + "\n" + rule.pattern() + "\n\n"
                + rule.explanation() + "\n\n" + rule.recommendation());
        for (TreeItem item : tree.getItems()) {
            highlight(item, rule.matchedNodeIds());
        }
    }

    private void highlight(TreeItem item, List<Integer> matches) {
        var node = (PlanNode) item.getData();
        item.setForeground(matches.contains(node.id()) ? tree.getDisplay().getSystemColor(SWT.COLOR_DARK_RED) : null);
        for (TreeItem child : item.getItems()) {
            highlight(child, matches);
        }
    }

    @Override
    public void setFocus() {
        tree.setFocus();
    }

    @Override
    public void dispose() {
        generation++;
        if (job != null) {
            job.cancel();
        }
        if (source != null) {
            source.document().removeDocumentListener(edits);
        }
        super.dispose();
    }
}
