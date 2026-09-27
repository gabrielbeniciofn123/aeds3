# Roteiro de vídeo — TP2 Carros

**Duração planejada: 9 minutos e 30 segundos.** O limite do enunciado é 10 minutos. As falas abaixo são sugestões para adaptar à própria maneira de explicar, sem decorar um texto ou relatar experiências que não ocorreram.

O material de demonstração fornecido pode usar narração sintetizada identificada. Este roteiro permite regravar a apresentação com voz própria. Identifique os integrantes que realmente apresentam o trabalho e use apenas resultados que apareçam na execução.

## Antes de gravar

1. Abra o projeto e deixe o terminal com fonte legível. Compile com `./compilar.sh` ou `compilar.bat`.
2. Execute os testes e a demonstração antes de iniciar a gravação. Verifique o que cada saída mostra e guarde os resultados reais.
3. Deixe acessíveis `src/MainTP2.java`, `src/dao/ArquivoIndexado.java` e as três classes de `src/index/`.
4. Use a demonstração isolada para não excluir carros da base que pretende conservar. Se quiser fazer um ensaio manual separado, execute `java -cp out MainTP2 --pasta data/ensaio-tp2 --ordem 4 --percentual 2` e importe o CSV pelo menu antes da gravação.
5. Tenha anotados os IDs impressos durante o ensaio. Eles dependem da base usada; não suponha que todo exemplo terá o mesmo ID.

Os comandos de apoio são:

```sh
./compilar.sh
./testar.sh
java -cp out TesteTP2 --base-100k
java -cp out DemonstracaoTP2
./executar.sh
```

No Windows, use os scripts `.bat` equivalentes. As esperas longas de carga ou testes podem ser cortadas na edição; preserve a identificação do comando e sua saída para ficar claro o que foi executado.

## 0:00–0:35 — Apresentação e objetivo

**Na tela:** título do projeto e nomes dos integrantes.

**Sugestão de fala:**

> Este é o TP2 de AEDS III, com a base de carros do TP1. Agora o cadastro usa quatro índices: uma árvore B+, um hash estendido e duas listas invertidas. Vou explicar as escolhas, mostrar o CRUD pelos três métodos e conferir se os arquivos continuam de acordo depois das alterações.

Se houver voz sintetizada, informe de forma simples: “Este vídeo demonstrativo utiliza narração sintetizada; as telas mostram execuções do programa.”

## 0:35–1:10 — Base de dados e arquivos

**Na tela:** pasta `data/tp2`, formato de `dados.db` e método de leitura direta em `ArquivoIndexado`.

**Sugestão de fala:**

> O registro mantém o formato do TP1: lápide, tamanho e dados do carro. O índice guarda o ID junto com a posição da lápide. Com essa posição, o programa usa acesso direto ao arquivo. O hash tem um arquivo de diretório e outro de buckets. As listas de anos e características também ficam em arquivos separados.

Mostre rapidamente que `long` armazena o endereço e que `lerPosicao` confere a lápide e o ID. Não é preciso ler a classe inteira.

## 1:10–2:10 — Árvore B+

**Na tela:** comentário inicial de `ArvoreBMais`, métodos `buscar`, `inserir` e `remover`, e resumo do índice.

**Sugestão de fala:**

> A escolha foi a B+ porque ela reúne várias chaves por página e mantém os endereços dos carros nas folhas. Os nós internos orientam a descida e as folhas ficam encadeadas em ordem. A ordem é configurável: com ordem 16, uma página interna tem até 16 filhos e 15 chaves. Ao inserir, uma página cheia pode ser dividida. Ao excluir, a árvore tenta um empréstimo de um irmão ou faz uma fusão. As páginas liberadas podem ser reutilizadas.

Mostre `--ordem 4` como exemplo de configuração que produz divisões mais cedo em uma base pequena. Diferencie essa configuração de demonstração do padrão 16.

## 2:10–3:10 — Hash estendido

**Na tela:** cálculo de capacidade em `ArquivoIndexado`, função `hash` e método `dividir` de `HashEstendido`.

**Sugestão de fala:**

> O hash usa o ID, assim como a árvore. A função é ID módulo dois elevado à profundidade global. Quando um bucket enche, ele é dividido usando mais um bit; se a profundidade local era igual à global, o diretório dobra. A capacidade depende da carga inicial. Com cem mil carros e dois por cento, cada bucket aceita dois mil pares. Depois, inclusões e exclusões não mudam essa capacidade.

> Dois por cento é a regra do enunciado desta entrega. Para bases pequenas, arredondamos a capacidade para cima e usamos no mínimo um par por bucket. O teste oficial usa cem mil carros, para os quais a capacidade é exatamente dois mil.

Mostre o resumo real do hash. A quantidade de buckets e a profundidade exibidas dependem da execução; diga os números que estiverem na tela.

## 3:10–4:00 — Duas listas invertidas

**Na tela:** `anos.lista`, `caracteristicas.lista` e métodos `termos` e `candidatos` do coordenador.

**Sugestão de fala:**

> Uma lista agrupa carros por ano. A outra agrupa cada característica completa, como gas ou oito cylinders, escrito na base como `8 cylinders`. Cada termo aponta para IDs e posições. A busca normaliza acentos, letras maiúsculas e espaços. Ela procura a característica inteira; não faz tradução nem busca por trecho.

> Quando preencho ano e característica, o sistema faz uma interseção: entram apenas os carros presentes nas duas listas. As listas são persistidas em arquivos de eventos, e seus dicionários e postings são carregados em memória.

## 4:00–6:50 — CRUD pelos três métodos

**Na tela:** execução de `java -cp out DemonstracaoTP2` e, se necessário, menu de `MainTP2` para destacar a escolha do índice.

A demonstração automatizada evita gastar o vídeo inteiro digitando campos. Percorra os blocos de árvore, hash e lista, sempre deixando visível o método usado e o resultado da operação.

| Etapa | O que mostrar | O que explicar |
|---|---|---|
| Criar | Criação pelos métodos Árvore, Hash e Lista. | O ID vem do cabeçalho; o método escolhido confirma a inclusão. Os quatro índices são atualizados. |
| Consultar | Consulta dos IDs criados pelos três métodos. | O endereço vem do índice selecionado e leva ao registro no banco. |
| Atualizar mantendo tamanho | Alteração apenas do ano, com endereço antes e depois. | O inteiro continua ocupando quatro bytes, então o endereço pode ser mantido. A lista do ano precisa mudar mesmo assim. |
| Atualizar mudando tamanho | Aumento do nome, com mudança do endereço. | O registro vai para o fim; o antigo recebe lápide; todos os índices recebem a nova posição. |
| Excluir | Exclusão pela lista e consultas posteriores sem resultado nos três métodos. Os testes também exercitam exclusão por árvore e hash. | A lápide é marcada e as entradas do carro saem de todos os índices. |

**Falas que ajudam a ligar os passos:**

> Aqui a tela informa qual índice está sendo usado. Escolher a árvore para localizar um carro não significa atualizar apenas a árvore: o cadastro precisa manter todas as estruturas coerentes.

> Nesta atualização, o endereço não mudou. Mesmo assim, o carro deve sair do ano antigo e aparecer no ano novo.

> Agora o nome ficou maior e o endereço mudou. Se algum índice guardasse a posição antiga, ele apontaria para uma lápide. A comparação dos endereços e a auditoria ajudam a detectar esse problema.

> Para atualizar ou excluir usando lista invertida, primeiro informo o ano ou a característica que o carro possui naquele momento, e depois o ID entre os candidatos.

Se fizer a gravação manual, use nomes simples, como `Carro demonstracao`, características `gas|automatic`, ano `2014` e uma data válida. Mude somente o ano na primeira atualização e acrescente ` com nome maior` ao nome na segunda. Na exclusão, digite `EXCLUIR` quando o menu pedir.

## 6:50–7:40 — Pesquisa combinada e persistência

**Na tela:** opção 6 do menu ou trecho correspondente da demonstração.

Mostre estas três pesquisas:

1. Ano `2014`, característica vazia.
2. Ano vazio, característica `gas`.
3. Ano `2014`, característica `gas`.

**Sugestão de fala:**

> Primeiro consultei uma lista por vez. Agora usei os dois filtros. O resultado precisa satisfazer ano e característica ao mesmo tempo. O menu mostra até vinte carros, mas informa o total encontrado.

Mostre também o bloco de reabertura da demonstração, ou saia do menu, abra novamente a mesma pasta e consulte um registro conservado.

> Esta consulta foi feita depois de reabrir os arquivos. Isso mostra que as informações utilizadas pelo índice foram persistidas.

## 7:40–8:50 — Testes e resultados

**Na tela:** comando de teste, saída real e mensagem de auditoria. Mostre também a execução na base de 100.000 carros.

**Sugestão de fala:**

> Além do exemplo no menu, há testes específicos de cada estrutura e testes integrados. Os cenários conferem inserções, remoções, alterações de endereço, reabertura e arquivos inválidos. A auditoria lê os registros ativos e compara IDs, posições e termos com todos os índices. Essa varredura faz parte da verificação; a consulta comum por ID usa o índice escolhido.

Apresente somente os testes concluídos e os resultados efetivamente exibidos. Se citar tempo, diga que foi medido naquele computador. Não transforme um teste isolado em afirmação de que todo caso possível está coberto.

## 8:50–9:30 — Limites e encerramento

**Na tela:** resumo dos índices ou trecho dos documentos de decisões.

**Sugestão de fala:**

> O diretório do hash e os postings das listas ficam em memória. O hash tem profundidade máxima vinte e não faz fusão de buckets depois de excluir. As listas podem compactar seu histórico pela API. São limites assumidos nesta implementação. O ponto central demonstrado foi manter o mesmo cadastro acessível pelos três métodos, inclusive depois de alterar o tamanho de um registro, excluir e reabrir os arquivos.

Finalize antes de 10 minutos. Não é necessário repetir toda a explicação ou ler os arquivos de código linha por linha.
