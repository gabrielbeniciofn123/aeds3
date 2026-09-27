package index;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Índice de hashing estendido em dois arquivos, usando h(id) = id mod 2^p.
 * O diretório fica em memória; os registros dos buckets são lidos do disco.
 *
 * Diretório: magic:int, versão:int, capacidade:int, p:int, tamanho:int,
 *            tamanho referências long para os buckets.
 * Buckets: magic:int, versão:int, capacidade:int, seguido de páginas fixas.
 * Página: profundidadeLocal:int, quantidade:int, capacidade pares (id:int, posição:long).
 * Apenas os primeiros "quantidade" pares estão ocupados.
 *
 * A capacidade é fixa e deve ser calculada com o tamanho INICIAL da base.
 * Remoções liberam slots; não fazem fusão de buckets, que é uma otimização opcional.
 * Esta classe não implementa transações: a camada de dados deve reconstruir os
 * índices depois de uma interrupção durante uma alteração em vários arquivos.
 */
public final class HashEstendido implements AutoCloseable {
    private static final int MAGIC_DIRETORIO = 0x48454431; // HED1
    private static final int MAGIC_BUCKETS = 0x48454231;  // HEB1
    private static final int VERSAO = 1;
    private static final int CABECALHO_DIRETORIO = 20;
    private static final int CABECALHO_BUCKETS = 12;
    private static final int CABECALHO_PAGINA = 8;
    private static final int TAMANHO_PAR = 12;

    /** Até 1.048.576 referências (8 MiB); impede crescimento ilimitado em colisões. */
    public static final int MAX_PROFUNDIDADE = 20;
    private static final int MASCARA_MAXIMA = (1 << MAX_PROFUNDIDADE) - 1;

    private final RandomAccessFile arquivoDiretorio;
    private final RandomAccessFile arquivoBuckets;
    private final int capacidade;
    private final long tamanhoPagina;
    private long[] diretorio;
    private int profundidadeGlobal;
    private boolean fechado;

    public HashEstendido(Path caminhoDiretorio, Path caminhoBuckets, int capacidade)
            throws IOException {
        if (capacidade < 1 || capacidade > (Integer.MAX_VALUE - CABECALHO_PAGINA) / TAMANHO_PAR) {
            throw new IllegalArgumentException("Capacidade de bucket inválida: " + capacidade);
        }
        if (caminhoDiretorio.toAbsolutePath().normalize()
                .equals(caminhoBuckets.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Diretório e buckets precisam de arquivos diferentes.");
        }
        this.capacidade = capacidade;
        this.tamanhoPagina = CABECALHO_PAGINA + (long) TAMANHO_PAR * capacidade;
        criarPasta(caminhoDiretorio);
        criarPasta(caminhoBuckets);
        RandomAccessFile arqDir = new RandomAccessFile(caminhoDiretorio.toFile(), "rw");
        RandomAccessFile arqBuckets;
        try {
            arqBuckets = new RandomAccessFile(caminhoBuckets.toFile(), "rw");
        } catch (IOException e) {
            arqDir.close();
            throw e;
        }
        this.arquivoDiretorio = arqDir;
        this.arquivoBuckets = arqBuckets;
        try {
            // Nomes distintos também podem apontar para o mesmo arquivo por links
            // ou pelas regras de maiúsculas/minúsculas do sistema de arquivos.
            // A verificação ocorre antes de gravar qualquer cabeçalho e depois de
            // abrir ambos, incluindo o caso de um link cujo destino ainda não existia.
            if (Files.isSameFile(caminhoDiretorio, caminhoBuckets)) {
                throw new IllegalArgumentException("Diretório e buckets precisam de arquivos diferentes.");
            }
            if (arquivoDiretorio.length() == 0 && arquivoBuckets.length() == 0) {
                criar();
            } else {
                carregar();
            }
        } catch (IOException | RuntimeException e) {
            try { close(); } catch (IOException erroFechamento) { e.addSuppressed(erroFechamento); }
            throw e;
        }
    }

    /** Retorna o endereço da lápide do registro no arquivo de dados, ou -1. */
    public long buscar(int id) throws IOException {
        conferirAberto();
        if (id <= 0) return -1;
        Bucket bucket = lerBucket(diretorio[hash(id)]);
        int slot = bucket.encontrar(id);
        return slot < 0 ? -1 : bucket.posicoes[slot];
    }

    /** Insere uma chave nova ou atualiza somente o endereço de uma chave existente. */
    public void inserir(int id, long posicao) throws IOException {
        conferirAberto();
        if (id <= 0) throw new IllegalArgumentException("O ID deve ser positivo.");
        if (posicao < 0) throw new IllegalArgumentException("A posição não pode ser negativa.");
        while (true) {
            long endereco = diretorio[hash(id)];
            Bucket bucket = lerBucket(endereco);
            int slot = bucket.encontrar(id);
            if (slot >= 0) {
                arquivoBuckets.seek(endereco + CABECALHO_PAGINA + (long) slot * TAMANHO_PAR + Integer.BYTES);
                arquivoBuckets.write(ByteBuffer.allocate(Long.BYTES).putLong(posicao).array());
                return;
            }
            if (bucket.quantidade < capacidade) {
                // Não reescrevemos os 2.000 slots a cada inserção da base de 100 mil carros.
                arquivoBuckets.seek(endereco + CABECALHO_PAGINA + (long) bucket.quantidade * TAMANHO_PAR);
                arquivoBuckets.write(ByteBuffer.allocate(TAMANHO_PAR).putInt(id).putLong(posicao).array());
                arquivoBuckets.seek(endereco + Integer.BYTES);
                arquivoBuckets.write(ByteBuffer.allocate(Integer.BYTES).putInt(bucket.quantidade + 1).array());
                return;
            }
            conferirPossibilidadeDivisao(bucket, id);
            dividir(endereco, bucket);
        }
    }

    /** Preenche o espaço liberado com o último par; nenhuma ordenação é necessária. */
    public boolean remover(int id) throws IOException {
        conferirAberto();
        if (id <= 0) return false;
        long endereco = diretorio[hash(id)];
        Bucket bucket = lerBucket(endereco);
        int slot = bucket.encontrar(id);
        if (slot < 0) return false;
        int ultimo = bucket.quantidade - 1;
        if (slot != ultimo) {
            arquivoBuckets.seek(endereco + CABECALHO_PAGINA + (long) slot * TAMANHO_PAR);
            arquivoBuckets.write(ByteBuffer.allocate(TAMANHO_PAR)
                    .putInt(bucket.ids[ultimo]).putLong(bucket.posicoes[ultimo]).array());
        }
        arquivoBuckets.seek(endereco + Integer.BYTES);
        arquivoBuckets.write(ByteBuffer.allocate(Integer.BYTES).putInt(ultimo).array());
        return true;
    }

    /** Percorre cada bucket uma única vez, mesmo quando há referências compartilhadas. */
    public Map<Integer, Long> listar() throws IOException {
        conferirAberto();
        Map<Integer, Long> resultado = new LinkedHashMap<>();
        Set<Long> visitados = new HashSet<>();
        for (long endereco : diretorio) {
            if (!visitados.add(endereco)) continue;
            Bucket bucket = lerBucket(endereco);
            for (int i = 0; i < bucket.quantidade; i++) {
                if (resultado.put(bucket.ids[i], bucket.posicoes[i]) != null) {
                    throw new IOException("ID duplicado no hash: " + bucket.ids[i]);
                }
            }
        }
        return resultado;
    }

    public String resumo() throws IOException {
        conferirAberto();
        Set<Long> visitados = new HashSet<>();
        long registros = 0;
        int menorProfundidade = profundidadeGlobal;
        int maiorProfundidade = 0;
        for (long endereco : diretorio) {
            if (!visitados.add(endereco)) continue;
            arquivoBuckets.seek(endereco);
            byte[] cabecalho = new byte[CABECALHO_PAGINA];
            arquivoBuckets.readFully(cabecalho);
            ByteBuffer pagina = ByteBuffer.wrap(cabecalho);
            int local = pagina.getInt();
            int quantidade = pagina.getInt();
            conferirCabecalhoBucket(local, quantidade);
            registros += quantidade;
            menorProfundidade = Math.min(menorProfundidade, local);
            maiorProfundidade = Math.max(maiorProfundidade, local);
        }
        return "Hash estendido: profundidade global=" + profundidadeGlobal
                + ", referências=" + diretorio.length + ", buckets=" + visitados.size()
                + ", capacidade por bucket=" + capacidade + ", registros=" + registros
                + ", profundidades locais=" + menorProfundidade + ".." + maiorProfundidade
                + ", função h(id)=id mod 2^p";
    }

    /** Confirma as gravações dos dois arquivos antes de encerrar uma transação do CRUD. */
    public void sincronizar() throws IOException {
        conferirAberto();
        arquivoBuckets.getFD().sync();
        arquivoDiretorio.getFD().sync();
    }

    /** Verifica cabeçalhos, páginas, aliases, distribuição, limites e IDs únicos. */
    public void validar() throws IOException {
        conferirAberto();
        conferirArquivos();
        Map<Long, Integer> referencias = new HashMap<>();
        Map<Long, Integer> prefixos = new HashMap<>();
        Map<Long, Bucket> buckets = new HashMap<>();
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < diretorio.length; i++) {
            long endereco = diretorio[i];
            conferirEndereco(endereco);
            Bucket bucket = buckets.get(endereco);
            if (bucket == null) {
                bucket = lerBucket(endereco);
                buckets.put(endereco, bucket);
                prefixos.put(endereco, i & ((1 << bucket.profundidade) - 1));
                for (int j = 0; j < bucket.quantidade; j++) {
                    int id = bucket.ids[j];
                    if (id <= 0 || bucket.posicoes[j] < 0) {
                        throw new IOException("Par inválido no bucket de endereço " + endereco);
                    }
                    if (!ids.add(id)) throw new IOException("ID duplicado no hash: " + id);
                    if (diretorio[hash(id)] != endereco) {
                        throw new IOException("ID " + id + " está no bucket incorreto.");
                    }
                }
            }
            int prefixo = i & ((1 << bucket.profundidade) - 1);
            if (prefixo != prefixos.get(endereco)) {
                throw new IOException("Aliases incompatíveis para o bucket " + endereco);
            }
            referencias.merge(endereco, 1, Integer::sum);
        }
        for (Map.Entry<Long, Bucket> item : buckets.entrySet()) {
            int esperado = 1 << (profundidadeGlobal - item.getValue().profundidade);
            if (referencias.get(item.getKey()) != esperado) {
                throw new IOException("Número incorreto de referências para o bucket " + item.getKey());
            }
        }
        long paginasFisicas = (arquivoBuckets.length() - CABECALHO_BUCKETS) / tamanhoPagina;
        if (paginasFisicas != buckets.size()) {
            throw new IOException("Há páginas de buckets sem referência no diretório.");
        }
    }

    @Override
    public void close() throws IOException {
        if (fechado) return;
        fechado = true;
        IOException erro = null;
        try { arquivoDiretorio.close(); } catch (IOException e) { erro = e; }
        try { arquivoBuckets.close(); } catch (IOException e) {
            if (erro == null) erro = e;
            else erro.addSuppressed(e);
        }
        if (erro != null) throw erro;
    }

    private static void criarPasta(Path caminho) throws IOException {
        if (caminho.getParent() != null) Files.createDirectories(caminho.getParent());
    }

    private void criar() throws IOException {
        arquivoBuckets.writeInt(MAGIC_BUCKETS);
        arquivoBuckets.writeInt(VERSAO);
        arquivoBuckets.writeInt(capacidade);
        profundidadeGlobal = 0;
        diretorio = new long[]{CABECALHO_BUCKETS};
        escreverBucket(CABECALHO_BUCKETS, new Bucket(0, new int[0], new long[0]));
        salvarDiretorio();
    }

    private void carregar() throws IOException {
        if (arquivoDiretorio.length() < CABECALHO_DIRETORIO) {
            throw new IOException("Cabeçalho do diretório de hash incompleto.");
        }
        arquivoDiretorio.seek(0);
        if (arquivoDiretorio.readInt() != MAGIC_DIRETORIO || arquivoDiretorio.readInt() != VERSAO) {
            throw new IOException("Formato de diretório de hash inválido.");
        }
        if (arquivoDiretorio.readInt() != capacidade) {
            throw new IOException("A capacidade informada difere da capacidade persistida no hash.");
        }
        profundidadeGlobal = arquivoDiretorio.readInt();
        int tamanho = arquivoDiretorio.readInt();
        if (profundidadeGlobal < 0 || profundidadeGlobal > MAX_PROFUNDIDADE
                || tamanho != (1 << profundidadeGlobal)) {
            throw new IOException("Profundidade ou tamanho do diretório de hash inválido.");
        }
        if (arquivoDiretorio.length() != CABECALHO_DIRETORIO + (long) tamanho * Long.BYTES) {
            throw new IOException("Tamanho físico do diretório de hash inválido.");
        }
        byte[] bytes = new byte[tamanho * Long.BYTES];
        arquivoDiretorio.readFully(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        diretorio = new long[tamanho];
        for (int i = 0; i < tamanho; i++) diretorio[i] = buffer.getLong();
        conferirArquivos();
        for (long endereco : diretorio) conferirEndereco(endereco);
    }

    private void conferirArquivos() throws IOException {
        arquivoDiretorio.seek(0);
        if (arquivoDiretorio.length() != CABECALHO_DIRETORIO + (long) diretorio.length * Long.BYTES
                || arquivoDiretorio.readInt() != MAGIC_DIRETORIO
                || arquivoDiretorio.readInt() != VERSAO
                || arquivoDiretorio.readInt() != capacidade
                || arquivoDiretorio.readInt() != profundidadeGlobal
                || arquivoDiretorio.readInt() != diretorio.length) {
            throw new IOException("Cabeçalho persistido do diretório de hash inconsistente.");
        }
        byte[] referencias = new byte[diretorio.length * Long.BYTES];
        arquivoDiretorio.readFully(referencias);
        ByteBuffer buffer = ByteBuffer.wrap(referencias);
        for (long endereco : diretorio) {
            if (buffer.getLong() != endereco) throw new IOException("Diretório em memória difere do disco.");
        }
        long tamanho = arquivoBuckets.length();
        if (tamanho < CABECALHO_BUCKETS + tamanhoPagina
                || (tamanho - CABECALHO_BUCKETS) % tamanhoPagina != 0) {
            throw new IOException("Tamanho do arquivo de buckets inválido.");
        }
        arquivoBuckets.seek(0);
        if (arquivoBuckets.readInt() != MAGIC_BUCKETS || arquivoBuckets.readInt() != VERSAO
                || arquivoBuckets.readInt() != capacidade) {
            throw new IOException("Cabeçalho do arquivo de buckets inválido.");
        }
    }

    private void salvarDiretorio() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(CABECALHO_DIRETORIO + diretorio.length * Long.BYTES);
        buffer.putInt(MAGIC_DIRETORIO).putInt(VERSAO).putInt(capacidade)
                .putInt(profundidadeGlobal).putInt(diretorio.length);
        for (long endereco : diretorio) buffer.putLong(endereco);
        arquivoDiretorio.seek(0);
        arquivoDiretorio.write(buffer.array());
        arquivoDiretorio.setLength(buffer.capacity());
    }

    private int hash(int id) {
        // Para IDs positivos, a máscara é exatamente id % (1 << p).
        return id & (diretorio.length - 1);
    }

    private Bucket lerBucket(long endereco) throws IOException {
        conferirEndereco(endereco);
        arquivoBuckets.seek(endereco);
        byte[] cabecalho = new byte[CABECALHO_PAGINA];
        arquivoBuckets.readFully(cabecalho);
        ByteBuffer pagina = ByteBuffer.wrap(cabecalho);
        int profundidade = pagina.getInt();
        int quantidade = pagina.getInt();
        conferirCabecalhoBucket(profundidade, quantidade);
        byte[] bytes = new byte[quantidade * TAMANHO_PAR];
        arquivoBuckets.readFully(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int[] ids = new int[quantidade];
        long[] posicoes = new long[quantidade];
        for (int i = 0; i < quantidade; i++) {
            ids[i] = buffer.getInt();
            posicoes[i] = buffer.getLong();
        }
        return new Bucket(profundidade, ids, posicoes);
    }

    private void conferirCabecalhoBucket(int profundidade, int quantidade) throws IOException {
        if (profundidade < 0 || profundidade > profundidadeGlobal
                || quantidade < 0 || quantidade > capacidade) {
            throw new IOException("Profundidade local ou quantidade de registros inválida no bucket.");
        }
    }

    private void conferirEndereco(long endereco) throws IOException {
        if (endereco < CABECALHO_BUCKETS
                || (endereco - CABECALHO_BUCKETS) % tamanhoPagina != 0
                || endereco > arquivoBuckets.length() - tamanhoPagina) {
            throw new IOException("Endereço de bucket inválido: " + endereco);
        }
    }

    private void conferirPossibilidadeDivisao(Bucket bucket, int novoId) throws IOException {
        int hashLimite = novoId & MASCARA_MAXIMA;
        for (int id : bucket.ids) {
            if ((id & MASCARA_MAXIMA) != hashLimite) return;
        }
        // Recusa antes de alocar sucessivos diretórios incapazes de separar as chaves.
        throw new IOException("Colisões excedem o limite de profundidade " + MAX_PROFUNDIDADE
                + "; aumente a capacidade e reconstrua o hash.");
    }

    private void dividir(long endereco, Bucket antigo) throws IOException {
        if (antigo.profundidade >= MAX_PROFUNDIDADE) {
            throw new IOException("Limite de profundidade do hash atingido.");
        }
        if (antigo.profundidade == profundidadeGlobal) {
            long[] ampliado = new long[diretorio.length * 2];
            System.arraycopy(diretorio, 0, ampliado, 0, diretorio.length);
            System.arraycopy(diretorio, 0, ampliado, diretorio.length, diretorio.length);
            diretorio = ampliado;
            profundidadeGlobal++;
        }
        int bitSeparacao = 1 << antigo.profundidade;
        int quantidadeEsquerda = 0;
        for (int id : antigo.ids) if ((id & bitSeparacao) == 0) quantidadeEsquerda++;
        int quantidadeDireita = antigo.quantidade - quantidadeEsquerda;
        int[] idsEsquerda = new int[quantidadeEsquerda];
        int[] idsDireita = new int[quantidadeDireita];
        long[] posicoesEsquerda = new long[quantidadeEsquerda];
        long[] posicoesDireita = new long[quantidadeDireita];
        int esquerda = 0;
        int direita = 0;
        for (int i = 0; i < antigo.quantidade; i++) {
            if ((antigo.ids[i] & bitSeparacao) == 0) {
                idsEsquerda[esquerda] = antigo.ids[i];
                posicoesEsquerda[esquerda++] = antigo.posicoes[i];
            } else {
                idsDireita[direita] = antigo.ids[i];
                posicoesDireita[direita++] = antigo.posicoes[i];
            }
        }
        long novoEndereco = arquivoBuckets.length();
        int novaProfundidade = antigo.profundidade + 1;
        escreverBucket(novoEndereco, new Bucket(novaProfundidade, idsDireita, posicoesDireita));
        escreverBucket(endereco, new Bucket(novaProfundidade, idsEsquerda, posicoesEsquerda));
        for (int i = 0; i < diretorio.length; i++) {
            if (diretorio[i] == endereco && (i & bitSeparacao) != 0) diretorio[i] = novoEndereco;
        }
        salvarDiretorio();
    }

    private void escreverBucket(long endereco, Bucket bucket) throws IOException {
        if (arquivoBuckets.length() < endereco + tamanhoPagina) {
            arquivoBuckets.setLength(endereco + tamanhoPagina);
        }
        ByteBuffer buffer = ByteBuffer.allocate(CABECALHO_PAGINA + bucket.quantidade * TAMANHO_PAR);
        buffer.putInt(bucket.profundidade).putInt(bucket.quantidade);
        for (int i = 0; i < bucket.quantidade; i++) {
            buffer.putInt(bucket.ids[i]).putLong(bucket.posicoes[i]);
        }
        arquivoBuckets.seek(endereco);
        arquivoBuckets.write(buffer.array());
    }

    private void conferirAberto() throws IOException {
        if (fechado) throw new IOException("O índice de hash já foi fechado.");
    }

    private static final class Bucket {
        final int profundidade;
        final int quantidade;
        final int[] ids;
        final long[] posicoes;

        Bucket(int profundidade, int[] ids, long[] posicoes) {
            this.profundidade = profundidade;
            this.quantidade = ids.length;
            this.ids = ids;
            this.posicoes = posicoes;
        }

        int encontrar(int id) {
            for (int i = 0; i < quantidade; i++) if (ids[i] == id) return i;
            return -1;
        }
    }
}
