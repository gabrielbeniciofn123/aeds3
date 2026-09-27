# TP2 AEDS III — Carros

**Grupo 15 — Gabriel Benicio Fonseca e Rhayner Martins**

Este projeto continua o TP1: a mesma entidade `Carro`, a mesma base de 100.000 anúncios e o mesmo formato binário, agora com CRUD apoiado por índices persistentes. O menu permite escolher árvore B+, hash estendido ou lista invertida em cada operação.

## Começar

Requisito: **JDK 11 ou superior**, sem Maven, dependências ou conexão à internet durante a execução. O código foi compilado com `--release 11` e executado com JDK 17. No ambiente local foi disponibilizado um JDK portátil em `.tools`; ele não faz parte do ZIP de entrega. Para instalar em outro computador, há [distribuições do JDK na Azul](https://www.azul.com/downloads/).

**macOS / Linux**, no terminal dentro desta pasta:

```bash
./executar.sh
```

**Windows**, no Prompt de Comando dentro desta pasta:

```bat
executar.bat
```

Na primeira execução, escolha **1**, pressione ENTER para usar `data/base.csv` e confirme digitando `IMPORTAR`. A carga constrói os quatro índices. Se já houver `data/dados.db` do TP1, o programa copia esse arquivo para a pasta do TP2 automaticamente na primeira abertura.

O CSV usa `;`, aceita campos entre aspas com `""` para uma aspa literal e exige um registro por linha. O cabeçalho é opcional; UTF-8 com BOM e linhas vazias são aceitos. Uma linha inválida cancela a carga e preserva a base anterior.

A base usada pelo TP2 fica em `data/tp2`. O CSV e o binário antigo permanecem disponíveis. A importação seguinte substitui apenas a base do TP2, após confirmação no menu, e guarda `antes-da-carga.db` como backup.

## Configuração conforme o enunciado

O enunciado desta entrega exige capacidade de **2% da base inicial** por bucket. Use o padrão `--percentual 2`. O texto recebido não informa data de entrega. Este pacote atende à **etapa 2 (indexação)**; compactação, casamento de padrões e criptografia pertencem às etapas seguintes.

```bash
./executar.sh --ordem 16 --percentual 2
./executar.sh --ordem 8 --percentual 2 --pasta data/tp2-alternativo
```

No Windows, use os mesmos argumentos depois de `executar.bat`.

- Ordem `m`: máximo de filhos; cada página tem até `m - 1` chaves. Padrão 16; menu aceita 3 a 1024.
- Capacidade `X = max(1, ceil(N_inicial × 2 / 100))`. Para 100.000 carros: **2.000 pares por bucket**. O mínimo 1 e o arredondamento para cima são escolhas para bases pequenas.
- `N_inicial` fica salvo e não muda a cada CRUD. Uma nova importação redefine a base inicial.
- Alterar ordem ou percentual ao abrir uma base existente reconstrói os índices com a nova configuração.

O percentual continua configurável para experimentos, mas valores diferentes de 2 não correspondem à regra desta entrega.

## O que está implementado

| Requisito | Implementação |
|---|---|
| Árvore escolhida e justificada | B+, com registros nas folhas e páginas internas de navegação |
| Ordem parametrizada | `--ordem`; valor persistido no arquivo |
| Pares id + posição | `int id` e `long posição` em árvore e hash |
| Hashing estendido | Diretório, profundidade global/local, duplicação e divisão de buckets |
| Função hash exigida | `id mod 2^p`, usando os bits menos significativos |
| Duas listas em arquivos separados | Ano e característica completa normalizada |
| Duas listas na mesma consulta | Interseção por ID e endereço; exemplo: 2014 **E** gas |
| CRUD com seleção de índice | Create, Read, Update e Delete oferecem B+, Hash e Lista |
| Consistência | Todos os índices acompanham criação, mudança de campos/endereço e exclusão |
| Persistência | Arquivos reabertos; índices ausentes ou desatualizados podem ser reconstruídos |
| Robustez | Diário de undo, backup de carga, trava de processo e auditoria estrutural |
| Demonstração e testes | Demonstração determinística, testes isolados e teste da base completa |

O índice escolhido localiza o registro que será consultado, alterado ou excluído. Em uma criação, como ainda não existe um registro anterior para buscar, ele confirma o endereço da entrada recém-inserida. **Todos os índices são mantidos em cada escrita**, independentemente da escolha.

A lista invertida exige ano e/ou característica antes do ID, pois suas chaves são esses campos. Ela usa o endereço armazenado na própria lista para acessar o carro, sem recorrer ao hash ou à árvore. Na criação via lista, a confirmação usa o ano do novo carro, inclusive quando não há características.

## Exemplos para explorar o menu

1. Importe a base pela opção 1.
2. Consulte o ID 1 pela opção 3, primeiro com B+ e depois com Hash. Os endereços devem ser iguais.
3. Use a opção 6 com ano `2014` e característica `gas`. Os filtros são combinados com **E**.
4. Crie um carro pela opção 2. O próximo ID da base original será `100001`.
5. Atualize apenas o ano: o endereço deve continuar igual. A lista de anos muda.
6. Aumente o nome e troque a característica: o registro vai para o final, preserva o ID e atualiza os endereços nos índices.
7. Exclua pela lista e consulte novamente pela árvore e pelo hash.
8. Rode a auditoria na opção 8; feche, abra novamente e repita a consulta.

Características são termos completos, separados por `|` no cadastro. `8 cylinders` é um único termo. Maiúsculas, acentos e espaços repetidos são normalizados. O nome do carro não é uma chave dessas listas. A pesquisa mostra os primeiros 20 resultados e o total.

## Testes e demonstração reproduzível

```bash
./testar.sh                 # estruturas, integração, recuperação e regressão TP1
./testar.sh --base-100k     # CSV real, todos os índices e auditoria completa
./demonstrar.sh            # exemplo guiado em base temporária própria
```

No Windows: `testar.bat`, `testar.bat --base-100k` e `demonstrar.bat`.

Os testes usam pastas temporárias dentro de `build-test*` e do diretório temporário do sistema, nunca a base de uso em `data/tp2`. As evidências da entrega revisada estão em `docs/RESULTADO_VALIDACAO_ATUAL.txt`, `docs/RESULTADO_BASE_100K_ATUAL.txt`, `docs/DEMONSTRACAO_ATUAL.txt` e `docs/DEMONSTRACAO_MENU_ATUAL.txt`.

A [avaliação atual por requisito](docs/AVALIACAO_ATUAL.md) registra as correções, os testes e os limites da conferência. A revisão verifica também CSV malformado, preservação da origem, banco truncado, recuperação de falhas em todos os tipos de escrita, acesso direto por índice e interação com o menu. Documentos e logs anteriores foram preservados como histórico; seus tempos e contagens não substituem as evidências atuais.

## Vídeo e explicações

- [Decisões e formatos dos arquivos](docs/DECISOES_TP2.md)
- [Roteiro de apresentação com duração inferior a 10 minutos](docs/ROTEIRO_VIDEO.md)
- [Guia de estudo e perguntas de defesa](docs/GUIA_ESTUDO.md)
- `video/video_tp2.mp4`: apresentação com as 12 gravações de voz fornecidas pelo integrante, sincronizadas às cenas, às saídas reais dos testes e à sessão real do menu; veja `video/tp2/LEIA-ME.md`.
- [Capítulos e sincronização das falas](video/tp2/SINCRONIZACAO.md). A duração final e as verificações do MP4 estão em `video/tp2/VERIFICACAO.txt`; os áudios originais acompanham a entrega em `video/tp2/audios_originais`.

O roteiro foi escrito para explicar o que o código realmente faz. Leia e pratique a demonstração para apresentar com suas palavras. O vídeo `video/video_tp1.mp4` pertence ao TP1; o vídeo do TP2 está ao lado, em `video/video_tp2.mp4`. O pacote de entrega final é `TP2-Carros-Entrega-Final.zip`, gerado por `python3 scripts/empacotar.py`.

## Organização

```text
src/MainTP2.java                 menu interativo
src/dao/ArquivoIndexado.java     coordenação de dados e índices
src/index/ArvoreBMais.java       páginas B+, splits e remoção com rebalanceamento
src/index/HashEstendido.java     diretório e buckets persistentes
src/index/ListaInvertida.java    eventos persistentes e dicionário de postings
src/DemonstracaoTP2.java         demonstração executável
src/Teste*.java                 testes sem bibliotecas externas
data/base.csv                   os 100.000 anúncios do TP1
docs/                           justificativas, roteiro e evidências
video/tp2/                      vídeo e materiais da apresentação
```

A origem da base, já registrada no TP1, é [Used Cars Dataset — Craigslist, de Austin Reese](https://www.kaggle.com/datasets/austinreese/craigslist-carstrucks-data). O recorte e o CSV recebidos foram reutilizados; não foi necessário baixar outra base. A documentação antiga está em `docs/README_TP1.md` e a entrada antiga continua disponível com `java -cp out Main`.

## Limites assumidos

A aplicação permite um processo por base. Os postings das listas e o diretório do hash ficam em memória; páginas B+ e buckets são lidos no disco. O hash limita `p` a 20 e reaproveita slots excluídos, mas não funde buckets. As listas mantêm histórico de eventos; a reconstrução elimina esse histórico. O diário trata interrupções de operações e a carga tem backup, mas o projeto não se propõe a substituir um SGBD com garantias para todo tipo de falha de hardware. A auditoria completa é uma operação explícita, não uma varredura escondida em cada consulta.
