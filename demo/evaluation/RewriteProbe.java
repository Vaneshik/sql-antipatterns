import io.github.vaneshik.sqlantipatterns.Analyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

public class RewriteProbe {
    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        var cases = json.readTree(Files.readString(Path.of(args[0])));
        var results = new ArrayList<Object>();
        for (var item : cases) {
            var result = new LinkedHashMap<String, Object>();
            result.put("id", item.get("id").asText());
            try {
                var before = new Analyzer().analyze(item.get("sql").asText());
                var after = before.optimized();
                result.put("sql", after.sql());
                result.put("changed", after.changed());
                result.put("plan", after.plan());
                result.put("beforePlan", before.plan());
                var rebuilt = new Analyzer().analyze(after.sql());
                result.put("stable", !rebuilt.optimized().changed() && rebuilt.plan().equals(after.plan()));
            } catch (Exception e) {
                result.put("error", e.toString());
            }
            results.add(result);
        }
        System.out.println(json.writeValueAsString(results));
    }
}
