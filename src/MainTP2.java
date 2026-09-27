import dao.ArquivoIndexado;
import dao.ArquivoIndexado.Metodo;
import model.Carro;

import java.io.EOFException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Menu do TP2. Main permanece disponível para reproduzir o TP1. */
public class MainTP2 {
    private final Scanner entrada = new Scanner(System.in);
    private final ArquivoIndexado arquivo;
    private MainTP2(ArquivoIndexado arquivo) { this.arquivo = arquivo; }

    public static void main(String[] args) {
        Path pasta = Paths.get("data/tp2"); int ordem = 16; double percentual = 2;
        boolean ajuda = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--pasta": pasta = Paths.get(valorArgumento(args, ++i)); break;
                    case "--ordem": ordem = Integer.parseInt(valorArgumento(args, ++i)); break;
                    case "--percentual": percentual = Double.parseDouble(valorArgumento(args, ++i).replace(',', '.')); break;
                    case "--ajuda": ajuda = true; break;
                    default: throw new IllegalArgumentException("Argumento desconhecido: " + args[i]);
                }
            }
            if (ajuda) {
                System.out.println("java -cp out MainTP2 [--pasta data/tp2] [--ordem 16] [--percentual 2]");
                System.out.println("A ordem é o máximo de filhos por página. O percentual calcula X = max(1, ceil(N inicial * percentual/100)).");
                return;
            }
            // Preserva o TP1. A primeira execução aproveita seu binário, se ele já existir.
            Path anterior = Paths.get("data/dados.db");
            if (pasta.equals(Paths.get("data/tp2")) && !Files.exists(pasta.resolve("dados.db")) && Files.exists(anterior)) {
                Files.createDirectories(pasta); Files.copy(anterior, pasta.resolve("dados.db"));
                System.out.println("Uma cópia da base do TP1 foi trazida para data/tp2.");
            }
            try (ArquivoIndexado arquivo = new ArquivoIndexado(pasta, ordem, percentual)) {
                System.out.println("TP2 AEDS III — Carros | Grupo 15");
                System.out.println("Gabriel Benicio Fonseca e Rhayner Martins");
                System.out.println("Pasta: " + pasta + " | ordem=" + ordem + " | percentual=" + percentual + "%");
                new MainTP2(arquivo).menu();
            }
        } catch (Exception e) {
            System.err.println("Não foi possível executar: " + e.getMessage());
            System.exit(1);
        }
    }
    private static String valorArgumento(String[] args, int indice) {
        if (indice >= args.length) throw new IllegalArgumentException("Falta o valor de " + args[indice - 1] + ". Use --ajuda.");
        return args[indice];
    }
    private void menu() throws Exception {
        while (true) {
            System.out.println("\n1 Importar CSV     2 Criar carro      3 Consultar por ID");
            System.out.println("4 Atualizar carro  5 Excluir carro    6 Pesquisar nas listas");
            System.out.println("7 Ver índices      8 Auditar tudo     9 Reconstruir índices    0 Sair");
            try {
                int opcao = inteiro("Escolha: ", null);
                switch (opcao) {
                    case 0: System.out.println("Base salva. Até mais!"); return;
                    case 1: importar(); break;
                    case 2: criar(); break;
                    case 3: consultar(); break;
                    case 4: atualizar(); break;
                    case 5: excluir(); break;
                    case 6: pesquisar(); break;
                    case 7: System.out.println(arquivo.resumo()); break;
                    case 8: System.out.println(arquivo.auditar()); break;
                    case 9: arquivo.reconstruirIndices(); System.out.println("Índices reconstruídos e salvos."); break;
                    default: System.out.println("Escolha uma opção de 0 a 9.");
                }
            } catch (EOFException e) { System.out.println("Entrada encerrada. Base salva."); return; }
            catch (Exception e) { System.out.println("Operação não concluída: " + e.getMessage()); }
        }
    }
    private void importar() throws Exception {
        String caminho = texto("CSV [data/base.csv]: ");
        if (caminho.isEmpty()) caminho = "data/base.csv";
        System.out.println("A carga substitui a base do TP2 e recalcula a capacidade inicial do hash.");
        if (!texto("Digite IMPORTAR para confirmar: ").equals("IMPORTAR")) { System.out.println("Carga cancelada."); return; }
        System.out.println("Importando e construindo os quatro índices...");
        System.out.println("Carga concluída: " + arquivo.importar(Paths.get(caminho)) + " carros.");
        System.out.println(arquivo.resumo());
    }
    private Metodo metodo() throws Exception {
        while (true) {
            int n = inteiro("Índice desta operação (1 B+, 2 Hash, 3 Lista invertida): ", null);
            if (n >= 1 && n <= 3) return Metodo.values()[n - 1];
            System.out.println("Escolha 1, 2 ou 3.");
        }
    }
    private static class Filtro { Integer ano; String termo; }
    private Filtro filtro() throws Exception {
        Filtro f = new Filtro();
        f.ano = inteiro("Ano (ENTER para não filtrar): ", null, true);
        f.termo = texto("Característica inteira, ex.: gas ou 8 cylinders (ENTER para não filtrar): ");
        if (f.ano == null && f.termo.isEmpty()) throw new IllegalArgumentException("Preencha ao menos um filtro.");
        return f;
    }
    private Filtro filtro(Metodo m) throws Exception { return m == Metodo.LISTA ? filtro() : new Filtro(); }
    private void criar() throws Exception {
        Metodo m = metodo();
        Carro c = editar(new Carro(), false);
        int id = arquivo.criar(c, m);
        System.out.println("Carro criado: id=" + id + " | todos os índices atualizados.");
        System.out.println(arquivo.getUltimoAcesso());
    }
    private void consultar() throws Exception {
        Metodo m = metodo(); Filtro f = filtro(m); int id = inteiro("ID: ", null);
        Carro c = arquivo.ler(id, m, f.ano, f.termo);
        System.out.println(c == null ? "Nenhum carro encontrado nesse índice/filtro." : c);
        System.out.println(arquivo.getUltimoAcesso());
    }
    private void atualizar() throws Exception {
        Metodo m = metodo(); Filtro f = filtro(m); int id = inteiro("ID a atualizar: ", null);
        Carro c = arquivo.ler(id, m, f.ano, f.termo);
        if (c == null) { System.out.println("Nenhum carro encontrado nesse índice/filtro."); return; }
        System.out.println("Atual: " + c);
        System.out.println("ENTER mantém o valor; - limpa as características.");
        Carro novo = editar(c, true);
        long antes = arquivo.endereco(id, m, f.ano, f.termo);
        if (!arquivo.atualizar(novo, m, f.ano, f.termo)) {
            System.out.println("O carro não foi atualizado: não foi encontrado no índice/filtro.");
            return;
        }
        System.out.println(arquivo.getUltimoAcesso());
        long depois = arquivo.endereco(id, m, novo.getAno(), null);
        System.out.println("Atualizado. Endereço " + antes + " -> " + depois + ". Todos os índices atualizados.");
    }
    private void excluir() throws Exception {
        Metodo m = metodo(); Filtro f = filtro(m); int id = inteiro("ID a excluir: ", null);
        Carro c = arquivo.ler(id, m, f.ano, f.termo);
        if (c == null) { System.out.println("Nenhum carro encontrado nesse índice/filtro."); return; }
        System.out.println(c);
        if (!texto("Digite EXCLUIR para confirmar: ").equals("EXCLUIR")) { System.out.println("Exclusão cancelada."); return; }
        System.out.println(arquivo.excluir(id, m, f.ano, f.termo) ? "Lápide marcada e entradas removidas de todos os índices." : "Carro não encontrado.");
        System.out.println(arquivo.getUltimoAcesso());
    }
    private void pesquisar() throws Exception {
        System.out.println("Pesquisa por listas invertidas. Dois filtros usam interseção (E).");
        Filtro f = filtro(); List<Carro> encontrados = arquivo.pesquisar(f.ano, f.termo);
        int limite = Math.min(20, encontrados.size());
        for (int i = 0; i < limite; i++) System.out.println(encontrados.get(i));
        System.out.println("Total: " + encontrados.size() + " (exibidos " + limite + ").");
        System.out.println(arquivo.getUltimoAcesso());
    }
    private Carro editar(Carro atual, boolean manter) throws Exception {
        String nome;
        do { nome = texto("Nome" + (manter ? " [" + atual.getNome() + "]" : "") + ": "); }
        while (!manter && nome.isEmpty());
        if (nome.isEmpty()) nome = atual.getNome();
        String lista = texto("Características separadas por |" + (manter ? " [" + String.join("|", atual.getCaracteristicas()) + "]" : "") + ": ");
        List<String> termos = new ArrayList<>();
        if (manter && lista.isEmpty()) termos = atual.getCaracteristicas();
        else if (!lista.equals("-")) for (String t : lista.split("\\|")) if (!t.trim().isEmpty()) termos.add(t.trim());
        int ano;
        do { ano = inteiro("Ano" + (manter ? " [" + atual.getAno() + "]" : "") + ": ", manter ? atual.getAno() : null); }
        while (ano <= 0);
        LocalDate data;
        while (true) {
            String valor = texto("Data AAAA-MM-DD" + (manter ? " [" + atual.getDataRegistro() + "]" : "") + ": ");
            try { data = manter && valor.isEmpty() ? atual.getDataRegistro() : LocalDate.parse(valor); break; }
            catch (RuntimeException e) { System.out.println("Use uma data válida, por exemplo 2026-09-14."); }
        }
        return new Carro(atual.getId(), atual.getCodigo(), nome, data, termos, ano);
    }
    private String texto(String prompt) throws EOFException {
        System.out.print(prompt);
        if (!entrada.hasNextLine()) throw new EOFException();
        return entrada.nextLine().trim();
    }
    private Integer inteiro(String prompt, Integer padrao) throws Exception { return inteiro(prompt, padrao, false); }
    private Integer inteiro(String prompt, Integer padrao, boolean vazio) throws Exception {
        while (true) {
            String s = texto(prompt);
            if (s.isEmpty() && (vazio || padrao != null)) return padrao;
            try { return Integer.valueOf(s); } catch (NumberFormatException e) { System.out.println("Digite um inteiro válido."); }
        }
    }
}
