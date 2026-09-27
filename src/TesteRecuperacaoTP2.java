import dao.ArquivoIndexado;
import dao.ArquivoIndexado.Metodo;
import model.Carro;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Stream;

/** Falhas reais de acesso a arquivos e retomada sem reiniciar a sessão. */
public final class TesteRecuperacaoTP2 {
    private static int verificacoes;

    public static void main(String[] args) throws Exception {
        Path raiz = Files.createTempDirectory("tp2-recuperacao-");
        try {
            Path pasta = raiz.resolve("base");
            Path csvInicial = criarCsv(raiz.resolve("inicial.csv"), 60, "Anterior");
            Path csvNovo = criarCsv(raiz.resolve("novo.csv"), 300, "Novo");
            testarFalhaDeCargaNaMesmaSessao(pasta, csvInicial, csvNovo);
            testarFalhaDeCommitCrud(raiz.resolve("crud"));
            testarConfiguracaoCorrompida(pasta);
            testarIndiceCorrompido(pasta);
            System.out.println("Recuperação do TP2: " + verificacoes + " verificações passaram.");
        } finally {
            try (Stream<Path> caminhos = Files.walk(raiz)) {
                for (Path p : caminhos.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(p);
            }
        }
    }

    /** Cada CRUD precisa desfazer dados E todos os índices se o commit falhar. */
    private static void testarFalhaDeCommitCrud(Path raiz) throws Exception {
        for (Metodo metodo : Metodo.values()) {
            Path pasta = raiz.resolve(metodo.name());
            try (ArquivoIndexado arquivo = new ArquivoIndexado(pasta, 4, 2)) {
                Carro original = carro("Original");
                int id = arquivo.criar(original, metodo);
                for (int operacao = 0; operacao < 4; operacao++) {
                    byte[] antes = Files.readAllBytes(pasta.resolve("dados.db"));
                    Path impedimento = pasta.resolve("config.properties.tmp");
                    Files.createDirectory(impedimento);
                    try {
                        final int caso = operacao;
                        esperarIOException(() -> {
                            if (caso == 0) arquivo.criar(carro("Criação recusada"), metodo);
                            else if (caso == 3) arquivo.excluir(id, metodo, 2020, "gas");
                            else {
                                Carro novo = carro(caso == 1 ? "Alterado" : "Atualização que exige outro endereço");
                                novo.setId(id);
                                novo.setAno(2021);
                                novo.setCaracteristicas(Collections.singletonList("gnv"));
                                arquivo.atualizar(novo, metodo, 2020, "gas");
                            }
                        }, "Falha no commit deve abortar CRUD " + operacao + " via " + metodo);
                        conferir(Arrays.equals(antes, Files.readAllBytes(pasta.resolve("dados.db"))),
                                "Undo restaura exatamente os bytes anteriores do CRUD " + operacao);
                    } finally { Files.delete(impedimento); }
                    for (Metodo leitura : Metodo.values()) {
                        conferir(original.equals(arquivo.ler(id, leitura, 2020, "gas")),
                                "Após rollback, registro original reaparece em " + leitura);
                        conferir(arquivo.ler(id + 1, leitura, 2020, "gas") == null,
                                "Criação não confirmada ausente em " + leitura);
                    }
                    conferir(arquivo.pesquisar(2021, null).isEmpty(), "Ano do update abortado não ficou na lista");
                    conferir(arquivo.pesquisar(null, "gnv").isEmpty(), "Característica do update abortado não ficou na lista");
                    conferir(arquivo.auditar().contains("1 registros ativos"), "Auditoria após rollback do CRUD");
                    conferir(!Files.exists(pasta.resolve("transacao.bin"))
                                    && !Files.exists(pasta.resolve("indices.pendentes")),
                            "Próxima consulta conclui recuperação na mesma sessão");
                }
                conferir(arquivo.criar(carro("Depois"), metodo) == id + 1,
                        "Create abortado não consumiu ID");
            }
            try (ArquivoIndexado arquivo = new ArquivoIndexado(pasta, 4, 2)) {
                conferir(arquivo.auditar().contains("2 registros ativos"),
                        "CRUD recuperado permanece coerente depois de reiniciar");
            }
        }
        System.out.println("OK: falhas de commit em create, update no lugar/relocado e delete pelos três índices.");
    }

    private static void testarFalhaDeCargaNaMesmaSessao(Path pasta, Path inicial, Path novo) throws Exception {
        try (ArquivoIndexado arquivo = new ArquivoIndexado(pasta, 4, 2)) {
            conferir(arquivo.importar(inicial) == 60, "Carga inicial");
            conferir(arquivo.excluir(3, Metodo.HASH, null, null), "Exclusão antes da carga conserva N inicial");
            byte[] bancoAnterior = Files.readAllBytes(pasta.resolve("dados.db"));
            // Impede a gravação da configuração depois que o novo banco e os índices foram escritos.
            Path impedimento = pasta.resolve("config.properties.tmp");
            Files.createDirectory(impedimento);
            Files.write(impedimento.resolve("bloqueio"), new byte[]{1});
            esperarIOException(() -> arquivo.importar(novo), "Falha no commit da carga deve ser informada");
            conferir(Arrays.equals(bancoAnterior, Files.readAllBytes(pasta.resolve("dados.db"))),
                    "Mesmo com erro na recuperação dos metadados, o backup restaurou os bytes do banco");
            conferir(Files.exists(pasta.resolve("carga.pendente")), "Marcador preservado até recuperação terminar");
            Files.delete(impedimento.resolve("bloqueio"));
            Files.delete(impedimento);
            conferir(arquivo.ler(1, Metodo.HASH).getNome().equals("Anterior 1"),
                    "A próxima consulta recupera a carga na mesma sessão");
            conferir(arquivo.ler(3, Metodo.ARVORE) == null, "Lápide anterior restaurada");
            conferir(arquivo.ler(61, Metodo.HASH) == null, "Nenhum registro da carga recusada aparece");
            conferir(arquivo.resumo().contains("Base inicial: 60 | Hash: 2.0% | X=2"),
                    "Rollback conserva N inicial 60 e capacidade 2, apesar de 59 carros ativos");
            conferir(arquivo.auditar().contains("59 registros ativos"), "Todos os índices correspondem ao backup");
            conferir(!Files.exists(pasta.resolve("carga.pendente")), "Recuperação remove marcador de carga");
            conferir(!Files.exists(pasta.resolve("indices.pendentes")), "Recuperação confirma índices");

            Path diario = pasta.resolve("transacao.bin");
            byte[] diarioInvalido = {1, 2, 3, 4};
            Files.write(diario, diarioInvalido);
            esperarIOException(() -> arquivo.criar(carro("Não inserir"), Metodo.HASH),
                    "Sessão com diário inválido bloqueia nova transação");
            esperarIOException(arquivo::reconstruirIndices, "Reconstrução pública também respeita undo pendente");
            conferir(Arrays.equals(diarioInvalido, Files.readAllBytes(diario)), "Diário anterior não foi sobrescrito");
            conferir(Arrays.equals(bancoAnterior, Files.readAllBytes(pasta.resolve("dados.db"))),
                    "Diário inválido não modifica os dados");
            Files.delete(diario);
            conferir(arquivo.criar(carro("Depois da recuperação"), Metodo.LISTA) == 61,
                    "CRUD volta a funcionar mantendo sequência de IDs");
            conferir(arquivo.auditar().contains("60 registros ativos"), "CRUD após recuperação mantém coerência");

            conferir(arquivo.importar(novo) == 300, "Nova tentativa de carga conclui normalmente");
            conferir(arquivo.resumo().contains("Base inicial: 300 | Hash: 2.0% | X=6"),
                    "Somente carga concluída estabelece nova capacidade");
            conferir(arquivo.auditar().contains("300 registros ativos"), "Auditoria após carga concluída");
            conferir(!Files.exists(pasta.resolve("carga.pendente")), "Carga concluída remove marcador");
        }
    }

    private static void testarConfiguracaoCorrompida(Path pasta) throws Exception {
        Path configuracao = pasta.resolve("config.properties");
        byte[] anterior = Files.readAllBytes(configuracao);
        byte[] banco = Files.readAllBytes(pasta.resolve("dados.db"));
        try {
            Files.write(configuracao, "tamanhoInicial=abc\n".getBytes(StandardCharsets.UTF_8));
            esperarIOException(() -> {
                try (ArquivoIndexado ignorado = new ArquivoIndexado(pasta, 4, 2)) { ignorado.auditar(); }
            }, "N inicial malformado não pode ser recalculado silenciosamente");
            conferir(Arrays.equals(banco, Files.readAllBytes(pasta.resolve("dados.db"))),
                    "Configuração inválida não muda o banco");
            Files.write(configuracao, "tamanhoInicial=\\uZZZZ\n".getBytes(StandardCharsets.UTF_8));
            esperarIOException(() -> {
                try (ArquivoIndexado ignorado = new ArquivoIndexado(pasta, 4, 2)) { ignorado.auditar(); }
            }, "Escape Unicode malformado recebe IOException clara");
        } finally { Files.write(configuracao, anterior); }
        try (ArquivoIndexado arquivo = new ArquivoIndexado(pasta, 4, 2)) {
            conferir(arquivo.auditar().contains("300 registros ativos"), "Trava foi liberada após construtor falhar");
        }
    }

    private static void testarIndiceCorrompido(Path pasta) throws Exception {
        try (RandomAccessFile indice = new RandomAccessFile(pasta.resolve("hash.diretorio").toFile(), "rw")) {
            indice.writeInt(0);
        }
        try (ArquivoIndexado arquivo = new ArquivoIndexado(pasta, 4, 2)) {
            conferir(arquivo.ler(300, Metodo.HASH).getNome().equals("Novo 300"),
                    "Cabeçalho de índice corrompido é reconstruído a partir do banco");
            conferir(arquivo.auditar().contains("300 registros ativos"), "Reconstrução automática mantém os quatro índices");
        }
    }

    private static Path criarCsv(Path caminho, int quantidade, String prefixo) throws IOException {
        StringBuilder csv = new StringBuilder("nome;caracteristicas;ano;data\n");
        for (int i = 1; i <= quantidade; i++) csv.append(prefixo).append(' ').append(i)
                .append(";gas|4 cylinders;2020;2026-09-14\n");
        Files.write(caminho, csv.toString().getBytes(StandardCharsets.UTF_8));
        return caminho;
    }

    private static Carro carro(String nome) {
        return new Carro(0, "", nome, LocalDate.of(2026, 9, 14), Collections.singletonList("gas"), 2020);
    }

    private static void conferir(boolean valor, String mensagem) {
        verificacoes++;
        if (!valor) throw new AssertionError(mensagem);
    }

    private static void esperarIOException(Acao acao, String mensagem) throws Exception {
        verificacoes++;
        try { acao.executar(); }
        catch (IOException esperado) { return; }
        throw new AssertionError(mensagem);
    }

    private interface Acao { void executar() throws Exception; }
}
