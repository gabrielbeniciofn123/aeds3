import index.HashEstendido;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/** Testes independentes do hash; não tocam na base de carros do trabalho. */
public final class TesteHashEstendido {
    private static int verificacoes;

    public static void main(String[] args) throws Exception {
        Path pasta = Files.createTempDirectory("tp2-teste-hash-");
        try {
            testarOperacoesAleatorias(pasta.resolve("aleatorio"));
            testarColisoesEReabertura(pasta.resolve("colisoes"));
            testarLimiteSemPerda(pasta.resolve("limite"));
            testarCorrupcao(pasta.resolve("corrupcao"));
            testarArquivosIguais(pasta.resolve("aliases"));
            if (args.length > 0 && "--carga".equals(args[0])) testarCarga(pasta.resolve("carga"));
            System.out.println("Hash estendido: " + verificacoes + " verificações passaram.");
        } finally {
            try (Stream<Path> caminhos = Files.walk(pasta)) {
                Path[] arquivos = caminhos.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path caminho : arquivos) Files.deleteIfExists(caminho);
            }
        }
    }

    private static void testarOperacoesAleatorias(Path pasta) throws Exception {
        Map<Integer, Long> esperado = new HashMap<>();
        Random aleatorio = new Random(20260920L);
        // Reabrir periodicamente também verifica que os índices sobrevivem a outro processo.
        for (int rodada = 0; rodada < 8; rodada++) {
            try (HashEstendido hash = abrir(pasta, 3)) {
                igual(esperado, hash.listar(), "Persistência entre rodadas");
                for (int operacao = 0; operacao < 1500; operacao++) {
                    int id = 1 + aleatorio.nextInt(2000);
                    int escolha = aleatorio.nextInt(4);
                    if (escolha <= 1) {
                        long posicao = 4_000_000_000L + aleatorio.nextInt(1_000_000);
                        hash.inserir(id, posicao);
                        esperado.put(id, posicao);
                    } else if (escolha == 2) {
                        igual(esperado.remove(id) != null, hash.remover(id), "Remoção com ID " + id);
                    } else {
                        igual(esperado.getOrDefault(id, -1L), hash.buscar(id), "Busca com ID " + id);
                    }
                    if (operacao % 250 == 0) {
                        hash.validar();
                        igual(esperado, hash.listar(), "CRUD aleatório preserva todas as chaves");
                    }
                }
                hash.sincronizar();
                hash.validar();
            }
        }
        try (HashEstendido hash = abrir(pasta, 3)) {
            for (Map.Entry<Integer, Long> par : esperado.entrySet()) {
                igual(par.getValue(), hash.buscar(par.getKey()), "Busca final por chave");
            }
            for (int id : esperado.keySet()) igual(true, hash.remover(id), "Remoção de toda a base");
            hash.validar();
            igual(0, hash.listar().size(), "Base vazia após exclusões");
            hash.inserir(Integer.MAX_VALUE, Long.MAX_VALUE);
            igual(Long.MAX_VALUE, hash.buscar(Integer.MAX_VALUE), "Limites de int e long");
            igual(-1L, hash.buscar(0), "ID zero não existe");
            igual(false, hash.remover(-10), "ID negativo não existe");
        }
    }

    private static void testarColisoesEReabertura(Path pasta) throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 150; i++) ids.add(1 + i * 64);
        Collections.shuffle(ids, new Random(15));
        try (HashEstendido hash = abrir(pasta, 1)) {
            for (int id : ids) hash.inserir(id, (long) id * 31);
            hash.validar();
            igual(ids.size(), hash.listar().size(), "Colisões e bucket de capacidade 1");
            // Upsert em bucket cheio não pode provocar outro split nem duplicar a chave.
            hash.inserir(ids.get(0), 99);
            igual(99L, hash.buscar(ids.get(0)), "Atualização em bucket cheio");
            igual(ids.size(), hash.listar().size(), "Upsert mantém quantidade");
        }
        try (HashEstendido hash = abrir(pasta, 1)) {
            hash.validar();
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.get(i);
                igual(i == 0 ? 99L : (long) id * 31, hash.buscar(id), "Colisões persistidas");
            }
        }
        esperarIOException(() -> {
            try (HashEstendido ignorado = abrir(pasta, 2)) { ignorado.validar(); }
        }, "Capacidade persistida não pode ser alterada silenciosamente");
    }

    private static void testarLimiteSemPerda(Path pasta) throws Exception {
        try (HashEstendido hash = abrir(pasta, 1)) {
            hash.inserir(1, 100);
            esperarIOException(() -> hash.inserir(1 + (1 << HashEstendido.MAX_PROFUNDIDADE), 200),
                    "Colisão além do limite deve ser recusada antes de modificar a estrutura");
            hash.validar();
            igual(100L, hash.buscar(1), "Registro preservado ao atingir o limite");
            igual(1, hash.listar().size(), "Inserção recusada não cria registro parcial");
            hash.inserir(2, 300);
            hash.validar();
            igual(300L, hash.buscar(2), "Estrutura continua utilizável após recusa");
        }
    }

    private static void testarCorrupcao(Path pasta) throws Exception {
        try (HashEstendido hash = abrir(pasta, 2)) {
            for (int id = 1; id <= 30; id++) hash.inserir(id, id * 100L);
            hash.validar();
        }
        // O tamanho físico da página continua válido; a contagem excede sua capacidade.
        try (RandomAccessFile buckets = new RandomAccessFile(pasta.resolve("buckets.db").toFile(), "rw")) {
            buckets.seek(12 + Integer.BYTES);
            buckets.writeInt(3);
        }
        esperarIOException(() -> {
            try (HashEstendido hash = abrir(pasta, 2)) { hash.validar(); }
        }, "Validação detecta contagem de bucket corrompida");
    }

    private static void testarCarga(Path pasta) throws Exception {
        long inicio = System.nanoTime();
        try (HashEstendido hash = abrir(pasta, 2000)) {
            for (int id = 1; id <= 100_000; id++) hash.inserir(id, id * 80L);
            hash.validar();
            igual(100_000, hash.listar().size(), "Carga de 100 mil registros");
            for (int id = 1; id <= 100_000; id += 97) {
                igual(id * 80L, hash.buscar(id), "Busca na carga completa");
            }
            System.out.println(hash.resumo());
        }
        double segundos = (System.nanoTime() - inicio) / 1_000_000_000.0;
        System.out.printf(java.util.Locale.ROOT, "Carga, validação e consultas: %.2f s.%n", segundos);
    }

    private static void testarArquivosIguais(Path pasta) throws Exception {
        Files.createDirectories(pasta);
        Path real = Files.createFile(pasta.resolve("real.db"));
        recusarArquivosIguais(real, real);
        igual(0L, Files.size(real), "Mesmo caminho é recusado sem gravar cabeçalhos");

        Path alias = pasta.resolve("alias.db");
        try {
            Files.createLink(alias, real);
        } catch (UnsupportedOperationException | IOException | SecurityException indisponivel) {
            // Há sistemas de arquivos sem links físicos; a recusa do mesmo caminho
            // acima continua sendo exercitada nessas plataformas.
            System.out.println("  Teste de alias físico não disponível neste sistema de arquivos.");
            return;
        }
        recusarArquivosIguais(real, alias);
        igual(0L, Files.size(real), "Alias físico é recusado sem corromper o arquivo");
    }

    private static void recusarArquivosIguais(Path diretorio, Path buckets) throws Exception {
        verificacoes++;
        try (HashEstendido ignorado = new HashEstendido(diretorio, buckets, 2)) {
            throw new AssertionError("Diretório e buckets no mesmo arquivo físico deveriam ser recusados.");
        } catch (IllegalArgumentException esperado) {
            igual(true, esperado.getMessage().contains("arquivos diferentes"), "Motivo da recusa de aliases");
        }
    }

    private static HashEstendido abrir(Path pasta, int capacidade) throws IOException {
        return new HashEstendido(pasta.resolve("diretorio.db"), pasta.resolve("buckets.db"), capacidade);
    }

    private static void igual(Object esperado, Object encontrado, String mensagem) {
        verificacoes++;
        if (!esperado.equals(encontrado)) {
            throw new AssertionError(mensagem + ": esperado=" + esperado + ", encontrado=" + encontrado);
        }
    }

    private static void esperarIOException(Acao acao, String mensagem) throws Exception {
        verificacoes++;
        try { acao.executar(); }
        catch (IOException esperado) { return; }
        throw new AssertionError(mensagem);
    }

    private interface Acao { void executar() throws Exception; }
}
