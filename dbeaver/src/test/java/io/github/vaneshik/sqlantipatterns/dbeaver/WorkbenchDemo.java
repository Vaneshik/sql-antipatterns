package io.github.vaneshik.sqlantipatterns.dbeaver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Table;
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

public final class WorkbenchDemo implements IStartup {
    private SQLEditor editor;
    private String script;
    private String selectedSql;
    private long deadline;
    private int phase;

    @Override
    public void earlyStartup() {
        Display.getDefault().asyncExec(() -> {
            try {
                start();
            } catch (Throwable failure) {
                fail(failure);
            }
        });
    }

    private void start() throws Exception {
        var provider = DataSourceProviderRegistry.getInstance().getDataSourceProvider("postgresql");
        var driver = provider.createDriver((DriverDescriptor) provider.getDriver("postgres-jdbc"));
        driver.setDriverLibraries(List.of());
        driver.addDriverLibrary(System.getProperty("vkr.demo.driver"), DBPDriverLibrary.FileType.jar);
        var configuration = new DBPConnectionConfiguration();
        configuration.setUrl("jdbc:postgresql://127.0.0.1:55461/sql_antipatterns_demo");
        configuration.setHostName("127.0.0.1");
        configuration.setHostPort("55461");
        configuration.setDatabaseName("sql_antipatterns_demo");
        configuration.setUserName("vnshk");
        configuration.setUserPassword("");
        var registry = DBWorkbench.getPlatform().getWorkspace().getActiveProject().getDataSourceRegistry();
        var connection = new DataSourceDescriptor(registry, "sql-antipatterns-demo", driver, configuration);
        connection.setName("ВКР · локальная тестовая БД");
        connection.setSavePassword(true);
        registry.addDataSource(connection);
        connection.connect(new VoidProgressMonitor(), true, false);
        var context = connection.getDataSource().getDefaultInstance().getDefaultContext(new VoidProgressMonitor(), true);
        try (var session = (JDBCSession) context.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Demo fixtures");
                var statement = session.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS public.customer (
                        id integer PRIMARY KEY, name varchar(100), created_at timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE TABLE IF NOT EXISTS public.orders (
                        id integer PRIMARY KEY, customer_id integer REFERENCES public.customer(id),
                        status varchar(20), amount numeric(12,2), created_at timestamptz NOT NULL DEFAULT now()
                    );
                    INSERT INTO public.customer(id,name) VALUES
                       (1,'Анна'),(2,'Борис'),(3,'Анна'),(4,'Мария'),(5,NULL),(6,'Дмитрий')
                       ON CONFLICT (id) DO NOTHING;
                   INSERT INTO public.orders(id,customer_id,status,amount) VALUES
                       (1,1,'paid',1200),(2,1,'paid',900),(3,2,'paid',1500),
                       (4,2,'new',800),(5,3,'paid',750),(6,3,'paid',600),
                       (7,4,'cancelled',300),(8,5,'paid',1100),(9,NULL,'new',200)
                       ON CONFLICT (id) DO NOTHING;
                    """);
        }
        script = Files.readString(Path.of(System.getProperty("vkr.demo.script")));
        selectedSql = script.substring(script.indexOf("SELECT"), script.indexOf(';'));
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        editor = (SQLEditor) page.openEditor(new StringEditorInput("Демонстрация SQL-антипаттернов.sql", script, false, "UTF-8"),
                "org.jkiss.dbeaver.ui.editors.sql.SQLEditor");
        editor.setDataSourceContainer(connection);
        editor.getSelectionProvider().setSelection(new TextSelection(script.indexOf("SELECT"), selectedSql.length()));
        page.activate(editor);
        PlatformUI.getWorkbench().getService(IHandlerService.class)
                .executeCommand("io.github.vaneshik.sqlantipatterns.analyze", null);
        deadline = System.nanoTime() + 60_000_000_000L;
        Display.getDefault().timerExec(150, this::check);
    }

    private void check() {
        try {
            var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            var view = (AnalysisView) page.findView(AnalysisView.ID);
            require(view != null, "Панель анализа не открылась");
            var controls = controls(view.getSite().getShell());
            var cancel = button(controls, "Отменить");
            if (cancel.getEnabled()) {
                require(System.nanoTime() < deadline, "Анализ не завершился за 60 секунд");
                Display.getDefault().timerExec(150, this::check);
                return;
            }
            var tabs = controls.stream().filter(control -> control instanceof TabFolder folder && folder.getItemCount() == 3)
                    .map(TabFolder.class::cast).findFirst().orElseThrow();
            var apply = button(controls, "Заменить запрос");
            require(apply.getEnabled(), "Исправление SQL недоступно");
            var table = controls((Composite) tabs.getItem(0).getControl()).stream().filter(Table.class::isInstance)
                    .map(Table.class::cast).findFirst().orElseThrow();
            require(table.getItemCount() >= 1, "Диагностика не найдена");
            var browser = controls.stream().filter(Browser.class::isInstance).map(Browser.class::cast).findFirst().orElseThrow();
            if (!Boolean.TRUE.equals(browser.evaluate("return !!window.showPlan && document.querySelectorAll('.node').length > 0;"))) {
                require(System.nanoTime() < deadline, "Граф не загрузился");
                Display.getDefault().timerExec(150, this::check);
                return;
            }
            if (phase == 0) {
                button(controls, "Сравнить SQL").notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
                var rewritten = controls((Composite) tabs.getItem(1).getControl()).stream()
                        .filter(StyledText.class::isInstance).map(StyledText.class::cast)
                        .filter(text -> text.getText().contains("EXISTS")).findFirst().orElseThrow().getText();
                apply.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
                require(editor.getDocument().get().contains(rewritten), "Исправление не применилось");
                editor.getTextViewer().getUndoManager().undo();
                require(editor.getDocument().get().equals(script), "Undo не восстановил запрос");
                editor.getSelectionProvider().setSelection(new TextSelection(script.indexOf("SELECT"), selectedSql.length()));
                page.activate(editor);
                AnalyzeHandler.analyze(PlatformUI.getWorkbench().getActiveWorkbenchWindow());
                phase = 1;
                Display.getDefault().timerExec(200, this::check);
                return;
            }
            if (phase == 1) {
                button(controls, "Показать в плане").notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
                phase = 2;
                Display.getDefault().timerExec(300, this::check);
                return;
            }
            require(((Number) browser.evaluate("return document.querySelectorAll('.matched > .node').length;")).intValue() == 4,
                    "Подсветка графа не совпала с диагностикой");
            var shell = view.getSite().getShell();
            shell.setText("ВКР · Демонстрация SQL-антипаттернов · локальная БД");
            shell.forceActive();
            Files.writeString(Path.of(System.getProperty("vkr.demo.result")),
                    "READY — local PostgreSQL, timestamptz metadata, Python/Z3, finding, graph, SQL rewrite and Undo verified\n");
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static Button button(List<Control> controls, String text) {
        return controls.stream().filter(control -> control instanceof Button button && button.getText().equals(text))
                .map(Button.class::cast).findFirst().orElseThrow();
    }

    private static List<Control> controls(Composite parent) {
        List<Control> result = new ArrayList<>();
        for (Control child : parent.getChildren()) {
            result.add(child);
            if (child instanceof Composite composite) result.addAll(controls(composite));
        }
        return result;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private void fail(Throwable failure) {
        try {
            var output = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(output));
            Files.writeString(Path.of(System.getProperty("vkr.demo.result")), "FAIL\n" + output);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
}
