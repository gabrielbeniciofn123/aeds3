package dao;

import index.ArvoreBMais;
import index.HashEstendido;
import index.ListaInvertida;
import model.Carro;
import util.CsvUtil;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Coordena o arquivo herdado do TP1 e os quatro índices do TP2. */
public final class ArquivoIndexado implements AutoCloseable {
    public enum Metodo { ARVORE, HASH, LISTA }
    private final Path pasta, banco, configuracao, pendente, diario, cargaPendente, antesDaCarga;
    private final RandomAccessFile dados;
    private final FileLock trava;
    private ArvoreBMais arvore;
    private HashEstendido hash;
    private ListaInvertida anos, caracteristicas;
    private final int ordem;
    private final double percentual;
    private int tamanhoInicial, capacidade;
    private long tamanhoConhecido, modificacaoConhecida;
    private boolean fechado;
    private String ultimoAcesso = "Nenhuma busca realizada.";

    public ArquivoIndexado(Path pasta, int ordem, double percentual) throws IOException {
        if (ordem < 3 || ordem > 1024) throw new IllegalArgumentException("Ordem deve estar entre 3 e 1024.");
        if (!Double.isFinite(percentual) || percentual <= 0 || percentual > 100)
            throw new IllegalArgumentException("Percentual deve estar entre 0 (exclusivo) e 100.");
        this.pasta = pasta;
        this.ordem = ordem;
        this.percentual = percentual;
        Files.createDirectories(pasta);
        banco = pasta.resolve("dados.db");
        configuracao = pasta.resolve("config.properties");
        pendente = pasta.resolve("indices.pendentes");
        diario = pasta.resolve("transacao.bin");
        cargaPendente = pasta.resolve("carga.pendente");
        antesDaCarga = pasta.resolve("antes-da-carga.db");
        dados = new RandomAccessFile(banco.toFile(), "rw");
        FileLock tentativa;
        try { tentativa = dados.getChannel().tryLock(); }
        catch (RuntimeException | IOException e) { dados.close(); throw new IOException("A base já está aberta em outro processo.", e); }
        if (tentativa == null) { dados.close(); throw new IOException("A base já está aberta em outro processo."); }
        trava = tentativa;
        try {
            // Uma carga interrompida pode ter deixado até o cabeçalho incompleto.
            boolean recuperou = recuperarPendencias();
            if (dados.length() == 0) {
                boolean baseAnterior = Files.exists(configuracao);
                for (String nome : nomesIndices()) baseAnterior |= Files.exists(pasta.resolve(nome));
                if (baseAnterior) throw new IOException("Arquivo de dados vazio ou ausente em uma base existente. Restaure o banco a partir de um backup.");
                dados.writeInt(0);
            }
            if (dados.length() < 4) throw new IOException("Cabeçalho do arquivo de dados incompleto.");
            Properties p = new Properties();
            if (Files.exists(configuracao)) {
                try (InputStream in = Files.newInputStream(configuracao)) { p.load(in); }
                catch (IllegalArgumentException e) { throw new IOException("Configuração corrompida: " + configuracao, e); }
                tamanhoInicial = inteiroConfigurado(p, "tamanhoInicial");
                if (tamanhoInicial < 0) throw new IOException("Tamanho inicial inválido na configuração.");
            } else tamanhoInicial = contarAtivos();
            capacidade = calcularCapacidade(tamanhoInicial, percentual);
            boolean reconstruir = recuperou || Files.exists(pendente)
                    || !p.getProperty("ordem", "").equals(Integer.toString(ordem))
                    || !p.getProperty("percentual", "").equals(Double.toString(percentual))
                    || !p.getProperty("tamanhoDados", "").equals(Long.toString(dados.length()))
                    || !p.getProperty("modificacaoDados", "").equals(Long.toString(Files.getLastModifiedTime(banco).toMillis()));
            for (String nome : nomesIndices()) if (!Files.exists(pasta.resolve(nome))) reconstruir = true;
            if (reconstruir) reconstruirIndicesInterno();
            else {
                try { abrirIndices(); memorizarEstado(); }
                catch (IOException indiceCorrompido) {
                    // Os índices são derivados. Falhas de formato não exigem descartar dados.
                    reconstruirIndicesInterno();
                }
            }
        } catch (IOException | RuntimeException e) {
            try { fecharIndices(); } catch (IOException suprimida) { e.addSuppressed(suprimida); }
            trava.release(); dados.close(); throw e;
        }
    }

    public static int calcularCapacidade(int inicial, double percentual) {
        if (inicial < 0 || !Double.isFinite(percentual) || percentual <= 0 || percentual > 100)
            throw new IllegalArgumentException("Parâmetros inválidos para a capacidade do hash.");
        return Math.max(1, (int) Math.ceil(inicial * percentual / 100.0));
    }
    private static List<String> nomesIndices() {
        return Arrays.asList("arvore.bmais", "hash.diretorio", "hash.buckets", "anos.lista", "caracteristicas.lista");
    }
    private void abrirIndices() throws IOException {
        arvore = new ArvoreBMais(pasta.resolve("arvore.bmais"), ordem);
        hash = new HashEstendido(pasta.resolve("hash.diretorio"), pasta.resolve("hash.buckets"), capacidade);
        anos = new ListaInvertida(pasta.resolve("anos.lista"));
        caracteristicas = new ListaInvertida(pasta.resolve("caracteristicas.lista"));
    }
    private void fecharIndices() throws IOException {
        IOException falha = null;
        for (AutoCloseable indice : new AutoCloseable[]{arvore, hash, anos, caracteristicas}) {
            if (indice != null) try { indice.close(); }
            catch (Exception e) { if (falha == null) falha = new IOException("Falha ao fechar índice", e); else falha.addSuppressed(e); }
        }
        arvore = null; hash = null; anos = null; caracteristicas = null;
        if (falha != null) throw falha;
    }
    private void conferirEstado() throws IOException {
        if (fechado) throw new IOException("Base fechada.");
        boolean recuperou = recuperarPendencias();
        if (recuperou || Files.exists(pendente) || dados.length() != tamanhoConhecido
                || Files.getLastModifiedTime(banco).toMillis() != modificacaoConhecida) reconstruirIndicesInterno();
    }
    private void memorizarEstado() throws IOException {
        tamanhoConhecido = dados.length();
        modificacaoConhecida = Files.getLastModifiedTime(banco).toMillis();
    }
    private void gravarConfiguracao() throws IOException {
        memorizarEstado();
        Properties p = new Properties();
        p.setProperty("ordem", Integer.toString(ordem));
        p.setProperty("percentual", Double.toString(percentual));
        p.setProperty("tamanhoInicial", Integer.toString(tamanhoInicial));
        p.setProperty("capacidadeBucket", Integer.toString(capacidade));
        p.setProperty("tamanhoDados", Long.toString(tamanhoConhecido));
        p.setProperty("modificacaoDados", Long.toString(modificacaoConhecida));
        Path tmp = pasta.resolve("config.properties.tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) { p.store(out, "TP2 - capacidade fixa sobre a carga inicial"); }
        sincronizarArquivo(tmp);
        substituir(tmp, configuracao);
    }
    private static int inteiroConfigurado(Properties p, String chave) throws IOException {
        try { return Integer.parseInt(p.getProperty(chave)); }
        catch (NumberFormatException e) { throw new IOException("Campo " + chave + " ausente ou inválido na configuração.", e); }
    }
    private static void sincronizarArquivo(Path caminho) throws IOException {
        try (RandomAccessFile arquivo = new RandomAccessFile(caminho.toFile(), "rw")) { arquivo.getFD().sync(); }
    }
    private void marcarIndicesPendentes() throws IOException {
        Files.write(pendente, new byte[]{1});
        sincronizarArquivo(pendente);
    }
    private void sincronizarIndices() throws IOException {
        arvore.sincronizar(); hash.sincronizar(); anos.sincronizar(); caracteristicas.sincronizar();
    }
    private static void substituir(Path origem, Path destino) throws IOException {
        try { Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** Varreduras são reservadas à carga, reconstrução e auditoria, nunca ao CRUD por ID. */
    public void reconstruirIndices() throws IOException {
        if (fechado) throw new IOException("Base fechada.");
        recuperarPendencias();
        reconstruirIndicesInterno();
    }
    private void reconstruirIndicesInterno() throws IOException {
        marcarIndicesPendentes();
        fecharIndices();
        for (String nome : nomesIndices()) Files.deleteIfExists(pasta.resolve(nome));
        abrirIndices();
        Set<Integer> ids = new HashSet<>();
        percorrer((pos, c) -> {
            if (!ids.add(c.getId())) throw new IOException("ID ativo duplicado: " + c.getId());
            indexar(c, pos);
        });
        dados.seek(0);
        int ultimo = dados.readInt();
        if (ultimo < 0 || ids.stream().anyMatch(id -> id > ultimo)) throw new IOException("Último ID incompatível com os registros.");
        dados.getFD().sync();
        sincronizarIndices();
        gravarConfiguracao();
        Files.deleteIfExists(pendente);
    }
    private Set<String> termos(Carro c) {
        Set<String> termos = new TreeSet<>();
        for (String valor : c.getCaracteristicas()) {
            String termo = ListaInvertida.normalizar(valor);
            if (!termo.isEmpty()) termos.add(termo);
        }
        return termos;
    }
    private void indexar(Carro c, long pos) throws IOException {
        arvore.inserir(c.getId(), pos); hash.inserir(c.getId(), pos);
        anos.adicionar(Integer.toString(c.getAno()), c.getId(), pos);
        for (String termo : termos(c)) caracteristicas.adicionar(termo, c.getId(), pos);
    }
    private void desindexar(Carro c) throws IOException {
        arvore.remover(c.getId()); hash.remover(c.getId());
        anos.remover(Integer.toString(c.getAno()), c.getId());
        for (String termo : termos(c)) caracteristicas.remover(termo, c.getId());
    }
    public Carro ler(int id, Metodo metodo) throws IOException { return ler(id, metodo, null, null); }
    public Carro ler(int id, Metodo metodo, Integer ano, String termo) throws IOException {
        conferirEstado();
        long pos = localizar(id, metodo, ano, termo);
        return pos < 0 ? null : lerPosicao(pos, id);
    }
    private long localizar(int id, Metodo metodo, Integer ano, String termo) throws IOException {
        Objects.requireNonNull(metodo, "Escolha o índice.");
        long pos;
        if (metodo == Metodo.ARVORE) pos = arvore.buscar(id);
        else if (metodo == Metodo.HASH) pos = hash.buscar(id);
        else pos = candidatos(ano, termo).getOrDefault(id, -1L);
        ultimoAcesso = "Índice: " + metodo + " | id=" + id + " | endereço=" + pos;
        return pos;
    }
    private Map<Integer, Long> candidatos(Integer ano, String termo) throws IOException {
        boolean temTermo = termo != null && !ListaInvertida.normalizar(termo).isEmpty();
        if (ano == null && !temTermo) throw new IllegalArgumentException("Informe ano e/ou característica para a lista invertida.");
        Map<Integer, Long> resultado = ano == null ? caracteristicas.buscar(termo) : anos.buscar(ano.toString());
        if (ano != null && temTermo) {
            Map<Integer, Long> outra = caracteristicas.buscar(termo);
            resultado.entrySet().removeIf(e -> !Objects.equals(outra.get(e.getKey()), e.getValue()));
        }
        return resultado;
    }
    public List<Carro> pesquisar(Integer ano, String termo) throws IOException {
        conferirEstado();
        List<Carro> resultado = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : new TreeMap<>(candidatos(ano, termo)).entrySet()) resultado.add(lerPosicao(e.getValue(), e.getKey()));
        ultimoAcesso = "Índice: LISTA | ano=" + ano + " | característica=" + termo + " | resultados=" + resultado.size();
        return resultado;
    }
    public String getUltimoAcesso() { return ultimoAcesso; }
    public long endereco(int id, Metodo metodo, Integer ano, String termo) throws IOException {
        conferirEstado(); return localizar(id, metodo, ano, termo);
    }

    public int criar(Carro c, Metodo metodo) throws IOException {
        conferirEstado(); Objects.requireNonNull(metodo, "Escolha o índice.");
        dados.seek(0); int anterior = dados.readInt();
        if (anterior == Integer.MAX_VALUE) throw new IOException("Limite de IDs atingido.");
        c.setId(anterior + 1);
        if (c.getCodigo().isBlank()) c.setCodigo(Carro.codigoPorId(c.getId()));
        validarCarro(c); byte[] bytes = c.toByteArray();
        iniciarTransacao(-1);
        try {
            long pos = dados.length(); dados.seek(pos); escrever(bytes);
            dados.seek(0); dados.writeInt(c.getId()); dados.getFD().sync();
            indexar(c, pos);
            // No Create não há registro anterior: a escolha verifica a inserção pelo índice solicitado.
            long encontrado = localizar(c.getId(), metodo, c.getAno(), null);
            if (encontrado != pos) throw new IOException("Índice não confirmou a criação.");
            concluirTransacao(); return c.getId();
        } catch (IOException | RuntimeException e) { recuperarFalha(e); throw e; }
    }
    public boolean atualizar(Carro novo, Metodo metodo, Integer anoBusca, String termoBusca) throws IOException {
        conferirEstado();
        long pos = localizar(novo.getId(), metodo, anoBusca, termoBusca);
        if (pos < 0) return false;
        Carro antigo = lerPosicao(pos, novo.getId());
        if (novo.getCodigo().isBlank()) novo.setCodigo(antigo.getCodigo());
        validarCarro(novo); byte[] bytes = novo.toByteArray();
        iniciarTransacao(pos);
        try {
            dados.seek(pos + 1); int tamanho = dados.readInt();
            long destino = pos;
            if (tamanho == bytes.length) { dados.seek(pos + 5); dados.write(bytes); }
            else {
                destino = dados.length(); dados.seek(destino); escrever(bytes);
                dados.seek(pos); dados.writeByte(1);
            }
            dados.getFD().sync();
            desindexar(antigo); indexar(novo, destino);
            concluirTransacao(); return true;
        } catch (IOException | RuntimeException e) { recuperarFalha(e); throw e; }
    }
    public boolean excluir(int id, Metodo metodo, Integer anoBusca, String termoBusca) throws IOException {
        conferirEstado(); long pos = localizar(id, metodo, anoBusca, termoBusca);
        if (pos < 0) return false;
        Carro antigo = lerPosicao(pos, id);
        iniciarTransacao(pos);
        try {
            dados.seek(pos); dados.writeByte(1); dados.getFD().sync(); desindexar(antigo);
            concluirTransacao(); return true;
        } catch (IOException | RuntimeException e) { recuperarFalha(e); throw e; }
    }
    private void validarCarro(Carro c) {
        if (c.getId() <= 0 || c.getNome().isBlank() || c.getAno() <= 0 || c.getDataRegistro() == null)
            throw new IllegalArgumentException("ID, nome, ano e data precisam ser válidos.");
        if (c.getCaracteristicas().size() > 1000) throw new IllegalArgumentException("Máximo de 1000 características por carro.");
    }
    private void escrever(byte[] bytes) throws IOException { dados.writeByte(0); dados.writeInt(bytes.length); dados.write(bytes); }
    private Carro lerPosicao(long pos, int id) throws IOException {
        if (pos < 4 || pos + 5 > dados.length()) throw new IOException("Endereço de índice inválido: " + pos);
        dados.seek(pos); int lapide = dados.readUnsignedByte(); int tamanho = dados.readInt();
        if (lapide != 0 || tamanho < 4 || tamanho > dados.length() - dados.getFilePointer()) throw new IOException("Índice aponta para registro inválido: " + pos);
        byte[] bytes = new byte[tamanho]; dados.readFully(bytes);
        Carro c = new Carro(); c.fromByteArray(bytes);
        if (c.getId() != id) throw new IOException("ID do índice difere do registro no endereço " + pos);
        return c;
    }

    /** Diário de undo de uma operação: permite restaurar cabeçalho, registro e tamanho. */
    private void iniciarTransacao(long pos) throws IOException {
        Path tmp = pasta.resolve("transacao.tmp");
        try (RandomAccessFile log = new RandomAccessFile(tmp.toFile(), "rw")) {
            log.setLength(0); log.writeInt(0x54503255); log.writeLong(dados.length());
            dados.seek(0); log.writeInt(dados.readInt()); log.writeLong(pos);
            byte[] antes = new byte[0];
            if (pos >= 4) {
                dados.seek(pos + 1); int tamanho = dados.readInt();
                antes = new byte[tamanho + 5]; dados.seek(pos); dados.readFully(antes);
            }
            log.writeInt(antes.length); log.write(antes); log.getFD().sync();
        }
        substituir(tmp, diario);
        marcarIndicesPendentes();
    }
    private void concluirTransacao() throws IOException {
        // O diário só desaparece depois de todos os arquivos confirmarem suas gravações.
        sincronizarIndices();
        gravarConfiguracao();
        Files.deleteIfExists(pendente);
        Files.delete(diario); // Ponto de confirmação: até aqui uma interrupção desfaz o CRUD.
    }
    private void desfazerTransacao() throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(diario))) {
            if (in.readInt() != 0x54503255) throw new IOException("Diário de transação inválido.");
            long tamanho = in.readLong(); int ultimo = in.readInt(); long pos = in.readLong(); int n = in.readInt();
            if (tamanho < 4 || ultimo < 0 || n < 0 || n > tamanho || Files.size(diario) != 28L + n
                    || (pos == -1 && n != 0) || (pos != -1 && (pos < 4 || n < 5 || n > tamanho - pos)))
                throw new IOException("Diário de transação corrompido.");
            byte[] bytes = new byte[n]; in.readFully(bytes);
            dados.setLength(tamanho); dados.seek(0); dados.writeInt(ultimo);
            if (pos >= 4) { dados.seek(pos); dados.write(bytes); }
            dados.getFD().sync();
        }
        marcarIndicesPendentes(); Files.delete(diario);
    }
    private boolean recuperarPendencias() throws IOException {
        boolean recuperou = false;
        if (Files.exists(cargaPendente)) { desfazerCarga(); recuperou = true; }
        if (Files.exists(diario)) { desfazerTransacao(); recuperou = true; }
        return recuperou;
    }
    private void recuperarFalha(Exception original) {
        try { recuperarPendencias(); reconstruirIndicesInterno(); }
        catch (Exception e) { original.addSuppressed(e); }
    }

    private void iniciarCarga() throws IOException {
        // O backup só substitui a versão anterior quando a cópia completa está no disco.
        Path tmpBackup = pasta.resolve("antes-da-carga.tmp");
        dados.getFD().sync();
        Files.copy(banco, tmpBackup, StandardCopyOption.REPLACE_EXISTING);
        sincronizarArquivo(tmpBackup);
        substituir(tmpBackup, antesDaCarga);
        Properties p = new Properties();
        p.setProperty("formato", "TP2-CARGA-1");
        p.setProperty("tamanhoInicial", Integer.toString(tamanhoInicial));
        p.setProperty("capacidadeBucket", Integer.toString(capacidade));
        p.setProperty("percentual", Double.toString(percentual));
        p.setProperty("tamanhoAnterior", Long.toString(dados.length()));
        dados.seek(0); p.setProperty("ultimoIdAnterior", Integer.toString(dados.readInt()));
        Path tmp = pasta.resolve("carga.pendente.tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) { p.store(out, "Undo da carga completa"); }
        sincronizarArquivo(tmp);
        substituir(tmp, cargaPendente);
    }

    private void desfazerCarga() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(cargaPendente)) { p.load(in); }
        catch (IllegalArgumentException e) { throw new IOException("Marcador de carga corrompido.", e); }
        if (!"TP2-CARGA-1".equals(p.getProperty("formato"))) throw new IOException("Marcador de carga inválido.");
        int inicialAnterior = inteiroConfigurado(p, "tamanhoInicial");
        int capacidadeAnterior = inteiroConfigurado(p, "capacidadeBucket");
        int ultimoAnterior = inteiroConfigurado(p, "ultimoIdAnterior");
        long tamanhoAnterior;
        double percentualAnterior;
        try {
            tamanhoAnterior = Long.parseLong(p.getProperty("tamanhoAnterior"));
            percentualAnterior = Double.parseDouble(p.getProperty("percentual", "NaN"));
        } catch (NumberFormatException e) { throw new IOException("Metadados da carga corrompidos.", e); }
        if (inicialAnterior < 0 || tamanhoAnterior < 4 || ultimoAnterior < 0
                || !Double.isFinite(percentualAnterior) || percentualAnterior <= 0 || percentualAnterior > 100
                || capacidadeAnterior != calcularCapacidade(inicialAnterior, percentualAnterior))
            throw new IOException("Metadados anteriores à carga são inválidos.");
        if (!Files.exists(antesDaCarga) || Files.size(antesDaCarga) != tamanhoAnterior)
            throw new IOException("Backup anterior à carga ausente ou incompleto; a base não foi modificada pela recuperação.");
        try (DataInputStream in = new DataInputStream(Files.newInputStream(antesDaCarga))) {
            if (in.readInt() != ultimoAnterior) throw new IOException("Cabeçalho do backup anterior à carga inválido.");
        }
        marcarIndicesPendentes();
        copiarParaDados(antesDaCarga);
        tamanhoInicial = inicialAnterior;
        // Uma reabertura pode escolher outro percentual explicitamente; N inicial é preservado.
        capacidade = calcularCapacidade(tamanhoInicial, percentual);
        gravarConfiguracao();
        Files.delete(cargaPendente);
    }

    private void copiarParaDados(Path origem) throws IOException {
        try (InputStream in = Files.newInputStream(origem)) {
            dados.setLength(0); dados.seek(0); byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) dados.write(buffer, 0, n);
            dados.getFD().sync();
        }
    }

    /** Importa primeiro em temporário: CSV inválido não apaga a base em uso. */
    public int importar(Path csv) throws IOException {
        conferirEstado();
        // Um temporário exclusivo evita truncar o CSV caso ele tenha o mesmo nome
        // do antigo temporário fixo (carga.tmp), inclusive através de links.
        Path tmp = Files.createTempFile(pasta, "carga-", ".tmp");
        try {
            int total = 0;
            try (BufferedReader in = Files.newBufferedReader(csv);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(0); String linha; int numero = 0; boolean primeiroConteudo = true;
                while ((linha = in.readLine()) != null) {
                    numero++;
                    if (numero == 1 && linha.startsWith("\uFEFF")) linha = linha.substring(1);
                    if (linha.isBlank()) continue;
                    try {
                        List<String> campos = CsvUtil.separarLinha(linha, ';');
                        if (primeiroConteudo) {
                            primeiroConteudo = false;
                            if (cabecalhoCsv(campos)) continue;
                        }
                        if (campos.size() != 4) throw new IllegalArgumentException("São necessários quatro campos.");
                        List<String> caracts = new ArrayList<>();
                        for (String t : campos.get(1).split("\\|")) if (!t.trim().isEmpty()) caracts.add(t.trim());
                        Carro c = new Carro(total + 1, Carro.codigoPorId(total + 1), campos.get(0),
                                LocalDate.parse(campos.get(3)), caracts, Integer.parseInt(campos.get(2)));
                        validarCarro(c); byte[] bytes = c.toByteArray();
                        out.writeByte(0); out.writeInt(bytes.length); out.write(bytes); total++;
                    } catch (RuntimeException | IOException e) { throw new IOException("CSV inválido na linha " + numero + ": " + e.getMessage(), e); }
                }
            }
            try (RandomAccessFile carga = new RandomAccessFile(tmp.toFile(), "rw")) { carga.writeInt(total); carga.getFD().sync(); }
            // Até a remoção de carga.pendente, uma falha restaura o banco e o N inicial anteriores.
            iniciarCarga();
            try {
                marcarIndicesPendentes();
                copiarParaDados(tmp);
                tamanhoInicial = total; capacidade = calcularCapacidade(total, percentual);
                reconstruirIndicesInterno();
                Files.delete(cargaPendente); // Confirmação da substituição completa da base.
                return total;
            } catch (IOException | RuntimeException e) {
                recuperarFalha(e);
                throw e;
            }
        } finally { Files.deleteIfExists(tmp); }
    }

    private static boolean cabecalhoCsv(List<String> campos) {
        if (campos.size() != 4) return false;
        String ultimo = campos.get(3).toLowerCase(Locale.ROOT);
        return campos.get(0).equalsIgnoreCase("nome")
                && campos.get(1).equalsIgnoreCase("caracteristicas")
                && campos.get(2).equalsIgnoreCase("ano")
                && (ultimo.equals("data_registro") || ultimo.equals("dataregistro") || ultimo.equals("data"));
    }

    private interface Visitante { void visitar(long pos, Carro c) throws IOException; }
    private void percorrer(Visitante visitante) throws IOException {
        long pos = 4;
        while (pos < dados.length()) {
            dados.seek(pos);
            if (dados.length() - pos < 5) throw new IOException("Registro truncado em " + pos);
            int lapide = dados.readUnsignedByte(); int tamanho = dados.readInt();
            if (lapide > 1 || tamanho < 4 || tamanho > dados.length() - dados.getFilePointer()) throw new IOException("Registro corrompido em " + pos);
            if (lapide == 0) {
                byte[] bytes = new byte[tamanho]; dados.readFully(bytes); Carro c = new Carro(); c.fromByteArray(bytes);
                if (c.getId() <= 0) throw new IOException("ID inválido em " + pos);
                visitante.visitar(pos, c);
            }
            pos += 5L + tamanho;
        }
    }
    private int contarAtivos() throws IOException { int[] n = {0}; percorrer((pos, c) -> n[0]++); return n[0]; }
    public String resumo() throws IOException {
        conferirEstado();
        return "Base inicial: " + tamanhoInicial + " | Hash: " + percentual + "% | X=" + capacidade + "\n"
                + arvore.resumo() + "\n" + hash.resumo() + "\nAno: " + anos.resumo() + "\nCaracterísticas: " + caracteristicas.resumo();
    }
    public String auditar() throws IOException {
        conferirEstado(); arvore.validar(); hash.validar(); anos.validar(); caracteristicas.validar();
        Map<Integer,Long> esperado = new TreeMap<>();
        Map<String,Map<Integer,Long>> porAno = new TreeMap<>(), porCaracteristica = new TreeMap<>();
        percorrer((pos, c) -> {
            if (esperado.put(c.getId(), pos) != null) throw new IOException("ID ativo duplicado: " + c.getId());
            porAno.computeIfAbsent(Integer.toString(c.getAno()), k -> new TreeMap<>()).put(c.getId(), pos);
            for (String t : termos(c)) porCaracteristica.computeIfAbsent(t, k -> new TreeMap<>()).put(c.getId(), pos);
        });
        if (!esperado.equals(arvore.listar())) throw new IOException("Árvore difere do banco.");
        if (!esperado.equals(hash.listar())) throw new IOException("Hash difere do banco.");
        if (!porAno.equals(anos.listar())) throw new IOException("Lista de anos difere do banco.");
        if (!porCaracteristica.equals(caracteristicas.listar())) throw new IOException("Lista de características difere do banco.");
        return "AUDITORIA OK: " + esperado.size() + " registros ativos; todos os IDs, endereços e listas conferem.";
    }
    @Override public void close() throws IOException {
        if (fechado) return;
        try { fecharIndices(); }
        finally { fechado = true; trava.release(); dados.close(); }
    }
}
