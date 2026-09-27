package index;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Índice B+ em disco: somente as folhas guardam pares (id, posição no arquivo de
 * dados). Nos nós internos, cada separador é o menor id do filho à sua direita.
 * A ordem m significa no máximo m filhos e m - 1 chaves por página.
 *
 * Cabeçalho (48 bytes): magic, versão, ordem e tamanho da página (4 ints),
 * endereço da raiz, quantidade de páginas, primeira página livre e quantidade
 * de registros (4 longs). Endereços são offsets absolutos no arquivo de índice.
 *
 * Página (20m + 1 bytes): tipo (byte), quantidade (int), próxima (long),
 * m - 1 ids (int), m - 1 posições (long) e m filhos (long).
 * "Próxima" encadeia folhas, ou páginas livres, conforme o tipo da página.
 * Campos sem uso são preenchidos com zero (ids) ou -1 (endereços).
 *
 * As alterações atingem o caminho de busca e os irmãos envolvidos. A remoção
 * usa empréstimo e fusão; não recria a árvore. Páginas liberadas são reutilizadas.
 * O objeto deve ser usado por um único processo; não oferece transações próprias.
 */
public final class ArvoreBMais implements AutoCloseable {
    private static final int MAGIC = 0x42505432; // BPT2
    private static final int VERSAO = 1;
    private static final int CABECALHO = 48;
    private static final byte LIVRE = 0;
    private static final byte FOLHA = 1;
    private static final byte INTERNO = 2;
    private static final int LIMITE_ALTURA = 64;

    private final RandomAccessFile arquivo;
    private final int ordem;
    private final int maxChaves;
    private final int tamanhoPagina;
    private long raiz;
    private long paginas;
    private long primeiraLivre;
    private long registros;
    private boolean fechado;

    /** A ordem é persistida: reabrir com outra ordem é um erro, não uma conversão. */
    public ArvoreBMais(Path caminho, int ordem) throws IOException {
        if (ordem < 3 || ordem > 4096) {
            throw new IllegalArgumentException("A ordem da árvore deve estar entre 3 e 4096.");
        }
        this.ordem = ordem;
        this.maxChaves = ordem - 1;
        this.tamanhoPagina = 20 * ordem + 1;
        if (caminho.getParent() != null) Files.createDirectories(caminho.getParent());
        arquivo = new RandomAccessFile(caminho.toFile(), "rw");
        try {
            if (arquivo.length() == 0) {
                raiz = -1;
                paginas = 0;
                primeiraLivre = -1;
                registros = 0;
                arquivo.setLength(CABECALHO);
                No inicial = alocar(FOLHA);
                raiz = inicial.endereco;
                gravarCabecalho();
            } else {
                lerCabecalho();
                lerNo(raiz); // Falha cedo se a raiz estiver truncada ou inválida.
            }
        } catch (IOException | RuntimeException e) {
            try { arquivo.close(); } catch (IOException fechamento) { e.addSuppressed(fechamento); }
            throw e;
        }
    }

    /** Desce pelas páginas de índice e devolve o offset dos dados, ou -1. */
    public synchronized long buscar(int id) throws IOException {
        garantirAberto();
        No no = lerNo(raiz);
        int altura = 0;
        while (no.tipo == INTERNO) {
            if (++altura > LIMITE_ALTURA) throw corrompido("ciclo ou altura excessiva");
            no = lerNo(no.filhos[filhoPara(no, id)]);
        }
        int i = primeiraMaiorOuIgual(no, id);
        return i < no.quantidade && no.chaves[i] == id ? no.posicoes[i] : -1;
    }

    /** Insere um id novo ou substitui somente a posição de um id existente. */
    public synchronized void inserir(int id, long posicao) throws IOException {
        garantirAberto();
        if (posicao < 0) throw new IllegalArgumentException("A posição não pode ser negativa.");
        Insercao resultado = inserir(raiz, id, posicao, 0);
        if (resultado.divisao != null) {
            No novaRaiz = alocar(INTERNO);
            novaRaiz.quantidade = 1;
            novaRaiz.chaves[0] = resultado.divisao.separador;
            novaRaiz.filhos[0] = raiz;
            novaRaiz.filhos[1] = resultado.divisao.direita;
            escreverNo(novaRaiz);
            raiz = novaRaiz.endereco;
        }
        if (resultado.novo) registros++;
        gravarCabecalho();
    }

    /** Remove a entrada e corrige subocupações por empréstimo ou fusão. */
    public synchronized boolean remover(int id) throws IOException {
        garantirAberto();
        if (!remover(raiz, id, 0)) return false;
        No antigaRaiz = lerNo(raiz);
        if (antigaRaiz.tipo == INTERNO && antigaRaiz.quantidade == 0) {
            raiz = antigaRaiz.filhos[0];
            liberar(antigaRaiz);
        }
        registros--;
        gravarCabecalho();
        return true;
    }

    /** Percorre as folhas encadeadas em ordem crescente; não lê o arquivo de dados. */
    public synchronized Map<Integer, Long> listar() throws IOException {
        garantirAberto();
        Map<Integer, Long> pares = new LinkedHashMap<>();
        No folha = folhaMaisAEsquerda();
        Set<Long> visitadas = new HashSet<>();
        Integer anterior = null;
        while (true) {
            if (folha.tipo != FOLHA || !visitadas.add(folha.endereco)) {
                throw corrompido("encadeamento de folhas inválido");
            }
            for (int i = 0; i < folha.quantidade; i++) {
                if (anterior != null && folha.chaves[i] <= anterior) {
                    throw corrompido("folhas fora de ordem");
                }
                anterior = folha.chaves[i];
                pares.put(folha.chaves[i], folha.posicoes[i]);
            }
            if (folha.proxima == -1) break;
            folha = lerNo(folha.proxima);
        }
        if (pares.size() != registros) throw corrompido("contagem de registros divergente");
        return pares;
    }

    public synchronized String resumo() throws IOException {
        garantirAberto();
        int altura = 1;
        No no = lerNo(raiz);
        while (no.tipo == INTERNO) {
            if (++altura > LIMITE_ALTURA) throw corrompido("altura excessiva");
            no = lerNo(no.filhos[0]);
        }
        long livres = contarLivres(null);
        return "Árvore B+: ordem=" + ordem + ", registros=" + registros
                + ", altura=" + altura + ", páginas ativas=" + (paginas - livres)
                + ", páginas livres=" + livres + ", página=" + tamanhoPagina
                + " bytes, raiz no offset=" + raiz;
    }

    /**
     * Confere ocupação mínima, ordenação, separadores exatos, alturas, ciclo,
     * encadeamento das folhas e partição entre páginas alcançáveis e livres.
     * A validação é um diagnóstico completo, e não parte de cada busca normal.
     */
    public synchronized void validar() throws IOException {
        garantirAberto();
        validarTamanhoArquivo();
        Set<Long> alcancadas = new HashSet<>();
        List<No> folhas = new ArrayList<>();
        Faixa faixa = validarNo(raiz, true, 0, alcancadas, folhas);
        if (faixa.registros != registros) throw corrompido("total de registros incorreto");
        for (int i = 0; i < folhas.size(); i++) {
            long esperada = i + 1 < folhas.size() ? folhas.get(i + 1).endereco : -1;
            if (folhas.get(i).proxima != esperada) {
                throw corrompido("próxima folha incorreta no offset " + folhas.get(i).endereco);
            }
        }
        Set<Long> livres = new HashSet<>();
        contarLivres(livres);
        for (long endereco : livres) {
            if (alcancadas.contains(endereco)) throw corrompido("página ativa também está livre");
        }
        if ((long) alcancadas.size() + livres.size() != paginas) {
            throw corrompido("página órfã, sem referência na árvore nem na lista livre");
        }
    }

    /** Garante a gravação física antes de o coordenador confirmar uma alteração. */
    public synchronized void sincronizar() throws IOException {
        garantirAberto();
        arquivo.getFD().sync();
    }

    @Override
    public synchronized void close() throws IOException {
        if (!fechado) {
            fechado = true;
            try { arquivo.getFD().sync(); } finally { arquivo.close(); }
        }
    }

    private Insercao inserir(long endereco, int id, long posicao, int nivel) throws IOException {
        if (nivel > LIMITE_ALTURA) throw corrompido("altura excessiva na inserção");
        No no = lerNo(endereco);
        if (no.tipo == FOLHA) {
            int i = primeiraMaiorOuIgual(no, id);
            if (i < no.quantidade && no.chaves[i] == id) {
                no.posicoes[i] = posicao;
                escreverNo(no);
                return new Insercao(false, null);
            }
            for (int j = no.quantidade; j > i; j--) {
                no.chaves[j] = no.chaves[j - 1];
                no.posicoes[j] = no.posicoes[j - 1];
            }
            no.chaves[i] = id;
            no.posicoes[i] = posicao;
            no.quantidade++;
            if (no.quantidade <= maxChaves) {
                escreverNo(no);
                return new Insercao(true, null);
            }
            No direita = alocar(FOLHA);
            int ficamEsquerda = (no.quantidade + 1) / 2;
            direita.quantidade = no.quantidade - ficamEsquerda;
            for (int j = 0; j < direita.quantidade; j++) {
                direita.chaves[j] = no.chaves[ficamEsquerda + j];
                direita.posicoes[j] = no.posicoes[ficamEsquerda + j];
            }
            no.quantidade = ficamEsquerda;
            direita.proxima = no.proxima;
            no.proxima = direita.endereco;
            escreverNo(no);
            escreverNo(direita);
            // Na folha, o separador é COPIADO: o par continua na folha direita.
            return new Insercao(true, new Divisao(direita.chaves[0], direita.endereco));
        }

        int filho = filhoPara(no, id);
        Insercao resultado = inserir(no.filhos[filho], id, posicao, nivel + 1);
        if (resultado.divisao == null) return resultado;
        for (int j = no.quantidade; j > filho; j--) no.chaves[j] = no.chaves[j - 1];
        for (int j = no.quantidade + 1; j > filho + 1; j--) no.filhos[j] = no.filhos[j - 1];
        no.chaves[filho] = resultado.divisao.separador;
        no.filhos[filho + 1] = resultado.divisao.direita;
        no.quantidade++;
        if (no.quantidade <= maxChaves) {
            escreverNo(no);
            return new Insercao(resultado.novo, null);
        }

        int meio = no.quantidade / 2;
        int promovida = no.chaves[meio];
        No direita = alocar(INTERNO);
        direita.quantidade = no.quantidade - meio - 1;
        for (int j = 0; j < direita.quantidade; j++) direita.chaves[j] = no.chaves[meio + 1 + j];
        for (int j = 0; j <= direita.quantidade; j++) direita.filhos[j] = no.filhos[meio + 1 + j];
        no.quantidade = meio;
        escreverNo(no);
        escreverNo(direita);
        // No interno, a chave central sobe: ela não fica nos dois nós resultantes.
        return new Insercao(resultado.novo, new Divisao(promovida, direita.endereco));
    }

    private boolean remover(long endereco, int id, int nivel) throws IOException {
        if (nivel > LIMITE_ALTURA) throw corrompido("altura excessiva na remoção");
        No no = lerNo(endereco);
        if (no.tipo == FOLHA) {
            int i = primeiraMaiorOuIgual(no, id);
            if (i == no.quantidade || no.chaves[i] != id) return false;
            for (int j = i; j + 1 < no.quantidade; j++) {
                no.chaves[j] = no.chaves[j + 1];
                no.posicoes[j] = no.posicoes[j + 1];
            }
            no.quantidade--;
            escreverNo(no);
            return true;
        }
        int indiceFilho = filhoPara(no, id);
        if (!remover(no.filhos[indiceFilho], id, nivel + 1)) return false;
        No filho = lerNo(no.filhos[indiceFilho]);
        if (filho.quantidade < minimoChaves(filho)) equilibrar(no, indiceFilho, filho);
        // Ao apagar o menor id de um filho, seu separador também pode mudar.
        atualizarSeparadores(no);
        escreverNo(no);
        return true;
    }

    private void equilibrar(No pai, int indice, No filho) throws IOException {
        No esquerda = indice > 0 ? lerNo(pai.filhos[indice - 1]) : null;
        No direita = indice < pai.quantidade ? lerNo(pai.filhos[indice + 1]) : null;
        if (esquerda != null && esquerda.quantidade > minimoChaves(esquerda)) {
            emprestarDaEsquerda(esquerda, filho);
        } else if (direita != null && direita.quantidade > minimoChaves(direita)) {
            emprestarDaDireita(filho, direita);
        } else if (esquerda != null) {
            fundir(esquerda, filho);
            retirarFilho(pai, indice);
        } else if (direita != null) {
            fundir(filho, direita);
            retirarFilho(pai, indice + 1);
        }
        // Se não há irmãos, o pai é uma raiz vazia, reduzida em remover(id).
    }

    private void emprestarDaEsquerda(No esquerda, No filho) throws IOException {
        if (filho.tipo == FOLHA) {
            for (int j = filho.quantidade; j > 0; j--) {
                filho.chaves[j] = filho.chaves[j - 1];
                filho.posicoes[j] = filho.posicoes[j - 1];
            }
            filho.chaves[0] = esquerda.chaves[esquerda.quantidade - 1];
            filho.posicoes[0] = esquerda.posicoes[esquerda.quantidade - 1];
        } else {
            for (int j = filho.quantidade + 1; j > 0; j--) filho.filhos[j] = filho.filhos[j - 1];
            filho.filhos[0] = esquerda.filhos[esquerda.quantidade];
        }
        esquerda.quantidade--;
        filho.quantidade++;
        atualizarSeparadores(esquerda);
        atualizarSeparadores(filho);
        escreverNo(esquerda);
        escreverNo(filho);
    }

    private void emprestarDaDireita(No filho, No direita) throws IOException {
        if (filho.tipo == FOLHA) {
            filho.chaves[filho.quantidade] = direita.chaves[0];
            filho.posicoes[filho.quantidade] = direita.posicoes[0];
            for (int j = 0; j + 1 < direita.quantidade; j++) {
                direita.chaves[j] = direita.chaves[j + 1];
                direita.posicoes[j] = direita.posicoes[j + 1];
            }
        } else {
            filho.filhos[filho.quantidade + 1] = direita.filhos[0];
            for (int j = 0; j < direita.quantidade; j++) direita.filhos[j] = direita.filhos[j + 1];
        }
        filho.quantidade++;
        direita.quantidade--;
        atualizarSeparadores(filho);
        atualizarSeparadores(direita);
        escreverNo(filho);
        escreverNo(direita);
    }

    /** Junta dois irmãos adjacentes e devolve a página direita à lista livre. */
    private void fundir(No esquerda, No direita) throws IOException {
        if (esquerda.tipo != direita.tipo) throw corrompido("irmãos com tipos diferentes");
        if (esquerda.tipo == FOLHA) {
            for (int j = 0; j < direita.quantidade; j++) {
                esquerda.chaves[esquerda.quantidade + j] = direita.chaves[j];
                esquerda.posicoes[esquerda.quantidade + j] = direita.posicoes[j];
            }
            esquerda.quantidade += direita.quantidade;
            esquerda.proxima = direita.proxima;
        } else {
            int primeiroNovoFilho = esquerda.quantidade + 1;
            for (int j = 0; j <= direita.quantidade; j++) esquerda.filhos[primeiroNovoFilho + j] = direita.filhos[j];
            esquerda.quantidade += direita.quantidade + 1;
            atualizarSeparadores(esquerda);
        }
        escreverNo(esquerda);
        liberar(direita);
    }

    private void retirarFilho(No pai, int indice) {
        for (int j = indice; j < pai.quantidade; j++) pai.filhos[j] = pai.filhos[j + 1];
        pai.filhos[pai.quantidade] = -1;
        pai.quantidade--;
    }

    private int minimoChaves(No no) {
        // Folha: ceil((m - 1)/2). Interno: ceil(m/2) - 1.
        return no.tipo == FOLHA ? ordem / 2 : (ordem - 1) / 2;
    }

    private void atualizarSeparadores(No no) throws IOException {
        if (no.tipo == INTERNO) {
            for (int i = 0; i < no.quantidade; i++) no.chaves[i] = menorId(no.filhos[i + 1]);
        }
    }

    private int menorId(long endereco) throws IOException {
        No no = lerNo(endereco);
        int altura = 0;
        while (no.tipo == INTERNO) {
            if (++altura > LIMITE_ALTURA) throw corrompido("ciclo ao obter separador");
            no = lerNo(no.filhos[0]);
        }
        if (no.quantidade == 0) throw corrompido("subárvore vazia usada como separador");
        return no.chaves[0];
    }

    private No folhaMaisAEsquerda() throws IOException {
        No no = lerNo(raiz);
        int altura = 0;
        while (no.tipo == INTERNO) {
            if (++altura > LIMITE_ALTURA) throw corrompido("ciclo à esquerda da árvore");
            no = lerNo(no.filhos[0]);
        }
        return no;
    }

    private int primeiraMaiorOuIgual(No no, int id) {
        int inicio = 0, fim = no.quantidade;
        while (inicio < fim) {
            int meio = (inicio + fim) >>> 1;
            if (no.chaves[meio] < id) inicio = meio + 1;
            else fim = meio;
        }
        return inicio;
    }

    private int filhoPara(No no, int id) {
        int inicio = 0, fim = no.quantidade;
        while (inicio < fim) {
            int meio = (inicio + fim) >>> 1;
            if (id >= no.chaves[meio]) inicio = meio + 1;
            else fim = meio;
        }
        return inicio;
    }

    private Faixa validarNo(long endereco, boolean ehRaiz, int nivel,
                           Set<Long> alcancadas, List<No> folhas) throws IOException {
        if (nivel > LIMITE_ALTURA || !alcancadas.add(endereco)) {
            throw corrompido("ciclo, filho compartilhado ou altura inválida");
        }
        No no = lerNo(endereco);
        if (!ehRaiz && no.quantidade < minimoChaves(no)) {
            throw corrompido("página subocupada no offset " + endereco);
        }
        if (no.tipo == FOLHA) {
            folhas.add(no);
            return no.quantidade == 0
                    ? new Faixa(Long.MAX_VALUE, Long.MIN_VALUE, nivel, 0)
                    : new Faixa(no.chaves[0], no.chaves[no.quantidade - 1], nivel, no.quantidade);
        }
        if (ehRaiz && no.quantidade == 0) throw corrompido("raiz interna sem separadores");
        if (no.proxima != -1) throw corrompido("nó interno com ligação de folha");
        Faixa acumulada = validarNo(no.filhos[0], false, nivel + 1, alcancadas, folhas);
        for (int i = 0; i < no.quantidade; i++) {
            Faixa direita = validarNo(no.filhos[i + 1], false, nivel + 1, alcancadas, folhas);
            if (acumulada.altura != direita.altura) throw corrompido("folhas em níveis diferentes");
            if (acumulada.maximo >= direita.minimo || no.chaves[i] != direita.minimo) {
                throw corrompido("separador ou intervalo inválido no offset " + endereco);
            }
            acumulada = new Faixa(acumulada.minimo, direita.maximo, acumulada.altura,
                    acumulada.registros + direita.registros);
        }
        return acumulada;
    }

    private long contarLivres(Set<Long> resultado) throws IOException {
        Set<Long> visitadas = resultado != null ? resultado : new HashSet<>();
        long endereco = primeiraLivre;
        while (endereco != -1) {
            if (!visitadas.add(endereco)) throw corrompido("ciclo na lista de páginas livres");
            No no = lerPagina(endereco, true);
            if (no.tipo != LIVRE) throw corrompido("lista livre aponta para página ativa");
            endereco = no.proxima;
        }
        return visitadas.size();
    }

    private No alocar(byte tipo) throws IOException {
        long endereco;
        if (primeiraLivre != -1) {
            No livre = lerPagina(primeiraLivre, true);
            if (livre.tipo != LIVRE) throw corrompido("página a reutilizar está ativa");
            endereco = primeiraLivre;
            primeiraLivre = livre.proxima;
        } else {
            endereco = CABECALHO + paginas * tamanhoPagina;
            paginas++;
        }
        No no = new No(endereco, tipo);
        escreverNo(no);
        return no;
    }

    private void liberar(No no) throws IOException {
        No livre = new No(no.endereco, LIVRE);
        livre.proxima = primeiraLivre;
        escreverNo(livre);
        primeiraLivre = livre.endereco;
    }

    private No lerNo(long endereco) throws IOException {
        return lerPagina(endereco, false);
    }

    private No lerPagina(long endereco, boolean aceitarLivre) throws IOException {
        validarEndereco(endereco);
        arquivo.seek(endereco);
        byte[] bytes = new byte[tamanhoPagina];
        arquivo.readFully(bytes);
        ByteBuffer pagina = ByteBuffer.wrap(bytes);
        byte tipo = pagina.get();
        if (tipo != FOLHA && tipo != INTERNO && !(aceitarLivre && tipo == LIVRE)) {
            throw corrompido("tipo inválido no offset " + endereco);
        }
        No no = new No(endereco, tipo);
        no.quantidade = pagina.getInt();
        no.proxima = pagina.getLong();
        if (no.quantidade < 0 || no.quantidade > maxChaves || (tipo == LIVRE && no.quantidade != 0)) {
            throw corrompido("quantidade inválida no offset " + endereco);
        }
        if (no.proxima != -1) validarEndereco(no.proxima);
        for (int i = 0; i < maxChaves; i++) no.chaves[i] = pagina.getInt();
        for (int i = 0; i < maxChaves; i++) no.posicoes[i] = pagina.getLong();
        for (int i = 0; i < ordem; i++) no.filhos[i] = pagina.getLong();
        for (int i = 0; i < no.quantidade; i++) {
            if (i > 0 && no.chaves[i - 1] >= no.chaves[i]) throw corrompido("ids repetidos ou fora de ordem");
            if (tipo == FOLHA && no.posicoes[i] < 0) throw corrompido("posição de dados negativa");
        }
        if (tipo == INTERNO) {
            for (int i = 0; i <= no.quantidade; i++) validarEndereco(no.filhos[i]);
        }
        return no;
    }

    private void escreverNo(No no) throws IOException {
        if (no.quantidade < 0 || no.quantidade > maxChaves) {
            throw new IOException("Tentativa de gravar página B+ com ocupação inválida.");
        }
        // Serializa a página inteira antes do I/O: uma gravação por página.
        ByteBuffer pagina = ByteBuffer.allocate(tamanhoPagina);
        pagina.put(no.tipo);
        pagina.putInt(no.quantidade);
        pagina.putLong(no.proxima);
        for (int i = 0; i < maxChaves; i++) pagina.putInt(i < no.quantidade ? no.chaves[i] : 0);
        for (int i = 0; i < maxChaves; i++) {
            pagina.putLong(no.tipo == FOLHA && i < no.quantidade ? no.posicoes[i] : -1);
        }
        for (int i = 0; i < ordem; i++) {
            pagina.putLong(no.tipo == INTERNO && i <= no.quantidade ? no.filhos[i] : -1);
        }
        arquivo.seek(no.endereco);
        arquivo.write(pagina.array());
    }

    private void lerCabecalho() throws IOException {
        if (arquivo.length() < CABECALHO) throw corrompido("cabeçalho truncado");
        arquivo.seek(0);
        byte[] bytes = new byte[CABECALHO];
        arquivo.readFully(bytes);
        ByteBuffer cabecalho = ByteBuffer.wrap(bytes);
        if (cabecalho.getInt() != MAGIC || cabecalho.getInt() != VERSAO) {
            throw corrompido("assinatura ou versão desconhecida");
        }
        int ordemGravada = cabecalho.getInt();
        if (ordemGravada != ordem) {
            throw new IOException("A árvore foi criada com ordem " + ordemGravada
                    + "; foi solicitada ordem " + ordem + ". Reconstrua o índice para mudar a ordem.");
        }
        if (cabecalho.getInt() != tamanhoPagina) throw corrompido("tamanho de página incompatível");
        raiz = cabecalho.getLong();
        paginas = cabecalho.getLong();
        primeiraLivre = cabecalho.getLong();
        registros = cabecalho.getLong();
        if (paginas < 1 || registros < 0) throw corrompido("contadores negativos ou árvore sem páginas");
        validarTamanhoArquivo();
        validarEndereco(raiz);
        if (primeiraLivre != -1) validarEndereco(primeiraLivre);
    }

    private void gravarCabecalho() throws IOException {
        ByteBuffer cabecalho = ByteBuffer.allocate(CABECALHO);
        cabecalho.putInt(MAGIC);
        cabecalho.putInt(VERSAO);
        cabecalho.putInt(ordem);
        cabecalho.putInt(tamanhoPagina);
        cabecalho.putLong(raiz);
        cabecalho.putLong(paginas);
        cabecalho.putLong(primeiraLivre);
        cabecalho.putLong(registros);
        arquivo.seek(0);
        arquivo.write(cabecalho.array());
    }

    private void validarTamanhoArquivo() throws IOException {
        if (paginas > (Long.MAX_VALUE - CABECALHO) / tamanhoPagina
                || arquivo.length() != CABECALHO + paginas * tamanhoPagina) {
            throw corrompido("tamanho físico não corresponde ao total de páginas");
        }
    }

    private void validarEndereco(long endereco) throws IOException {
        if (endereco < CABECALHO || (endereco - CABECALHO) % tamanhoPagina != 0
                || (endereco - CABECALHO) / tamanhoPagina >= paginas) {
            throw corrompido("endereço de página inválido: " + endereco);
        }
    }

    private void garantirAberto() throws IOException {
        if (fechado) throw new IOException("A árvore B+ já está fechada.");
    }

    private IOException corrompido(String detalhe) {
        return new IOException("Índice B+ corrompido: " + detalhe + ".");
    }

    private final class No {
        final long endereco;
        final byte tipo;
        int quantidade;
        long proxima = -1;
        // Uma posição extra acomoda o overflow temporário antes da divisão.
        final int[] chaves = new int[ordem];
        final long[] posicoes = new long[ordem];
        final long[] filhos = new long[ordem + 1];

        No(long endereco, byte tipo) {
            this.endereco = endereco;
            this.tipo = tipo;
            java.util.Arrays.fill(posicoes, -1);
            java.util.Arrays.fill(filhos, -1);
        }
    }

    private static final class Divisao {
        final int separador;
        final long direita;
        Divisao(int separador, long direita) { this.separador = separador; this.direita = direita; }
    }

    private static final class Insercao {
        final boolean novo;
        final Divisao divisao;
        Insercao(boolean novo, Divisao divisao) { this.novo = novo; this.divisao = divisao; }
    }

    private static final class Faixa {
        final long minimo, maximo, registros;
        final int altura;
        Faixa(long minimo, long maximo, int altura, long registros) {
            this.minimo = minimo;
            this.maximo = maximo;
            this.altura = altura;
            this.registros = registros;
        }
    }
}
