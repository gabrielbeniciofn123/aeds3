import dao.ArquivoIndexado;
import dao.ArquivoIndexado.Metodo;
import model.Carro;
import util.CsvUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** Regressões encontradas na revisão: CSV, preservação dos arquivos e menu real. */
public final class TesteRevisaoTP2 {
    private static int verificacoes;
    private static void conferir(boolean ok, String mensagem) {
        verificacoes++; if (!ok) throw new AssertionError(mensagem);
    }
    private interface Acao { void executar() throws Exception; }
    private static void deveFalhar(Acao acao, String mensagem) throws Exception {
        boolean falhou = false;
        try { acao.executar(); } catch (IOException | IllegalArgumentException esperado) { falhou = true; }
        conferir(falhou, mensagem);
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get("build-test-tp2"));
        Path raiz = Files.createTempDirectory(Paths.get("build-test-tp2"), "revisao-");
        csv(raiz.resolve("csv")); bancoTruncado(raiz.resolve("truncado"));
        leituraDireta(raiz.resolve("leitura-direta")); menu(raiz.resolve("menu"));
        System.out.println("REVISÃO TP2 APROVADA: " + verificacoes + " verificações de CSV, preservação, leitura indexada e menu.");
    }
    private static void leituraDireta(Path pasta) throws Exception {
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) {
            a.criar(new Carro(0, "", "Primeiro", LocalDate.of(2026,9,14), Arrays.asList("gas"), 2020), Metodo.ARVORE);
            a.criar(new Carro(0, "", "Outro ano", LocalDate.of(2026,9,14), Arrays.asList("gas"), 2021), Metodo.HASH);
            Carro alvo = new Carro(0, "", "Alvo", LocalDate.of(2026,9,14), Arrays.asList("diesel"), 2020);
            int id = a.criar(alvo, Metodo.LISTA);
            conferir(a.pesquisar(2020, null).size() == 2, "Lista de anos independente");
            conferir(a.pesquisar(null, "gas").size() == 2, "Lista de características independente");
            conferir(a.pesquisar(2020, "gas").size() == 1, "Pesquisa combinada faz interseção, não união");
            conferir(a.pesquisar(2021, "diesel").isEmpty(), "Duas listas com resultados separados não produzem falso positivo");

            // Em uma base descartável, um registro anterior ilegível distingue
            // uma busca por endereço de uma varredura sequencial disfarçada.
            // Conservamos tamanho e data para não pedir reconstrução automática.
            Path banco = pasta.resolve("dados.db");
            java.nio.file.attribute.FileTime modificacao = Files.getLastModifiedTime(banco);
            try (RandomAccessFile dados = new RandomAccessFile(banco.toFile(), "rw")) {
                dados.seek(5); int tamanhoOriginal = dados.readInt();
                dados.seek(5); dados.writeInt(Integer.MAX_VALUE);
                Files.setLastModifiedTime(banco, modificacao);
                try {
                    for (Metodo metodo : Metodo.values()) {
                        conferir(alvo.equals(a.ler(id, metodo, 2020, "diesel")),
                                "Consulta " + metodo + " alcança diretamente o endereço, sem ler registro anterior");
                    }
                    conferir(a.pesquisar(2020, "diesel").equals(Collections.singletonList(alvo)),
                            "Interseção consulta apenas os endereços dos candidatos");
                } finally {
                    dados.seek(5); dados.writeInt(tamanhoOriginal);
                    Files.setLastModifiedTime(banco, modificacao);
                }
            }
            conferir(a.auditar().contains("3 registros ativos"), "Base de teste restaurada e índices conferidos");
        }
        System.out.println("OK: acesso direto pelo endereço dos três índices e interseção das listas independentes.");
    }
    private static void csv(Path pasta) throws Exception {
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) {
            Path origem = pasta.resolve("entrada.csv");
            Files.writeString(origem, "\uFEFF\n\nnome;caracteristicas;ano;data_registro\n\"Carro; edição \"\"especial\"\"\";gas|manual;2020;2026-09-14\n");
            conferir(a.importar(origem) == 1, "BOM e linhas vazias antes do cabeçalho");
            conferir(a.ler(1, Metodo.HASH).getNome().equals("Carro; edição \"especial\""), "Separador e aspas dentro do campo");
            conferir(CsvUtil.separarLinha("a;;c;", ';').equals(Arrays.asList("a", "", "c", "")), "Campos vazios preservados");
            Files.writeString(origem, "Carro com caracteristicas;gas;2020;2026-09-14\n");
            conferir(a.importar(origem) == 1, "Dado inicial com palavra caracteristicas não é cabeçalho");
            conferir(a.ler(1, Metodo.ARVORE).getNome().equals("Carro com caracteristicas"), "Primeiro registro não foi descartado");
            byte[] antes = Files.readAllBytes(pasta.resolve("dados.db"));
            for (String linha : Arrays.asList("Carro;gas;2020;\"2026-09-14", "\"Carro\"x;gas;2020;2026-09-14", "Car\"ro;gas;2020;2026-09-14", "Carro;gas;2020", "Carro;gas;ano;2026-09-14")) {
                Files.writeString(origem, linha+"\n");
                deveFalhar(() -> a.importar(origem), "CSV malformado deve ser rejeitado");
                conferir(Arrays.equals(antes, Files.readAllBytes(pasta.resolve("dados.db"))), "CSV inválido não modifica a base");
                conferir(a.auditar().contains("1 registros ativos"), "Índices permanecem coerentes");
                try (Stream<Path> arquivos = Files.list(pasta)) {
                    conferir(arquivos.noneMatch(p -> p.getFileName().toString().startsWith("carga-") && p.toString().endsWith(".tmp")), "Carga recusada limpa temporário");
                }
            }
            // Este nome colidia com o temporário fixo e podia truncar a própria entrada.
            Path colidente = pasta.resolve("carga.tmp");
            Files.writeString(colidente, "Fonte preservada;diesel;2021;2026-09-14\n");
            byte[] fonte = Files.readAllBytes(colidente);
            conferir(a.importar(colidente) == 1, "CSV com nome igual ao antigo temporário");
            conferir(Arrays.equals(fonte, Files.readAllBytes(colidente)), "CSV de origem não foi truncado nem apagado");
            conferir(a.ler(1, Metodo.LISTA, 2021, "diesel") != null, "Carga do arquivo colidente coerente");
        }
        System.out.println("OK: CSV estrito, BOM, cabeçalho exato, aspas e preservação da origem/base.");
    }
    private static void bancoTruncado(Path pasta) throws Exception {
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) {
            a.criar(new Carro(0,"","Original",LocalDate.of(2026,9,14),Arrays.asList("gas"),2020), Metodo.HASH);
        }
        byte[] dados = Files.readAllBytes(pasta.resolve("dados.db"));
        byte[] indice = Files.readAllBytes(pasta.resolve("arvore.bmais"));
        byte[] config = Files.readAllBytes(pasta.resolve("config.properties"));
        Files.write(pasta.resolve("dados.db"),new byte[0]);
        deveFalhar(() -> { try (ArquivoIndexado a = new ArquivoIndexado(pasta,4,2)) { a.auditar(); } }, "Banco truncado não pode virar base vazia");
        conferir(Arrays.equals(indice,Files.readAllBytes(pasta.resolve("arvore.bmais"))), "Não destrói índice anterior");
        conferir(Arrays.equals(config,Files.readAllBytes(pasta.resolve("config.properties"))), "Não substitui configuração anterior");
        Files.write(pasta.resolve("dados.db"),dados);
        try (ArquivoIndexado a = new ArquivoIndexado(pasta,4,2)) { conferir(a.ler(1,Metodo.HASH)!=null,"Banco restaurado abre normalmente e a trava foi liberada"); }
        System.out.println("OK: truncamento detectado, índices preservados e restauração aceita.");
    }
    private static List<String> comando(Path pasta) {
        String nome = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new ArrayList<>(Arrays.asList(Paths.get(System.getProperty("java.home"),"bin",nome).toString(),"-Dfile.encoding=UTF-8","-cp",System.getProperty("java.class.path"),"MainTP2","--pasta",pasta.toString()));
    }
    private static void menu(Path pasta) throws Exception {
        Process processo = new ProcessBuilder(comando(pasta)).redirectErrorStream(true).start();
        ExecutorService leitor = Executors.newSingleThreadExecutor();
        try {
            Future<String> prompt = leitor.submit(() -> {
                StringBuilder texto = new StringBuilder();
                Reader in = new InputStreamReader(processo.getInputStream(),StandardCharsets.UTF_8);
                int c;
                while ((c=in.read())!=-1) { texto.append((char)c); if (texto.toString().contains("Escolha: ")) break; }
                return texto.toString();
            });
            conferir(prompt.get(15,TimeUnit.SECONDS).contains("Escolha: "),"Prompt aparece ANTES de digitar qualquer coisa");
            processo.getOutputStream().close();
            conferir(processo.waitFor(15,TimeUnit.SECONDS) && processo.exitValue()==0,"EOF encerra menu sem travar");
        } finally { processo.destroyForcibly(); leitor.shutdownNow(); }
        List<String> comando = comando(pasta); comando.add("--ordem");
        Process invalido = new ProcessBuilder(comando).redirectErrorStream(true).start();
        String erro = new String(invalido.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        conferir(invalido.waitFor()==1 && erro.contains("Falta o valor de --ordem"),"Argumento incompleto recebe mensagem clara");
        System.out.println("OK: menu real exibe prompt sem entrada prévia, aceita EOF e informa argumento incompleto.");
    }
}
