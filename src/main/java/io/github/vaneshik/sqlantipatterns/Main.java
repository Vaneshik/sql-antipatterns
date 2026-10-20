package io.github.vaneshik.sqlantipatterns;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;

public final class Main {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Использование: mvn compile exec:java -Dexec.args=\"путь/к/запросу.sql\"");
            System.exit(2);
        }
        try {
            String sql = Files.readString(Path.of(args[0]));
            Analyzer.Result result = new Analyzer().analyze(sql);
            System.out.println("SQL\n" + sql.strip());
            System.out.println("\nЛогический план\n" + result.plan());
            String label = switch (result.status()) {
                case FOUND -> "Найдено: DISTINCT + JOIN";
                case NOT_FOUND -> "Правило DISTINCT + JOIN не сработало";
                case UNSUPPORTED -> "Запрос вне поддерживаемого подмножества";
            };
            System.out.println(label);
            System.out.println(result.explanation());
            if (result.status() == Analyzer.Status.FOUND) {
                System.out.println(result.recommendation());
            }
        } catch (IOException | SqlParseException | ValidationException | RelConversionException e) {
            System.err.println("Ошибка анализа: " + e.getMessage());
            System.exit(2);
        }
    }
}
