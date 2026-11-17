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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Tree;
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
    private static final String SQL = "SELECT DISTINCT c.id, c.name FROM smoke_customer c "
            + "JOIN smoke_orders o ON c.id = o.customer_id WHERE o.status = 'paid'";
    private SQLEditor editor;
    private String script;
    private long deadline;

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
            var controls = controls(view.getSite().getShell());
            var apply = controls.stream().filter(control -> control instanceof Button button
                    && button.getText().equals("Применить исправление в редакторе"))
                    .map(Button.class::cast).findFirst().orElseThrow();
            if (!apply.isEnabled()) {
                var error = controls.stream().filter(control -> control instanceof Label label
                        && label.getText().equals("Анализ завершился ошибкой.")).findFirst();
                require(error.isEmpty(), "Analysis failed in the OSGi runtime");
                require(System.nanoTime() < deadline, "Timed out waiting for analysis");
                Display.getDefault().timerExec(100, this::check);
                return;
            }
            require(controls.stream().anyMatch(control -> control instanceof Tree tree && tree.getItemCount() > 0
                    && tree.getItem(0).getText().contains("LogicalAggregate")), "Missing Calcite tree");
            require(controls.stream().anyMatch(control -> control instanceof Table table
                    && java.util.Arrays.stream(table.getItems()).anyMatch(item -> item.getText(0).equals("Найдено")
                        && item.getText(1).contains("DISTINCT"))), "Missing finding");
            var source = EditorSnapshot.capture(editor);
            apply.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            require(editor.getDocument().get().contains("EXISTS"), "Apply did not update the editor");
            require(editor.getDocument().get().startsWith("SELECT 42;\n"), "Apply changed previous statement");
            require(editor.getDocument().get().endsWith(";\nSELECT 99;"), "Apply changed next statement");
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
            finish(null);
        } catch (Throwable failure) {
            finish(failure);
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
            String result = "PASS — command, PostgreSQL metadata, Calcite OSGi loading, finding, tree, selection, apply, Undo, stale edit\n";
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
        PlatformUI.getWorkbench().close();
    }
}
