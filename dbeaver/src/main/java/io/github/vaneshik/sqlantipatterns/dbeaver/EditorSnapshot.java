package io.github.vaneshik.sqlantipatterns.dbeaver;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

record EditorSnapshot(SQLEditor editor, IDocument document, String fullText,
                      int offset, int length, String sql, DBCExecutionContext context,
                      DBSObject catalog, DBSObject schema) {
    static EditorSnapshot capture(SQLEditor editor) throws BadLocationException {
        IDocument document = editor.getDocument();
        var selection = editor.getSelectionProvider().getSelection();
        int offset;
        int length;
        if (selection instanceof ITextSelection text && text.getLength() > 0) {
            offset = text.getOffset();
            length = text.getLength();
        } else if (editor.extractActiveQuery() instanceof SQLQuery query) {
            offset = query.getOffset();
            length = query.getLength();
        } else {
            throw new IllegalArgumentException("Поставьте курсор в SELECT или выделите один запрос.");
        }
        var context = editor.getExecutionContext();
        if (context == null || !context.isConnected()) {
            throw new IllegalArgumentException("Подключите SQL-редактор к PostgreSQL.");
        }
        return new EditorSnapshot(editor, document, document.get(), offset, length,
                document.get(offset, length), context,
                context.getContextDefaults().getDefaultCatalog(), context.getContextDefaults().getDefaultSchema());
    }

    boolean isCurrent() {
        return !editor.isDisposed() && editor.getDocument() == document
                && fullText.equals(document.get()) && editor.getExecutionContext() == context
                && context.isConnected() && context.getContextDefaults().getDefaultCatalog() == catalog
                && context.getContextDefaults().getDefaultSchema() == schema;
    }

    void apply(String suggestion) throws BadLocationException {
        if (!isCurrent()) {
            throw new IllegalStateException("Текст или подключение изменились. Повторите анализ.");
        }
        if (!editor.isEditable() || !editor.validateEditorInputState()) {
            throw new IllegalStateException("Редактор недоступен для изменения.");
        }
        String replacement = suggestion.stripTrailing();
        if (!sql.stripTrailing().endsWith(";") && replacement.endsWith(";")) {
            replacement = replacement.substring(0, replacement.length() - 1);
        }
        var undo = editor.getTextViewer().getRewriteTarget();
        undo.beginCompoundChange();
        try {
            document.replace(offset, length, replacement);
        } finally {
            undo.endCompoundChange();
        }
        editor.selectAndReveal(offset, replacement.length());
        editor.setFocus();
    }
}
