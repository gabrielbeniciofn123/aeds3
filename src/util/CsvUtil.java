package util;

import java.util.ArrayList;
import java.util.List;

/** CSV/DSV de uma linha por registro; aspas duplas escapadas são representadas por "". */
public final class CsvUtil {
    private CsvUtil() {}

    public static List<String> separarLinha(String linha, char separador) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean entreAspas = false;
        boolean fechouAspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (entreAspas) {
                if (c == '"') {
                    if (i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                        atual.append('"'); i++;
                    } else { entreAspas = false; fechouAspas = true; }
                } else atual.append(c);
            } else if (c == separador) {
                campos.add(atual.toString().trim()); atual.setLength(0); fechouAspas = false;
            } else if (fechouAspas) {
                if (!Character.isWhitespace(c)) throw new IllegalArgumentException("Caractere após fechamento de aspas.");
            } else if (c == '"') {
                if (!atual.toString().isBlank()) throw new IllegalArgumentException("Aspas devem iniciar o campo.");
                atual.setLength(0); entreAspas = true;
            } else atual.append(c);
        }
        if (entreAspas) throw new IllegalArgumentException("Aspas não fechadas; cada registro deve ocupar uma linha.");
        campos.add(atual.toString().trim());
        return campos;
    }
}
