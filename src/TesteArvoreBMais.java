import index.ArvoreBMais;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Testes independentes da árvore; o TreeMap funciona somente como oráculo. */
public final class TesteArvoreBMais {
    private static int verificacoes;

    public static void main(String[] args) throws Exception {
        Path pasta = Files.createTempDirectory("tp2-teste-bmais-");
        try {
            for (int ordem : new int[]{3, 4, 5, 6, 7, 8, 9, 16, 31}) {
                exercitar(pasta.resolve("ordem-" + ordem + ".idx"), ordem);
            }
            testarCorrupcao(pasta.resolve("corrompido.idx"));
            System.out.println("Árvore B+: " + verificacoes + " verificações passaram.");
        } finally {
            try (Stream<Path> caminhos = Files.walk(pasta)) {
                for (Path caminho : (Iterable<Path>) caminhos.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(caminho);
                }
            }
        }
    }

    private static void exercitar(Path caminho, int ordem) throws Exception {
        Random aleatorio = new Random(2048 + ordem);
        Map<Integer, Long> esperado = new TreeMap<>();
        List<Integer> ids = new ArrayList<>();
        for (int id = 0; id < 350; id++) ids.add(id);
        ids.add(Integer.MIN_VALUE);
        ids.add(Integer.MAX_VALUE);
        Collections.shuffle(ids, aleatorio);
        ArvoreBMais arvore = new ArvoreBMais(caminho, ordem);
        try {
            arvore.validar();
            conferir(arvore.buscar(0) == -1, "Busca em árvore vazia");
            conferir(!arvore.remover(0), "Remoção em árvore vazia");
            for (int id : ids) {
                long posicao = Integer.toUnsignedLong(id) * 19L + 4;
                arvore.inserir(id, posicao);
                esperado.put(id, posicao);
                arvore.validar();
                conferir(arvore.buscar(id) == posicao, "Inserção e busca: " + id);
            }
            conferir(arvore.listar().equals(esperado), "Listagem após divisões");
            arvore.sincronizar();
            arvore.close();
            arvore = new ArvoreBMais(caminho, ordem);
            conferir(arvore.listar().equals(esperado), "Reabertura após divisões");

            for (int passo = 0; passo < 1800; passo++) {
                int id = aleatorio.nextInt(550) - 100;
                if (aleatorio.nextInt(10) < 6) {
                    long posicao = 4L + aleatorio.nextInt(1_000_000);
                    arvore.inserir(id, posicao);
                    esperado.put(id, posicao);
                } else {
                    conferir(arvore.remover(id) == (esperado.remove(id) != null), "Resultado da remoção");
                }
                if (passo % 17 == 0) {
                    arvore.validar();
                    conferir(arvore.listar().equals(esperado), "Mistura de CRUD, passo " + passo);
                    for (int consulta = -100; consulta < 450; consulta += 13) {
                        conferir(arvore.buscar(consulta) == esperado.getOrDefault(consulta, -1L), "Busca pontual");
                    }
                }
                if (passo % 233 == 0) {
                    arvore.close();
                    arvore = new ArvoreBMais(caminho, ordem);
                }
            }
            ids = new ArrayList<>(esperado.keySet());
            Collections.shuffle(ids, aleatorio);
            long tamanhoAntesRemocoes = Files.size(caminho);
            for (int id : ids) {
                conferir(arvore.remover(id), "Remoção de id existente");
                esperado.remove(id);
                arvore.validar();
                conferir(arvore.buscar(id) == -1, "Id removido ausente");
            }
            conferir(arvore.listar().isEmpty(), "Árvore vazia após fusões e redução da raiz");
            arvore.close();
            arvore = new ArvoreBMais(caminho, ordem);
            arvore.validar();
            conferir(arvore.listar().isEmpty(), "Árvore vazia persistida");
            for (int id = 0; id < 100; id++) arvore.inserir(id, 8L + id * 31L);
            arvore.validar();
            conferir(Files.size(caminho) <= tamanhoAntesRemocoes, "Reutilização das páginas liberadas");
            arvore.inserir(50, 9_999_999L);
            conferir(arvore.listar().size() == 100, "Upsert não duplica id");
            conferir(arvore.buscar(50) == 9_999_999L, "Upsert substitui endereço");
            System.out.println("  " + arvore.resumo());
        } finally {
            arvore.close();
        }
        try (ArvoreBMais ignorada = new ArvoreBMais(caminho, ordem + 1)) {
            throw new AssertionError("Abertura com outra ordem deveria falhar.");
        } catch (IOException esperadoErro) {
            conferir(esperadoErro.getMessage().contains("ordem"), "Ordem incompatível rejeitada");
        }
    }

    private static void testarCorrupcao(Path caminho) throws Exception {
        try (ArvoreBMais arvore = new ArvoreBMais(caminho, 4)) {
            for (int i = 0; i < 20; i++) arvore.inserir(i, 10L + i);
        }
        // Sobrescreve o próximo da primeira folha com seu próprio endereço.
        try (RandomAccessFile arquivo = new RandomAccessFile(caminho.toFile(), "rw")) {
            arquivo.seek(48 + 1 + 4);
            arquivo.writeLong(48);
        }
        try (ArvoreBMais arvore = new ArvoreBMais(caminho, 4)) {
            try {
                arvore.validar();
                throw new AssertionError("Encadeamento corrompido deveria ser detectado.");
            } catch (IOException esperadoErro) {
                conferir(esperadoErro.getMessage().contains("folha"), "Corrupção do encadeamento detectada");
            }
        }
    }

    private static void conferir(boolean condicao, String mensagem) {
        if (!condicao) throw new AssertionError(mensagem);
        verificacoes++;
    }
}
