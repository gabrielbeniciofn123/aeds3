import index.ListaInvertida;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Testes do índice secundário, sem dependências. Execute com java -cp out TesteListaInvertida. */
public class TesteListaInvertida {
    public static void main(String[] args) throws Exception {
        Path pasta = Files.createTempDirectory("teste-lista-invertida-");
        try {
            testarOperacoes(pasta.resolve("operacoes.idx"));
            testarAleatorio(pasta.resolve("aleatorio.idx"));
            testarCorrupcao(pasta);
            if (Arrays.asList(args).contains("--100k")) testarEscala(pasta.resolve("escala.idx"));
            System.out.println("LISTA INVERTIDA: TODOS OS TESTES PASSARAM.");
        } finally {
            try (java.util.stream.Stream<Path> arquivos = Files.list(pasta)) {
                for (Path arquivo : (Iterable<Path>) arquivos::iterator) Files.deleteIfExists(arquivo);
            }
            Files.deleteIfExists(pasta);
        }
    }

    private static void testarOperacoes(Path arquivo) throws Exception {
        exigir(ListaInvertida.normalizar("  InjeÇÃO\t  ELETRÔNICA\u00a0 ").equals("injecao eletronica"),
                "Normalização de acentos, caixa, tabulação e espaço Unicode");
        try (ListaInvertida lista = new ListaInvertida(arquivo)) {
            lista.adicionar("Injeção eletrônica", 1, 100);
            lista.adicionar("INJECAO ELETRONICA", 1, 100);
            lista.adicionar("Injeção eletrônica", 2, 200);
            exigir(lista.buscar("injeção ELETRÔNICA").size() == 2, "Posting repetido não duplica");
            lista.adicionar("INJECAO ELETRONICA", 1, 999);
            exigir(lista.buscar("injeção eletrônica").get(1) == 999, "Atualização do endereço");
            lista.buscar("injeção eletrônica").clear();
            lista.listar().get("injecao eletronica").clear();
            exigir(lista.buscar("injeção eletrônica").size() == 2, "Buscas devolvem cópias profundas");
            lista.remover("injeção eletrônica", 2);
            lista.remover("inexistente", 999);
            lista.adicionar("  ", 3, 300);
            exigir(lista.buscar("  ").isEmpty(), "Termos vazios não são indexados");
            lista.validar();
            lista.sincronizar();
            // Outro leitor encontra as alterações mesmo antes de close().
            try (ListaInvertida leitor = new ListaInvertida(arquivo)) {
                exigir(leitor.buscar("injecao eletronica").get(1) == 999, "Persistência das alterações");
                exigir(leitor.buscar("injecao eletronica").size() == 1, "Remoção persistida");
            }
            long antes = Files.size(arquivo);
            lista.compactar();
            exigir(Files.size(arquivo) < antes, "Compactação reduz histórico");
            lista.validar();
        }
        try (ListaInvertida lista = new ListaInvertida(arquivo)) {
            exigir(lista.buscar("injecao eletronica").get(1) == 999, "Reabertura após compactação");
            lista.remover("Injeção Eletrônica", 1);
            exigir(lista.listar().isEmpty(), "Remoção do último posting elimina o termo");
            lista.compactar();
            exigir(Files.size(arquivo) == 8, "Índice vazio conserva somente cabeçalho");
            lista.validar();
        }
        System.out.println("OK: normalização, CRUD de postings, cópias, persistência e compactação.");
    }

    private static void testarAleatorio(Path arquivo) throws Exception {
        Random sorteio = new Random(20260920L);
        Map<String, Map<Integer, Long>> esperado = new TreeMap<>();
        for (int rodada = 0; rodada < 4; rodada++) {
            try (ListaInvertida lista = new ListaInvertida(arquivo)) {
                for (int i = 0; i < 4_000; i++) {
                    String termo = "termo " + sorteio.nextInt(15);
                    int id = 1 + sorteio.nextInt(250);
                    if (sorteio.nextInt(3) != 0) {
                        long posicao = 4L + sorteio.nextInt(100_000);
                        lista.adicionar(termo, id, posicao);
                        esperado.computeIfAbsent(termo, ignorado -> new HashMap<>()).put(id, posicao);
                    } else {
                        lista.remover(termo, id);
                        Map<Integer, Long> postings = esperado.get(termo);
                        if (postings != null) {
                            postings.remove(id);
                            if (postings.isEmpty()) esperado.remove(termo);
                        }
                    }
                }
                exigir(lista.listar().equals(esperado), "Índice igual ao modelo independente");
                if (rodada % 2 == 0) lista.compactar();
                lista.validar();
            }
        }
        try (ListaInvertida lista = new ListaInvertida(arquivo)) {
            exigir(lista.listar().equals(esperado), "Modelo preservado após última reabertura");
        }
        System.out.println("OK: 16.000 operações aleatórias com reaberturas e endereços alterados.");
    }

    private static void testarCorrupcao(Path pasta) throws Exception {
        Path original = pasta.resolve("original.idx");
        try (ListaInvertida lista = new ListaInvertida(original)) {
            lista.adicionar("8 cylinders", 1, 4);
        }
        byte[] valido = Files.readAllBytes(original);
        for (int tamanho : new int[] {0, 4, 9, valido.length - 1}) {
            Path truncado = pasta.resolve("truncado-" + tamanho + ".idx");
            Files.write(truncado, Arrays.copyOf(valido, tamanho));
            exigirInvalido(truncado, "Arquivo truncado de tamanho " + tamanho);
        }
        byte[] crcInvalido = valido.clone();
        crcInvalido[crcInvalido.length - 1] ^= 1;
        Path corrompido = pasta.resolve("crc-invalido.idx");
        Files.write(corrompido, crcInvalido);
        exigirInvalido(corrompido, "CRC inválido");
        System.out.println("OK: cabeçalho truncado, evento truncado e corrupção de CRC detectados.");
    }

    private static void testarEscala(Path arquivo) throws Exception {
        long inicio = System.nanoTime();
        try (ListaInvertida lista = new ListaInvertida(arquivo)) {
            for (int id = 1; id <= 100_000; id++) {
                for (int atributo = 0; atributo < 6; atributo++) {
                    lista.adicionar("atributo " + atributo + " valor " + (id % 8), id, id * 256L);
                }
            }
            lista.sincronizar();
            exigir(lista.buscar("atributo 3 valor 7").size() == 12_500, "Busca em 600.000 postings");
            lista.validar();
        }
        try (ListaInvertida lista = new ListaInvertida(arquivo)) {
            exigir(lista.buscar("atributo 0 valor 0").get(100_000) == 25_600_000L,
                    "Endereço preservado ao reabrir índice grande");
        }
        System.out.println("OK: 600.000 postings; gravação, validação e reabertura em "
                + (System.nanoTime() - inicio) / 1_000_000 + " ms; " + Files.size(arquivo) + " bytes.");
    }

    private static void exigirInvalido(Path arquivo, String mensagem) throws Exception {
        try (ListaInvertida ignorada = new ListaInvertida(arquivo)) {
            throw new AssertionError("Não detectou " + mensagem);
        } catch (IOException esperada) {
            // A rejeição explícita permite que o serviço reconstrua o índice.
        }
    }

    private static void exigir(boolean condicao, String mensagem) {
        if (!condicao) throw new AssertionError("FALHOU: " + mensagem);
    }
}
