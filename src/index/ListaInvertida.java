package index;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Lista invertida persistente: termo -> (id, endereço no arquivo de dados).
 *
 * O dicionário e os postings ficam em memória; os carros continuam no arquivo
 * de dados e são lidos diretamente pelo endereço. Essa escolha simplifica a
 * interseção das duas listas e é adequada ao tamanho da base deste trabalho.
 * Não é uma implementação para índices maiores que a memória disponível.
 *
 * Formato: cabeçalho [magic:int, versão:int] seguido de eventos
 * [tamanho:int, operação:byte, tamanhoTermo:int, termo:UTF-8, id:int,
 * posição:long, CRC32:int]. O tamanho inclui apenas o conteúdo do evento.
 * Adicionar substitui o endereço anterior do mesmo id; remover usa posição -1.
 * O histórico permite atualizar só o posting afetado, sem reescrever a lista.
 * compactar() elimina eventos antigos; sincronizar() força a gravação em disco.
 */
public final class ListaInvertida implements AutoCloseable {
    private static final int MAGIC = 0x4C495632; // LIV2
    private static final int VERSAO = 1;
    private static final byte ADICIONAR = 1;
    private static final byte REMOVER = 2;
    private static final int TAMANHO_FIXO_EVENTO = 17;
    private static final int MAX_TERMO_BYTES = 1_048_576;
    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACOS = Pattern.compile("[\\s\\p{Z}]+");

    private final Path arquivo;
    private final TreeMap<String, Map<Integer, Long>> termos;
    private FileChannel canal;
    private long quantidadePostings;
    private long quantidadeEventos;

    public ListaInvertida(Path arquivo) throws IOException {
        this.arquivo = arquivo.toAbsolutePath().normalize();
        Path diretorio = this.arquivo.getParent();
        if (diretorio != null) Files.createDirectories(diretorio);
        if (!Files.exists(this.arquivo)) {
            try (DataOutputStream saida = new DataOutputStream(Files.newOutputStream(
                    this.arquivo, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                saida.writeInt(MAGIC);
                saida.writeInt(VERSAO);
            }
        }
        Estado estado = lerArquivo(this.arquivo);
        this.termos = estado.termos;
        this.quantidadePostings = estado.postings;
        this.quantidadeEventos = estado.eventos;
        this.canal = abrirCanal();
    }

    /** Acentos, caixa e espaços não diferenciam termos de pesquisa. */
    public static String normalizar(String termo) {
        if (termo == null) return "";
        String semAcentos = MARCAS.matcher(Normalizer.normalize(termo, Normalizer.Form.NFD))
                .replaceAll("");
        return ESPACOS.matcher(semAcentos.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** Termos vazios são ignorados. Repetições do mesmo posting não geram duplicatas. */
    public synchronized void adicionar(String termo, int id, long posicao) throws IOException {
        garantirAberta();
        validarId(id);
        if (posicao < 0) throw new IllegalArgumentException("Posição não pode ser negativa.");
        String chave = normalizar(termo);
        if (chave.isEmpty()) return;
        Map<Integer, Long> postings = termos.get(chave);
        Long anterior = postings == null ? null : postings.get(id);
        if (anterior != null && anterior.longValue() == posicao) return;
        registrar(ADICIONAR, chave, id, posicao);
        if (postings == null) {
            postings = new HashMap<>();
            termos.put(chave, postings);
        }
        postings.put(id, posicao);
        if (anterior == null) quantidadePostings++;
    }

    public synchronized void remover(String termo, int id) throws IOException {
        garantirAberta();
        validarId(id);
        String chave = normalizar(termo);
        Map<Integer, Long> postings = termos.get(chave);
        if (postings == null || !postings.containsKey(id)) return;
        registrar(REMOVER, chave, id, -1L);
        postings.remove(id);
        quantidadePostings--;
        if (postings.isEmpty()) termos.remove(chave);
    }

    /** Cópia ordenada por id: o chamador não consegue alterar o índice por acidente. */
    public synchronized Map<Integer, Long> buscar(String termo) {
        garantirAberta();
        Map<Integer, Long> encontrados = termos.get(normalizar(termo));
        return encontrados == null ? new TreeMap<>() : new TreeMap<>(encontrados);
    }

    /** Cópia profunda, útil para inspeção e comparação dos índices. */
    public synchronized Map<String, Map<Integer, Long>> listar() {
        garantirAberta();
        Map<String, Map<Integer, Long>> copia = new TreeMap<>();
        for (Map.Entry<String, Map<Integer, Long>> termo : termos.entrySet()) {
            copia.put(termo.getKey(), new TreeMap<>(termo.getValue()));
        }
        return copia;
    }

    public synchronized String resumo() {
        garantirAberta();
        return "Lista invertida " + arquivo.getFileName() + ": termos=" + termos.size()
                + ", postings=" + quantidadePostings + ", eventos=" + quantidadeEventos;
    }

    /** Confere cabeçalho, enquadramento, CRC, valores e equivalência com a memória. */
    public synchronized void validar() throws IOException {
        garantirAberta();
        Estado persistido = lerArquivo(arquivo);
        if (persistido.postings != quantidadePostings || persistido.eventos != quantidadeEventos
                || !persistido.termos.equals(termos)) {
            throw new IOException("Lista invertida divergente entre memória e disco: " + arquivo);
        }
    }

    /** Usado ao concluir uma alteração do conjunto dados + índices. */
    public synchronized void sincronizar() throws IOException {
        garantirAberta();
        canal.force(true);
    }

    /**
     * Regrava apenas postings atuais. O arquivo original só é substituído depois
     * que o temporário está completo e sincronizado, por uma troca atômica.
     * Se o sistema de arquivos não suportar a troca, mantém o original e falha.
     */
    public synchronized void compactar() throws IOException {
        garantirAberta();
        Path temporario = Files.createTempFile(arquivo.getParent(), "lista-", ".tmp");
        try {
            try (FileChannel destino = FileChannel.open(temporario, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer cabecalho = ByteBuffer.allocate(8).putInt(MAGIC).putInt(VERSAO);
                cabecalho.flip();
                escreverCompleto(destino, cabecalho);
                for (Map.Entry<String, Map<Integer, Long>> termo : termos.entrySet()) {
                    for (Map.Entry<Integer, Long> posting : termo.getValue().entrySet()) {
                        escreverCompleto(destino, codificarEvento(ADICIONAR, termo.getKey(),
                                posting.getKey(), posting.getValue()));
                    }
                }
                destino.force(true);
            }
            canal.close();
            try {
                Files.move(temporario, arquivo, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                quantidadeEventos = quantidadePostings;
            } finally {
                canal = abrirCanal();
            }
        } finally {
            Files.deleteIfExists(temporario);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (canal == null || !canal.isOpen()) return;
        try {
            canal.force(true);
        } finally {
            canal.close();
        }
    }

    private FileChannel abrirCanal() throws IOException {
        FileChannel aberto = FileChannel.open(arquivo, StandardOpenOption.READ, StandardOpenOption.WRITE);
        aberto.position(aberto.size());
        return aberto;
    }

    private void registrar(byte operacao, String termo, int id, long posicao) throws IOException {
        ByteBuffer evento = codificarEvento(operacao, termo, id, posicao);
        long inicio = canal.size();
        canal.position(inicio);
        try {
            escreverCompleto(canal, evento);
            quantidadeEventos++;
        } catch (IOException erro) {
            // Uma falha de escrita não deve deixar metade de um evento no índice.
            try {
                canal.truncate(inicio);
                canal.position(inicio);
            } catch (IOException falhaAoReverter) {
                erro.addSuppressed(falhaAoReverter);
            }
            throw erro;
        }
    }

    private static ByteBuffer codificarEvento(byte operacao, String termo, int id, long posicao)
            throws IOException {
        byte[] texto = termo.getBytes(StandardCharsets.UTF_8);
        if (texto.length > MAX_TERMO_BYTES || !termo.equals(new String(texto, StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Termo muito longo ou com caracteres Unicode inválidos.");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(TAMANHO_FIXO_EVENTO + texto.length);
        try (DataOutputStream evento = new DataOutputStream(bytes)) {
            evento.writeByte(operacao);
            evento.writeInt(texto.length);
            evento.write(texto);
            evento.writeInt(id);
            evento.writeLong(posicao);
        }
        byte[] conteudo = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(conteudo);
        ByteBuffer completo = ByteBuffer.allocate(conteudo.length + 8);
        completo.putInt(conteudo.length).put(conteudo).putInt((int) crc.getValue());
        completo.flip();
        return completo;
    }

    private static void escreverCompleto(FileChannel destino, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) destino.write(bytes);
    }

    private static Estado lerArquivo(Path arquivo) throws IOException {
        Estado estado = new Estado();
        long tamanhoArquivo = Files.size(arquivo);
        try (DataInputStream entrada = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(arquivo), 64 * 1024))) {
            if (entrada.readInt() != MAGIC || entrada.readInt() != VERSAO) {
                throw new IOException("Cabeçalho inválido da lista invertida: " + arquivo);
            }
            long lidos = 8;
            while (lidos < tamanhoArquivo) {
                int tamanho = entrada.readInt();
                if (tamanho < TAMANHO_FIXO_EVENTO + 1 || tamanho > TAMANHO_FIXO_EVENTO + MAX_TERMO_BYTES
                        || (long) tamanho + 8 > tamanhoArquivo - lidos) {
                    throw new IOException("Evento inválido ou truncado na posição " + lidos + ": " + arquivo);
                }
                byte[] conteudo = new byte[tamanho];
                entrada.readFully(conteudo);
                int crcGravado = entrada.readInt();
                CRC32 crc = new CRC32();
                crc.update(conteudo);
                if (crcGravado != (int) crc.getValue()) {
                    throw new IOException("CRC inválido na posição " + lidos + ": " + arquivo);
                }
                aplicarEvento(estado, conteudo, arquivo);
                lidos += tamanho + 8L;
                estado.eventos++;
            }
        } catch (EOFException erro) {
            throw new IOException("Lista invertida truncada: " + arquivo, erro);
        }
        return estado;
    }

    private static void aplicarEvento(Estado estado, byte[] conteudo, Path arquivo) throws IOException {
        try (DataInputStream entrada = new DataInputStream(new ByteArrayInputStream(conteudo))) {
            byte operacao = entrada.readByte();
            int tamanhoTermo = entrada.readInt();
            if (tamanhoTermo != conteudo.length - TAMANHO_FIXO_EVENTO || tamanhoTermo <= 0) {
                throw new IOException("Tamanho do termo inválido: " + arquivo);
            }
            byte[] texto = new byte[tamanhoTermo];
            entrada.readFully(texto);
            String termo = new String(texto, StandardCharsets.UTF_8);
            int id = entrada.readInt();
            long posicao = entrada.readLong();
            if (!java.util.Arrays.equals(texto, termo.getBytes(StandardCharsets.UTF_8))
                    || !termo.equals(normalizar(termo)) || id <= 0
                    || (operacao != ADICIONAR && operacao != REMOVER)
                    || (operacao == ADICIONAR ? posicao < 0 : posicao != -1L)) {
                throw new IOException("Conteúdo inválido em evento da lista invertida: " + arquivo);
            }
            Map<Integer, Long> postings = estado.termos.get(termo);
            if (operacao == ADICIONAR) {
                if (postings == null) {
                    postings = new HashMap<>();
                    estado.termos.put(termo, postings);
                }
                if (postings.put(id, posicao) == null) estado.postings++;
            } else {
                if (postings == null || postings.remove(id) == null) {
                    throw new IOException("Remoção de posting inexistente no histórico: " + arquivo);
                }
                estado.postings--;
                if (postings.isEmpty()) estado.termos.remove(termo);
            }
        }
    }

    private static void validarId(int id) {
        if (id <= 0) throw new IllegalArgumentException("O id deve ser positivo.");
    }

    private void garantirAberta() {
        if (canal == null || !canal.isOpen()) throw new IllegalStateException("Lista invertida fechada.");
    }

    private static final class Estado {
        final TreeMap<String, Map<Integer, Long>> termos = new TreeMap<>();
        long postings;
        long eventos;
    }
}
