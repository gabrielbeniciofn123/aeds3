import dao.ArquivoIndexado;
import dao.ArquivoIndexado.Metodo;
import dao.ArquivoSequencial;
import model.Carro;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Testes reproduzíveis sem JUnit; sempre usam diretórios isolados em build-test-tp2. */
public class TesteTP2 {
    private static int verificacoes;
    private static void exigir(boolean condicao, String mensagem) {
        verificacoes++; if (!condicao) throw new AssertionError(mensagem);
    }
    private static Carro carro(String nome, int ano, String... termos) {
        return new Carro(0, "", nome, LocalDate.of(2026, 9, 14), Arrays.asList(termos), ano);
    }
    private static Carro copia(Carro c) throws IOException {
        Carro copia = new Carro(); copia.fromByteArray(c.toByteArray()); return copia;
    }
    public static void main(String[] args) throws Exception {
        long inicio = System.nanoTime();
        if (args.length > 0 && args[0].equals("--base-100k")) base100k();
        else {
            capacidade(); crud(); aleatorio(); compatibilidade(); recuperacao(); recuperacaoCarga();
        }
        System.out.printf(Locale.ROOT, "TESTES TP2 APROVADOS: %d verificações em %.3f s.%n", verificacoes, (System.nanoTime()-inicio)/1e9);
    }
    private static Path novaPasta(String prefixo) throws IOException {
        Path raiz = Paths.get("build-test-tp2"); Files.createDirectories(raiz);
        return Files.createTempDirectory(raiz, prefixo + "-");
    }
    private static void capacidade() {
        exigir(ArquivoIndexado.calcularCapacidade(100000, 2) == 2000, "2% da base real");
        exigir(ArquivoIndexado.calcularCapacidade(100000, 5) == 5000, "5% alternativo");
        exigir(ArquivoIndexado.calcularCapacidade(51, 2) == 2, "Arredondamento para cima");
        exigir(ArquivoIndexado.calcularCapacidade(0, 2) == 1, "Base vazia");
        System.out.println("OK: capacidade percentual, arredondamento e base vazia.");
    }
    private static void crud() throws Exception {
        Path pasta = novaPasta("crud");
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) {
            for (Metodo metodo : Metodo.values()) {
                Carro c = carro("Honda Civic", 2020, "Gás", "gas", "8 cylinders");
                int id = a.criar(c, metodo);
                long pos = a.endereco(id, Metodo.ARVORE, null, null);
                for (Metodo consulta : Metodo.values()) exigir(c.equals(a.ler(id, consulta, 2020, "GAS")), "Create visível em " + consulta);
                exigir(a.pesquisar(2020, "  GÁS  ").stream().filter(x -> x.getId() == id).count() == 1, "Normalização e deduplicação");
                c.setAno(2021);
                exigir(a.atualizar(c, metodo, 2020, "gas"), "Update no mesmo espaço");
                exigir(a.endereco(id, Metodo.HASH, null, null) == pos, "Mantém posição");
                exigir(a.ler(id, Metodo.LISTA, 2020, null) == null, "Remove ano antigo");
                c.setNome(c.getNome() + " Touring com descrição maior"); c.setCaracteristicas(Arrays.asList("diesel", "manual"));
                exigir(a.atualizar(c, metodo, 2021, "gas"), "Update relocado");
                long novo = a.endereco(id, Metodo.HASH, null, null);
                exigir(novo != pos, "Mudou endereço");
                for (Metodo consulta : Metodo.values()) exigir(c.equals(a.ler(id, consulta, 2021, "diesel")), "Novo offset correto em " + consulta);
                exigir(a.ler(id, Metodo.LISTA, null, "gas") == null, "Remove característica antiga");
                exigir(a.excluir(id, metodo, 2021, "diesel"), "Delete " + metodo);
                for (Metodo consulta : Metodo.values()) exigir(a.ler(id, consulta, 2021, "diesel") == null, "Delete refletido em " + consulta);
                exigir(!a.excluir(id, metodo, 2021, "diesel"), "Delete repetido");
                System.out.println("OK: CRUD via " + metodo + ", update fixo/relocado e limpeza das listas.");
            }
            Carro vazio = carro("Fiat Uno", 2010); int id = a.criar(vazio, Metodo.LISTA);
            exigir(a.ler(id, Metodo.LISTA, 2010, null).equals(vazio), "Lista de ano localiza carro sem características");
            exigir(a.pesquisar(1999, "inexistente").isEmpty(), "Interseção vazia");
            exigir(a.ler(999999, Metodo.ARVORE) == null, "ID ausente árvore");
            exigir(a.ler(999999, Metodo.HASH) == null, "ID ausente hash");
            System.out.println(a.auditar());
        }
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) {
            exigir(a.ler(4, Metodo.HASH) != null, "Persistência");
            System.out.println("OK: reabertura persistente. " + a.auditar());
        }
    }
    private static void aleatorio() throws Exception {
        Path pasta = novaPasta("aleatorio"); Random r = new Random(20260920L);
        Map<Integer,Carro> esperado = new TreeMap<>();
        for (int rodada = 0; rodada < 4; rodada++) {
            try (ArquivoIndexado a = new ArquivoIndexado(pasta, 5, 2)) {
                for (int i = 0; i < 150; i++) {
                    int operacao = esperado.isEmpty() ? 0 : r.nextInt(4);
                    Metodo m = Metodo.values()[r.nextInt(3)];
                    if (operacao == 0) {
                        Carro c = carro("Carro " + rodada + "/" + i, 2000 + r.nextInt(25), "gas", "cor " + r.nextInt(4));
                        int id = a.criar(c, m); esperado.put(id, copia(c));
                    } else {
                        List<Integer> ids = new ArrayList<>(esperado.keySet()); int id = ids.get(r.nextInt(ids.size()));
                        Carro antigo = esperado.get(id); int ano = antigo.getAno(); String termo = antigo.getCaracteristicas().get(0);
                        if (operacao == 1) {
                            Carro novo = copia(antigo); novo.setAno(ano + 1); novo.setNome(novo.getNome() + "+");
                            novo.setCaracteristicas(Arrays.asList("diesel", "branco"));
                            exigir(a.atualizar(novo, m, ano, termo), "Atualização aleatória"); esperado.put(id, copia(novo));
                        } else if (operacao == 2) {
                            exigir(a.excluir(id, m, ano, termo), "Exclusão aleatória"); esperado.remove(id);
                        } else exigir(antigo.equals(a.ler(id, m, ano, termo)), "Leitura aleatória");
                    }
                    if (i % 50 == 0) a.auditar();
                }
                for (Carro c : esperado.values()) for (Metodo m : Metodo.values()) exigir(c.equals(a.ler(c.getId(), m, c.getAno(), null)), "Oráculo após rodada");
                a.auditar();
            }
        }
        System.out.println("OK: 600 operações aleatórias contra modelo de referência e quatro reaberturas.");
    }
    private static void compatibilidade() throws Exception {
        Path pasta = novaPasta("tp1");
        ArquivoSequencial tp1 = new ArquivoSequencial(pasta.resolve("dados.db"));
        Carro c = carro("Carro legado", 2014, "gas"); int id = tp1.create(c);
        tp1.create(carro("Apagado", 2012, "diesel")); tp1.delete(2);
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 3, 2)) {
            exigir(a.ler(id, Metodo.ARVORE).equals(c), "Lê serialização TP1");
            exigir(a.ler(2, Metodo.HASH) == null, "Ignora lápides TP1"); a.auditar();
        }
        Files.delete(pasta.resolve("arvore.bmais"));
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 6, 5)) {
            exigir(a.ler(id, Metodo.HASH).equals(c), "Reconstrução índice ausente e nova ordem");
            exigir(a.resumo().contains("5.0%"), "Percentual alternativo"); a.auditar();
        }
        tp1.create(carro("Mudança externa", 2015, "gas"));
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 6, 5)) {
            exigir(a.ler(3, Metodo.ARVORE) != null, "Detecta banco alterado por TP1"); a.auditar();
        }
        System.out.println("OK: compatibilidade TP1, lápides, reconstrução e percentual 5%.");
    }
    private static void recuperacao() throws Exception {
        Path pasta = novaPasta("recuperacao");
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) { a.criar(carro("Original", 2020, "gas"), Metodo.ARVORE); }
        Path db = pasta.resolve("dados.db"); long tamanho = Files.size(db);
        // Simula processo interrompido após alterar cabeçalho e anexar dados incompletos.
        try (DataOutputStream log = new DataOutputStream(Files.newOutputStream(pasta.resolve("transacao.bin")))) {
            log.writeInt(0x54503255); log.writeLong(tamanho); log.writeInt(1); log.writeLong(-1); log.writeInt(0);
        }
        try (RandomAccessFile raf = new RandomAccessFile(db.toFile(), "rw")) {
            raf.writeInt(2); raf.seek(raf.length()); raf.write(new byte[]{0, 0, 0});
        }
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) {
            exigir(Files.size(db) == tamanho, "Undo remove cauda incompleta");
            exigir(a.ler(1, Metodo.HASH) != null, "Undo mantém registro original"); a.auditar();
            int id = a.criar(carro("Depois", 2021, "gas"), Metodo.HASH); exigir(id == 2, "Undo restaura contador");
            Path invalido = pasta.resolve("invalido.csv"); Files.writeString(invalido, "nome;caracteristicas;ano;data_registro\nerrado;gas;abc;2026-01-01\n");
            boolean falhou = false;
            try { a.importar(invalido); } catch (IOException e) { falhou = true; }
            exigir(falhou && a.ler(2, Metodo.HASH) != null, "CSV inválido preserva base");
        }
        System.out.println("OK: recuperação de transação interrompida e importação inválida sem perda.");
    }
    private static void base100k() throws Exception {
        Path pasta = novaPasta("base100k");
        int id;
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 16, 2)) {
            long inicio = System.nanoTime();
            exigir(a.importar(Paths.get("data/base.csv")) == 100000, "Carga total");
            System.out.printf(Locale.ROOT, "Carga real de 100.000 + quatro índices: %.3f s.%n", (System.nanoTime()-inicio)/1e9);
            exigir(a.resumo().contains("X=2000"), "Capacidade da base real");
            System.out.println(a.resumo()); System.out.println(a.auditar());
            Map<Integer,Carro> referencia = new TreeMap<>();
            for (Carro c : new ArquivoSequencial(pasta.resolve("dados.db")).listarAtivos()) referencia.put(c.getId(), c);
            Random r = new Random(15);
            for (int i=0; i<200; i++) {
                int chave = 1 + r.nextInt(100000);
                exigir(referencia.get(chave).equals(a.ler(chave, Metodo.ARVORE)), "Amostra B+");
                exigir(referencia.get(chave).equals(a.ler(chave, Metodo.HASH)), "Amostra hash");
            }
            Set<Integer> esperado = new TreeSet<>();
            for (Carro c : referencia.values()) if (c.getAno()==2014 && c.getCaracteristicas().contains("gas")) esperado.add(c.getId());
            Set<Integer> encontrado = new TreeSet<>(); for (Carro c : a.pesquisar(2014,"gas")) encontrado.add(c.getId());
            exigir(esperado.equals(encontrado), "Interseção real contra varredura independente");
            System.out.println("Pesquisa 2014 E gas: " + encontrado.size() + " registros, resultado igual à referência sequencial.");
            Carro novo = carro("Demonstração base completa", 2026, "elétrico"); id = a.criar(novo, Metodo.LISTA);
            exigir(id == 100001, "ID após carga"); novo.setNome(novo.getNome()+" com atualização maior");
            exigir(a.atualizar(novo, Metodo.HASH, null, null), "Update 100k");
            exigir(a.excluir(50000, Metodo.ARVORE, null, null), "Delete 100k");
            System.out.println(a.auditar());
        }
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 16, 2)) {
            exigir(a.ler(id, Metodo.LISTA, 2026, "eletrico") != null, "Persistência 100k");
            exigir(a.ler(50000, Metodo.HASH)==null, "Delete persistente 100k");
            System.out.println(a.auditar());
        }
        System.out.println("OK: carga real, 400 buscas, interseção, CRUD e reabertura com 100.000 ativos.");
    }
    private static void recuperacaoCarga() throws Exception {
        Path pasta = novaPasta("recuperacao-carga");
        Path csv = pasta.resolve("carga.csv");
        StringBuilder conteudo = new StringBuilder("nome;caracteristicas;ano;data_registro\n");
        for (int i = 1; i <= 51; i++) conteudo.append("Carga ").append(i).append(";gas;2020;2026-09-14\n");
        Files.writeString(csv, conteudo);
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) {
            exigir(a.importar(csv) == 51, "Carga pequena válida");
            exigir(a.criar(carro("Depois da carga", 2021, "diesel"), Metodo.HASH) == 52, "CRUD após carga");
        }
        Path db = pasta.resolve("dados.db");
        long tamanhoAnterior = Files.size(db);
        Files.copy(db, pasta.resolve("antes-da-carga.db"), StandardCopyOption.REPLACE_EXISTING);
        Properties marcador = new Properties();
        marcador.setProperty("formato", "TP2-CARGA-1");
        marcador.setProperty("tamanhoInicial", "51");
        marcador.setProperty("capacidadeBucket", "2");
        marcador.setProperty("percentual", "2.0");
        marcador.setProperty("tamanhoAnterior", Long.toString(tamanhoAnterior));
        marcador.setProperty("ultimoIdAnterior", "52");
        try (OutputStream out = Files.newOutputStream(pasta.resolve("carga.pendente"))) {
            marcador.store(out, "Simulação de carga interrompida");
        }
        // Simula uma interrupção após truncar o banco e alterar a configuração.
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(pasta.resolve("config.properties"))) { config.load(in); }
        config.setProperty("tamanhoInicial", "100000");
        config.setProperty("capacidadeBucket", "2000");
        try (OutputStream out = Files.newOutputStream(pasta.resolve("config.properties"))) { config.store(out, "Carga incompleta"); }
        Files.write(db, new byte[]{0});
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) {
            exigir(Files.size(db) == tamanhoAnterior, "Carga interrompida restaura tamanho do banco");
            exigir(a.ler(1, Metodo.ARVORE).getNome().equals("Carga 1"), "Restaura registros anteriores à carga");
            exigir(a.ler(52, Metodo.HASH).getNome().equals("Depois da carga"), "Preserva CRUD anterior à carga");
            exigir(a.resumo().contains("Base inicial: 51") && a.resumo().contains("X=2"), "Restaura capacidade calculada sobre a carga anterior");
            exigir(!Files.exists(pasta.resolve("carga.pendente")), "Finaliza recuperação da carga");
            exigir(a.criar(carro("Depois da recuperação", 2026, "gas"), Metodo.LISTA) == 53, "Restaura último ID anterior");
            System.out.println(a.auditar());
        }
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) {
            exigir(a.ler(53, Metodo.LISTA, 2026, "gas") != null, "Carga recuperada permanece correta após reabrir");
            a.auditar();
        }
        System.out.println("OK: carga interrompida com cabeçalho truncado, restauração do backup e capacidade inicial.");
    }
}
