package io.github.vaneshik.sqlantipatterns.dbeaver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriverLibrary;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
import org.jkiss.dbeaver.registry.DataSourceProviderRegistry;
import org.jkiss.dbeaver.registry.driver.DriverDescriptor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.editors.StringEditorInput;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

public final class WorkbenchSmoke implements IStartup {
    private static final String SQL = "SELECT DISTINCT c.id, c.name\nFROM smoke_customer c\n"
            + "JOIN smoke_orders o ON c.id = o.customer_id\nWHERE o.status = 'paid'";
    private SQLEditor editor;
    private String script;
    private long deadline;
    private int phase;

    @Override
    public void earlyStartup() {
        Display.getDefault().asyncExec(() -> {
            try {
                start();
            } catch (Throwable failure) {
                finish(failure);
            }
        });
    }

    private void start() throws Exception {
        var provider = DataSourceProviderRegistry.getInstance().getDataSourceProvider("postgresql");
        var driver = provider.createDriver((DriverDescriptor) provider.getDriver("postgres-jdbc"));
        driver.setDriverLibraries(List.of());
        driver.addDriverLibrary(System.getProperty("vkr.smoke.driver"), DBPDriverLibrary.FileType.jar);
        var configuration = new DBPConnectionConfiguration();
        configuration.setUrl(System.getProperty("vkr.smoke.url"));
        configuration.setHostName("127.0.0.1");
        configuration.setHostPort(System.getProperty("vkr.smoke.port"));
        configuration.setDatabaseName("postgres");
        configuration.setUserName(System.getProperty("vkr.smoke.user"));
        configuration.setUserPassword("");
        var registry = DBWorkbench.getPlatform().getWorkspace().getActiveProject().getDataSourceRegistry();
        var connection = new DataSourceDescriptor(registry, "vkr-smoke", driver, configuration);
        connection.setName("MVP PostgreSQL smoke test");
        connection.setSavePassword(true);
        registry.addDataSource(connection);
        connection.connect(new VoidProgressMonitor(), true, false);
        var context = connection.getDataSource().getDefaultInstance().getDefaultContext(new VoidProgressMonitor(), true);
        try (var session = (JDBCSession) context.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Test fixtures");
                var statement = session.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS smoke_customer (id integer PRIMARY KEY, name varchar(100));
                    CREATE TABLE IF NOT EXISTS smoke_orders (id integer PRIMARY KEY, customer_id integer, status varchar(20));
                    """);
        }
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        script = "SELECT 42;\n" + SQL + ";\nSELECT 99;";
        editor = (SQLEditor) page.openEditor(new StringEditorInput("MVP smoke.sql", script, false, "UTF-8"),
                "org.jkiss.dbeaver.ui.editors.sql.SQLEditor");
        editor.setDataSourceContainer(connection);
        editor.getSelectionProvider().setSelection(new TextSelection(25, 0));
        require(EditorSnapshot.capture(editor).sql().strip().replaceFirst(";$", "").equals(SQL),
                "Caret extraction selected a different statement");
        editor.getSelectionProvider().setSelection(new TextSelection(11, SQL.length()));
        page.activate(editor);
        var handler = PlatformUI.getWorkbench().getService(IHandlerService.class);
        handler.executeCommand("io.github.vaneshik.sqlantipatterns.analyze", null);
        deadline = System.nanoTime() + 60_000_000_000L;
        Display.getDefault().timerExec(100, this::check);
    }

    private void check() {
        try {
            var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            var view = (AnalysisView) page.findView(AnalysisView.ID);
            require(view != null, "Command did not open the analysis view");
            var tabs = controls(view.getSite().getShell()).stream().filter(control -> control instanceof TabFolder folder
                    && folder.getItemCount() == 3 && folder.getItem(0).getText().startsWith("Рекомендации"))
                    .map(TabFolder.class::cast).findFirst().orElseThrow();
            var controls = controls(tabs.getParent());
            var apply = button(controls, "Заменить запрос");
            var cancel = button(controls, "Отменить");
            var analyze = button(controls, "Анализировать");
            var preview = button(controls, "Сравнить SQL");
            if (cancel.isEnabled()) {
                require(System.nanoTime() < deadline, "Timed out waiting for analysis");
                Display.getDefault().timerExec(100, this::check);
                return;
            }
            var resultControls = controls((Composite) tabs.getItem(0).getControl());
            var findings = resultControls.stream().filter(Table.class::isInstance).map(Table.class::cast).findFirst().orElseThrow();
            var details = resultControls.stream().filter(StyledText.class::isInstance).map(StyledText.class::cast).findFirst().orElseThrow();
            require(analyze.isEnabled(), "Analyze did not become available after completion");
            if (phase == 1) {
                require(findings.getItemCount() == 0, "Non-findings appear in the recommendations list");
                require(!preview.isEnabled() && !apply.isEnabled(), "No-change query still offers an edit");
                require(details.getText().contains("не обнаружены"), "Missing empty state");
                reanalyze("SELECT id FROM smoke_customer UNION SELECT id FROM smoke_orders", 2);
                return;
            }
            if (phase == 2) {
                var scope = resultControls.stream().filter(Link.class::isInstance).map(Link.class::cast).findFirst().orElseThrow();
                require(scope.getText().contains("(1)"), "Unsupported rule is hidden without an explanation");
                scope.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
                require(details.getText().contains("операций над множествами"), "Scope link did not explain the limitation");
                reanalyze("SELECT missing_column FROM smoke_customer", 3);
                return;
            }
            if (phase == 3) {
                require(controls.stream().anyMatch(control -> control instanceof Label label
                        && label.getText().equals("Не удалось проверить запрос.")), "Missing error state");
                require(details.getText().contains("missing_column"), "SQL error did not reach the user");
                require(!apply.isEnabled(), "Error state permits applying a previous result");
                reanalyze(SQL, 4);
                cancel.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
                return;
            }
            if (phase == 4) {
                require(controls.stream().anyMatch(control -> control instanceof Label label
                        && label.getText().equals("Анализ отменён.")), "Cancelled result was replaced by a late callback");
                require(!apply.isEnabled() && findings.getItemCount() == 0, "Cancelled analysis exposed a result");
                reanalyze(SQL, 5);
                return;
            }
            require(apply.isEnabled(), "Analysis did not produce the expected correction");
            require(findings.getItemCount() == 1 && findings.getItem(0).getText().contains("DISTINCT"), "Missing finding");
            if (phase == 5) {
                finish(null);
                return;
            }
            require(tabs.getSelectionIndex() == 0 && !apply.isVisible(), "Initial view bypasses the recommendations and preview");
            String explanation = details.getText();
            require(explanation.contains("EXISTS"), "Recommendation is missing its next action");
            screenshot(view, "recommendations");
            button(controls, "Показать в плане").notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            require(tabs.getSelectionIndex() == 2, "Plan action did not open its tab");
            var tree = controls.stream().filter(Tree.class::isInstance).map(Tree.class::cast).findFirst().orElseThrow();
            require(tree.getItem(0).getText().contains("LogicalAggregate"), "Missing original Calcite tree");
            tree.setSelection(tree.getItem(0));
            tree.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            require(details.getText().equals(explanation), "Inspecting a node erased the recommendation");
            var version = controls.stream().filter(Combo.class::isInstance).map(Combo.class::cast).findFirst().orElseThrow();
            version.select(1);
            version.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            require(tree.getItem(0).getText().contains("LogicalProject"), "After selection did not rebuild tree");
            version.select(0);
            version.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            screenshot(view, "plan");
            preview.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            require(tabs.getSelectionIndex() == 1 && apply.isVisible(), "Preview did not reveal the apply action");
            var sqlTexts = controls((Composite) tabs.getItem(1).getControl()).stream()
                    .filter(StyledText.class::isInstance).map(StyledText.class::cast).filter(Control::isVisible).toList();
            require(sqlTexts.stream().anyMatch(text -> text.getText().equals(SQL)), "Diff lost the source SQL");
            var rewritten = sqlTexts.stream().filter(text -> text.getText().contains("EXISTS")).findFirst().orElseThrow();
            var original = sqlTexts.stream().filter(text -> text.getText().equals(SQL)).findFirst().orElseThrow();
            require(original.toDisplay(0, 0).x < rewritten.toDisplay(0, 0).x, "SQL comparison must show before on the left");
            require(sqlTexts.stream().noneMatch(StyledText::getEditable), "Diff should be read-only");
            String replacement = rewritten.getText();
            screenshot(view, "sql-diff");
            var source = EditorSnapshot.capture(editor);
            apply.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            require(editor.getDocument().get().equals("SELECT 42;\n" + replacement + ";\nSELECT 99;"),
                    "Applied SQL differs from the preview or changed adjacent statements");
            editor.getTextViewer().getUndoManager().undo();
            require(editor.getDocument().get().equals(script), "Undo did not restore the whole source");
            editor.getDocument().replace(0, 0, "-- changed\n");
            require(!apply.isEnabled(), "Edited source still allows apply");
            try {
                source.apply("SELECT 0");
                throw new AssertionError("Stale result overwrote edited SQL");
            } catch (IllegalStateException expected) {
                require(editor.getDocument().get().startsWith("-- changed"), "Stale text was replaced");
            }
            reanalyze("SELECT id FROM smoke_customer", 1);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void reanalyze(String sql, int nextPhase) throws Exception {
        phase = nextPhase;
        editor.getDocument().set(sql);
        editor.getSelectionProvider().setSelection(new TextSelection(0, sql.length()));
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = (AnalysisView) page.findView(AnalysisView.ID);
        page.activate(view);
        button(controls(view.getSite().getShell()), "Анализировать")
                .notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
        deadline = System.nanoTime() + 60_000_000_000L;
        Display.getDefault().timerExec(250, this::check);
    }

    private static Button button(List<Control> controls, String text) {
        return controls.stream().filter(control -> control instanceof Button button && button.getText().equals(text))
                .map(Button.class::cast).findFirst().orElseThrow();
    }

    private static void screenshot(AnalysisView view, String name) {
        var shell = view.getSite().getShell();
        shell.layout(true, true);
        shell.update();
        var size = shell.getSize();
        var image = new Image(shell.getDisplay(), size.x, size.y);
        var graphics = new GC(image);
        try {
            if (shell.print(graphics)) {
                var loader = new ImageLoader();
                loader.data = new org.eclipse.swt.graphics.ImageData[] {image.getImageData()};
                loader.save(System.getProperty("vkr.smoke.result") + "-" + name + ".png", SWT.IMAGE_PNG);
            }
        } finally {
            graphics.dispose();
            image.dispose();
        }
    }

    private static List<Control> controls(Composite parent) {
        List<Control> result = new ArrayList<>();
        for (Control child : parent.getChildren()) {
            result.add(child);
            if (child instanceof Composite composite) {
                result.addAll(controls(composite));
            }
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void finish(Throwable failure) {
        try {
            String result = "PASS — command, PostgreSQL metadata, recommendations, native SQL diff, independent node details, "
                    + "before/after tree, caret, selection, preview/apply equality, Undo, stale edit, panel reanalysis, "
                    + "empty/scope/error states, cancellation and recovery\n";
            if (failure != null) {
                var writer = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(writer));
                result = "FAIL\n" + writer;
            }
            Files.writeString(Path.of(System.getProperty("vkr.smoke.result")), result);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        if (editor != null) {
            PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeEditor(editor, false);
        }
        DBWorkbench.getPlatform().getPreferenceStore().setValue("org.jkiss.dbeaver.core.confirm.exit", "never");
        PlatformUI.getWorkbench().close();
    }
}
