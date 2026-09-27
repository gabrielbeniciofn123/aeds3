# TP2 AEDS III — Carros

**Grupo 15 — Gabriel Benicio Fonseca e Rhayner Martins**

Cadastro de carros em Java com árvore B+, hashing estendido e duas listas invertidas. O TP2 continua o TP1 e utiliza a mesma base de 100.000 anúncios.

## Para corrigir o trabalho

- [Decisões de implementação e formatos dos arquivos](docs/DECISOES_TP2.md)
- [Vídeo do TP2 — 8min10s, narrado pelo integrante](video/video_tp2.mp4)
- [Vídeo do TP1 — referência do trabalho anterior](video/video_tp1.mp4)
- [Resultado dos testes](docs/RESULTADO_VALIDACAO_ATUAL.txt)
- [Teste com os 100.000 carros](docs/RESULTADO_BASE_100K_ATUAL.txt)
- [Demonstração do CRUD e dos índices](docs/DEMONSTRACAO_ATUAL.txt)

## Executar

Requisito: **JDK 11 ou superior**. A aplicação não usa bibliotecas externas, Maven ou conexão com a internet.

Baixe o repositório em **Code → Download ZIP** e extraia, ou clone com Git. Abra um terminal na pasta do projeto.

**macOS / Linux:**

```bash
./executar.sh
```

**Windows — Prompt de Comando:**

```bat
executar.bat
```

Os scripts compilam o código antes de executar. No menu, escolha **1 — Importar CSV**, pressione ENTER para usar `data/base.csv` e confirme com `IMPORTAR`. A importação cria o arquivo de dados e os índices em `data/tp2/`.

A configuração padrão usa árvore de **ordem 16** e buckets com **2% da base inicial**: 2.000 pares para 100.000 carros. Para informar os parâmetros explicitamente:

```bash
./executar.sh --ordem 16 --percentual 2
```

No Windows, use os mesmos argumentos após `executar.bat`.

## Funcionalidades

- CRUD com escolha de árvore B+, hash estendido ou lista invertida para localizar o registro.
- Manutenção de todos os índices em cada escrita, independentemente do método escolhido para a busca.
- Árvore B+ com ordem parametrizada, divisões, empréstimos e fusões.
- Hash estendido com divisão de buckets, duplicação do diretório e função `id mod 2^p`.
- Listas separadas por ano e característica; dois filtros usam interseção.
- Atualização no mesmo endereço ou com realocação, exclusão por lápide e persistência após reabertura.
- Auditoria dos índices e recuperação de operações interrompidas.

Características são termos completos, separados por `|` no cadastro. Exemplo: `gas|automatic`. Na pesquisa, ano `2014` e característica `gas` retornam somente carros que atendem aos dois filtros.

## Testar e demonstrar

**macOS / Linux:**

```bash
./testar.sh
./testar.sh --base-100k
./demonstrar.sh
```

**Windows:**

```bat
testar.bat
testar.bat --base-100k
demonstrar.bat
```

Os testes usam bases isoladas. A suíte inclui as estruturas, integração, recuperação e regressão do TP1. Os logs publicados registram execuções anteriores; tempos podem variar conforme o computador. Os scripts Windows não foram executados no ambiente macOS de validação.

## Organização

| Pasta / arquivo | Conteúdo |
|---|---|
| `src/MainTP2.java` | Menu do TP2 |
| `src/model/` | Entidade Carro |
| `src/dao/` | Arquivos de dados e coordenação dos índices |
| `src/index/` | Árvore B+, hash estendido e lista invertida |
| `src/service/`, `src/util/` | Importação, ordenação e CSV |
| `src/Teste*.java` | Testes executáveis |
| `data/base.csv` | Base de 100.000 carros |
| `docs/` | Decisões técnicas e evidências de execução |
| `video/` | Vídeos finais do TP1 e TP2 |

O código do TP1 foi preservado para continuidade e testes de regressão. As limitações da implementação estão documentadas em [DECISOES_TP2.md](docs/DECISOES_TP2.md).

O vídeo do TP2 utiliza as 12 gravações do integrante. A demonstração do menu foi renderizada a partir de uma execução real com entradas automatizadas e tempo editado para acompanhar a narração. Sua transcrição está em [DEMONSTRACAO_MENU_ATUAL.txt](docs/DEMONSTRACAO_MENU_ATUAL.txt). Os [capítulos](video/CAPITULOS_TP2.md) e a [verificação do vídeo](video/VERIFICACAO_TP2.txt) acompanham os arquivos finais.
