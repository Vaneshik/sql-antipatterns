package io.github.vaneshik.sqlantipatterns.dbeaver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vaneshik.sqlantipatterns.PlanNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;

final class PlanGraph extends Composite {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Browser browser;
    private String pending = "null";
    private boolean loaded;

    PlanGraph(Composite parent) {
        super(parent, SWT.NONE);
        setLayout(new FillLayout());
        browser = new Browser(this, SWT.NONE);
        browser.setBackground(parent.getBackground());
        new BrowserFunction(browser, "graphReady") {
            @Override
            public Object function(Object[] arguments) {
                loaded = true;
                browser.execute("window.showPlan(" + pending + ");");
                return null;
            }
        };
        browser.setText(resource("/graph.html")
                .replace("/*GRAPH_STYLES*/", resource("/web/plan-tree.css"))
                .replace("/*GRAPH_SCRIPT*/", resource("/web/plan-tree.js")));
    }

    void show(PlanNode tree, List<Integer> matched, List<Integer> proved) {
        var color = getParent().getBackground().getRGB();
        try {
            pending = JSON.writeValueAsString(Map.of("tree", tree, "matched", matched, "proved", proved,
                    "dark", color.red + color.green + color.blue < 384));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось подготовить граф плана", exception);
        }
        if (loaded) {
            browser.execute("window.showPlan(" + pending + ");");
        }
    }

    void clear() {
        pending = "null";
        if (loaded) {
            browser.execute("window.showPlan(null);");
        }
    }

    private static String resource(String name) {
        try (var stream = PlanGraph.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось загрузить интерфейс графа", exception);
        }
    }
}
