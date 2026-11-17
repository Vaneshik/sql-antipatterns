package io.github.vaneshik.sqlantipatterns.dbeaver;

import io.github.vaneshik.sqlantipatterns.Analyzer;
import io.github.vaneshik.sqlantipatterns.PlanNode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.part.ViewPart;

public final class AnalysisView extends ViewPart {
    public static final String ID = "io.github.vaneshik.sqlantipatterns.view";
    private Label status;
    private Label sourceLabel;
    private Label changeSummary;
    private Button analyze;
    private Button cancel;
    private Button preview;
    private Button showInPlan;
    private Button apply;
    private TabFolder tabs;
    private TabItem recommendationsTab;
    private SashForm results;
    private Table findings;
    private StyledText details;
    private Link scope;
    private TextMergeViewer comparison;
    private Combo planVersion;
    private Tree tree;
    private StyledText nodeDetails;
    private EditorSnapshot source;
    private Analyzer.Result advice;
    private Job job;
    private long generation;
    private final IDocumentListener edits = new IDocumentListener() {
        public void documentAboutToBeChanged(DocumentEvent event) { }
        public void documentChanged(DocumentEvent event) {
            if (!status.isDisposed()) {
                apply.setEnabled(false);
                setStatus("Запрос изменён. Повторите анализ.");
            }
        }
    };

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        var toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        toolbar.setLayout(new GridLayout(3, false));
        analyze = new Button(toolbar, SWT.PUSH);
        analyze.setText("Анализировать");
        analyze.setToolTipText("Проверить выделенный запрос или запрос под курсором активного SQL-редактора.");
        analyze.addListener(SWT.Selection, event -> AnalyzeHandler.analyze(getSite().getWorkbenchWindow()));
        cancel = new Button(toolbar, SWT.PUSH);
        cancel.setText("Отменить");
        cancel.setEnabled(false);
        cancel.addListener(SWT.Selection, event -> {
            if (job != null) {
                job.cancel();
            }
            cancelled(generation);
            generation++;
        });
        status = new Label(toolbar, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Подключите SQL-редактор к PostgreSQL.");
        sourceLabel = new Label(parent, SWT.WRAP);
        sourceLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sourceLabel.setText("Выделите один запрос или поставьте в него курсор.");
        tabs = new TabFolder(parent, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        var recommendations = new Composite(tabs, SWT.NONE);
        recommendations.setLayout(new GridLayout(1, false));
        results = new SashForm(recommendations, SWT.HORIZONTAL);
        results.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        findings = new Table(results, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        details = new StyledText(results, SWT.BORDER | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        details.setText("После анализа здесь появятся рекомендации и условия их применимости.");
        findings.addListener(SWT.Selection, event -> showFinding());
        findings.addListener(SWT.DefaultSelection, event -> {
            if (preview.isEnabled()) {
                tabs.setSelection(1);
            }
        });
        results.setWeights(34, 66);
        var actions = new Composite(recommendations, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        actions.setLayout(new GridLayout(3, false));
        preview = new Button(actions, SWT.PUSH);
        preview.setText("Сравнить SQL");
        preview.setEnabled(false);
        preview.addListener(SWT.Selection, event -> tabs.setSelection(1));
        showInPlan = new Button(actions, SWT.PUSH);
        showInPlan.setText("Показать в плане");
        showInPlan.setEnabled(false);
        showInPlan.addListener(SWT.Selection, event -> {
            planVersion.select(0);
            renderPlan();
            tabs.setSelection(2);
        });
        scope = new Link(actions, SWT.NONE);
        scope.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        scope.addListener(SWT.Selection, event -> {
            findings.deselectAll();
            showInPlan.setEnabled(false);
            details.setText(advice.rules().stream().filter(rule -> rule.status() == Analyzer.Status.UNSUPPORTED)
                    .map(rule -> rule.title() + "\n" + rule.explanation()).collect(Collectors.joining("\n\n")));
        });
        recommendationsTab = new TabItem(tabs, SWT.NONE);
        recommendationsTab.setText("Рекомендации");
        recommendationsTab.setControl(recommendations);

        var sql = new Composite(tabs, SWT.NONE);
        sql.setLayout(new GridLayout(1, false));
        var sqlActions = new Composite(sql, SWT.NONE);
        sqlActions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sqlActions.setLayout(new GridLayout(2, false));
        apply = new Button(sqlActions, SWT.PUSH);
        apply.setText("Заменить запрос");
        apply.setToolTipText("Применить все предложенные исправления к исходному запросу. Отмена доступна в SQL-редакторе.");
        apply.setEnabled(false);
        apply.addListener(SWT.Selection, event -> {
            try {
                source.apply(advice.optimized().sql());
                apply.setEnabled(false);
                setStatus("Исправление применено. Undo доступен в SQL-редакторе.");
            } catch (Exception exception) {
                MessageDialog.openError(getSite().getShell(), "SQL-антипаттерны", exception.getMessage());
            }
        });
        changeSummary = new Label(sqlActions, SWT.WRAP);
        changeSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        changeSummary.setText("Сначала выполните анализ запроса.");
        var config = new CompareConfiguration();
        config.setLeftEditable(false);
        config.setRightEditable(false);
        config.setLeftLabel("Исходный SQL");
        config.setRightLabel("Исправленный SQL");
        config.setProperty(CompareConfiguration.MIRRORED, false);
        comparison = new TextMergeViewer(sql, SWT.NONE, config);
        comparison.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        comparison.getControl().addDisposeListener(event -> config.dispose());
        var sqlTab = new TabItem(tabs, SWT.NONE);
        sqlTab.setText("SQL до / после");
        sqlTab.setControl(sql);

        var plan = new Composite(tabs, SWT.NONE);
        plan.setLayout(new GridLayout(1, false));
        planVersion = new Combo(plan, SWT.READ_ONLY);
        planVersion.setItems("Исходный запрос", "Исправленный запрос");
        planVersion.select(0);
        planVersion.setEnabled(false);
        planVersion.addListener(SWT.Selection, event -> renderPlan());
        var planContent = new SashForm(plan, SWT.HORIZONTAL);
        planContent.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tree = new Tree(planContent, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        nodeDetails = new StyledText(planContent, SWT.BORDER | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        tree.addListener(SWT.Selection, event -> {
            if (tree.getSelectionCount() > 0 && tree.getSelection()[0].getData() instanceof PlanNode node) {
                nodeDetails.setText(node.type() + " #" + node.id() + "\n\n"
                        + node.attributes().entrySet().stream().map(entry -> entry.getKey() + " = " + entry.getValue())
                            .collect(Collectors.joining("\n"))
                        + "\n\n" + String.join("\n", node.fields()));
            }
        });
        planContent.setWeights(45, 55);
        var planTab = new TabItem(tabs, SWT.NONE);
        planTab.setText("Логический план");
        planTab.setControl(plan);
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
        sourceLabel.setText(snapshot.editor().getEditorInput().getName() + " · "
                + snapshot.context().getDataSource().getContainer().getName());
        advice = null;
        tree.removeAll();
        findings.removeAll();
        results.setMaximizedControl(details);
        details.setText("Читаем метаданные PostgreSQL и строим логический план…");
        nodeDetails.setText("");
        scope.setText("");
        comparison.setInput(null);
        changeSummary.setText("Выполняется анализ…");
        recommendationsTab.setText("Рекомендации");
        tabs.setSelection(0);
        planVersion.select(0);
        planVersion.setEnabled(false);
        preview.setEnabled(false);
        showInPlan.setEnabled(false);
        apply.setEnabled(false);
        analyze.setEnabled(false);
        cancel.setEnabled(true);
        setStatus("Анализируем запрос…");
        return ++generation;
    }

    void setAnalysisJob(Job job) {
        this.job = job;
    }

    void complete(long request, Analyzer.Result result) {
        if (status.isDisposed() || request != generation) {
            return;
        }
        advice = result;
        analyze.setEnabled(true);
        cancel.setEnabled(false);
        apply.setEnabled(source.isCurrent() && result.optimized().changed());
        preview.setEnabled(result.optimized().changed());
        planVersion.setEnabled(result.optimized().changed());
        comparison.setInput(new DiffNode(new SqlText("Исходный SQL", source.sql()),
                new SqlText("Исправленный SQL", result.optimized().changed()
                        ? source.replacement(result.optimized().sql()) : source.sql())));
        changeSummary.setText(result.optimized().changed() ? "Все исправления выбранного запроса."
                : "Автоматическое исправление отсутствует.");
        for (Analyzer.RuleResult rule : result.rules()) {
            if (rule.status() == Analyzer.Status.FOUND) {
                var item = new TableItem(findings, SWT.NONE);
                item.setText(rule.title());
                item.setData(rule);
            }
        }
        int count = findings.getItemCount();
        long unsupported = result.rules().stream().filter(rule -> rule.status() == Analyzer.Status.UNSUPPORTED).count();
        scope.setText(unsupported > 0 ? "<a>Ограничения проверки (" + unsupported + ")</a>" : "");
        recommendationsTab.setText("Рекомендации (" + count + ")");
        if (count > 0) {
            results.setMaximizedControl(null);
            findings.setSelection(0);
            showFinding();
        } else {
            details.setText("В поддержанных конструкциях антипаттерны не обнаружены."
                    + (unsupported > 0 ? "\n\nЧасть правил неприменима к этому запросу. Откройте ограничения проверки." : ""));
            renderPlan();
        }
        setStatus(source.isCurrent() ? (count > 0 ? "Найдено рекомендаций — " + count : "Проверка завершена")
                : "Запрос или подключение изменились. Повторите анализ.");
    }

    void failed(long request, Exception exception) {
        if (status.isDisposed() || request != generation) {
            return;
        }
        analyze.setEnabled(true);
        cancel.setEnabled(false);
        setStatus("Не удалось проверить запрос.");
        details.setText(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        tabs.setSelection(0);
        apply.setEnabled(false);
        changeSummary.setText("Исправление недоступно. Проверьте сообщение на вкладке рекомендаций.");
    }

    void cancelled(long request) {
        if (!status.isDisposed() && request == generation) {
            analyze.setEnabled(true);
            cancel.setEnabled(false);
            apply.setEnabled(false);
            details.setText("Можно изменить запрос и запустить анализ снова.");
            changeSummary.setText("Анализ отменён.");
            setStatus("Анализ отменён.");
        }
    }

    private void setStatus(String text) {
        status.setText(text);
        tabs.getParent().layout(true, true);
    }

    private void renderPlan() {
        if (advice == null) {
            return;
        }
        tree.removeAll();
        addNode(new TreeItem(tree, SWT.NONE), planVersion.getSelectionIndex() == 1 ? advice.optimized().tree() : advice.tree());
        nodeDetails.setText("Выберите узел, чтобы увидеть выражения и типы колонок.");
        List<Integer> matches = List.of();
        if (planVersion.getSelectionIndex() == 1) {
            matches = advice.optimized().rules().stream().filter(rule -> rule.status() == Analyzer.Status.FOUND)
                    .flatMap(rule -> rule.matchedNodeIds().stream()).toList();
        } else if (findings.getSelectionCount() > 0) {
            matches = ((Analyzer.RuleResult) findings.getSelection()[0].getData()).matchedNodeIds();
        }
        highlight(tree.getItem(0), matches);
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
        details.setText(rule.recommendation() + "\n\n" + rule.explanation());
        showInPlan.setEnabled(true);
        renderPlan();
    }

    private void highlight(TreeItem item, List<Integer> matches) {
        var node = (PlanNode) item.getData();
        item.setFont(matches.contains(node.id()) ? JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT) : null);
        item.setBackground(matches.contains(node.id()) ? tree.getDisplay().getSystemColor(SWT.COLOR_INFO_BACKGROUND) : null);
        item.setForeground(matches.contains(node.id()) ? tree.getDisplay().getSystemColor(SWT.COLOR_INFO_FOREGROUND) : null);
        for (TreeItem child : item.getItems()) {
            highlight(child, matches);
        }
    }

    private record SqlText(String name, String text) implements ITypedElement, IEncodedStreamContentAccessor {
        public String getName() { return name; }
        public String getType() { return TEXT_TYPE; }
        public Image getImage() { return null; }
        public String getCharset() { return StandardCharsets.UTF_8.name(); }
        public InputStream getContents() { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    }

    @Override
    public void setFocus() {
        analyze.setFocus();
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
