package io.github.vaneshik.sqlantipatterns.dbeaver;

import io.github.vaneshik.sqlantipatterns.Analyzer;
import io.github.vaneshik.sqlantipatterns.PostgresSchema;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

public final class AnalyzeHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) {
        analyze(HandlerUtil.getActiveWorkbenchWindow(event));
        return null;
    }

    static void analyze(IWorkbenchWindow window) {
        if (window == null) {
            return;
        }
        if (!(window.getActivePage().getActiveEditor() instanceof SQLEditor editor)) {
            MessageDialog.openInformation(window.getShell(), "SQL-антипаттерны", "Откройте SQL-редактор с подключением PostgreSQL.");
            return;
        }
        try {
            var source = EditorSnapshot.capture(editor);
            var view = (AnalysisView) window.getActivePage().showView(AnalysisView.ID);
            long request = view.beginAnalysis(source);
            var display = window.getShell().getDisplay();
            Job job = new Job("Анализ SQL-антипаттернов") {
                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    ClassLoader previous = Thread.currentThread().getContextClassLoader();
                    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
                    try (var session = source.context().openSession(new DefaultProgressMonitor(monitor),
                            DBCExecutionPurpose.META, "SQL antipattern metadata")) {
                        if (!(session instanceof JDBCSession jdbc)) {
                            throw new IllegalArgumentException("Нужно JDBC-подключение PostgreSQL.");
                        }
                        monitor.beginTask("Метаданные и логический план", 2);
                        var schema = PostgresSchema.load(jdbc, source.sql());
                        monitor.worked(1);
                        if (monitor.isCanceled()) {
                            if (!display.isDisposed()) {
                                display.asyncExec(() -> view.cancelled(request));
                            }
                            return Status.CANCEL_STATUS;
                        }
                        var advice = new Analyzer().analyze(source.sql(), schema);
                        if (!display.isDisposed()) {
                            display.asyncExec(() -> {
                                if (monitor.isCanceled()) {
                                    view.cancelled(request);
                                } else {
                                    view.complete(request, advice);
                                }
                            });
                        }
                        return Status.OK_STATUS;
                    } catch (Exception exception) {
                        if (!display.isDisposed()) {
                            display.asyncExec(() -> view.failed(request, exception));
                        }
                        return new Status(IStatus.ERROR, "io.github.vaneshik.sqlantipatterns.dbeaver",
                                "Не удалось проанализировать SQL", exception);
                    } finally {
                        Thread.currentThread().setContextClassLoader(previous);
                        monitor.done();
                    }
                }
            };
            view.setAnalysisJob(job);
            job.schedule();
        } catch (Exception exception) {
            MessageDialog.openError(window.getShell(), "SQL-антипаттерны", exception.getMessage());
        }
    }
}
