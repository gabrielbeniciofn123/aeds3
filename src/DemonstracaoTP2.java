import dao.ArquivoIndexado;
import dao.ArquivoIndexado.Metodo;
import model.Carro;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Demonstração real e determinística; não altera data/tp2. */
public class DemonstracaoTP2 {
    public static void main(String[] args) throws Exception {
        Path raiz = Paths.get("build-test-tp2"); Files.createDirectories(raiz);
        Path pasta = Files.createTempDirectory(raiz, "demonstracao-");
        System.out.println("=== 1. CONFIGURAÇÃO ===");
        System.out.println("Base isolada; ordem 4; X=1 na base inicial vazia para tornar splits visíveis.");
        try (ArquivoIndexado a = new ArquivoIndexado(pasta, 4, 2)) {
            for (int i=1; i<=12; i++) {
                Carro c = new Carro(0, "", "Carro " + i, LocalDate.of(2026,9,14), Arrays.asList(i%2==0?"gas":"diesel", "manual"), 2020+i%3);
                Metodo metodo = Metodo.values()[(i-1)%3];
                int id = a.criar(c, metodo);
                if (i <= 3) System.out.println("CREATE via " + metodo + ": id=" + id + " | " + a.getUltimoAcesso());
            }
            System.out.println(a.resumo());
            System.out.println("=== 2. CONSULTA PELOS TRÊS ÍNDICES ===");
            for (Metodo m : Metodo.values()) {
                Carro c = a.ler(2, m, 2022, "gas");
                System.out.println(a.getUltimoAcesso()); System.out.println(c);
            }
            System.out.println("=== 3. LISTAS COMBINADAS ===");
            System.out.println("Busca: ano=2022 E característica=gas");
            for (Carro c : a.pesquisar(2022,"gas")) System.out.println(c);
            System.out.println(a.getUltimoAcesso());
            System.out.println("=== 4. UPDATE NO MESMO ESPAÇO ===");
            Carro c = a.ler(2, Metodo.HASH); long antes = a.endereco(2, Metodo.HASH,null,null);
            c.setAno(2025); a.atualizar(c, Metodo.HASH,null,null);
            System.out.println("HASH: id=2, ano 2022 -> 2025; endereço " + antes + " -> " + a.endereco(2,Metodo.HASH,null,null));
            System.out.println("Ano antigo encontra id=2? " + (a.ler(2,Metodo.LISTA,2022,null)!=null));
            System.out.println("=== 5. UPDATE COM RELOCAÇÃO ===");
            c.setNome("Carro 2 com uma descrição mais longa"); c.setCaracteristicas(Arrays.asList("elétrico","automático"));
            a.atualizar(c, Metodo.ARVORE,null,null);
            for (Metodo m : Metodo.values()) {
                a.ler(2,m,2025,"ELETRICO"); System.out.println(a.getUltimoAcesso());
            }
            System.out.println("Característica antiga gas encontra id=2? " + (a.ler(2,Metodo.LISTA,null,"gas")!=null));
            System.out.println("=== 6. EXCLUSÃO PELA LISTA ===");
            a.excluir(2,Metodo.LISTA,2025,"elétrico"); System.out.println(a.getUltimoAcesso());
            System.out.println("Árvore encontra id=2? " + (a.ler(2,Metodo.ARVORE)!=null));
            System.out.println("Hash encontra id=2? " + (a.ler(2,Metodo.HASH)!=null));
            System.out.println("Lista encontra id=2? " + (a.ler(2,Metodo.LISTA,2025,null)!=null));
            System.out.println("=== 7. AUDITORIA ==="); System.out.println(a.auditar());
        }
        System.out.println("=== 8. REABERTURA ===");
        try (ArquivoIndexado a = new ArquivoIndexado(pasta,4,2)) {
            System.out.println(a.auditar()); System.out.println("id=3 persistido: " + a.ler(3,Metodo.HASH));
        }
        System.out.println("DEMONSTRAÇÃO CONCLUÍDA SEM ERROS.");
    }
}
