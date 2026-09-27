# Revisão final do TP2

A revisão usou os requisitos de árvore parametrizada por ID, hashing estendido com buckets de 2% da base inicial, duas listas invertidas combináveis e CRUD com escolha de índice. Este documento registra a revisão anterior; a avaliação e as evidências da entrega atual estão em `AVALIACAO_ATUAL.md`.

## Correções realizadas

- O menu agora mostra “Escolha” antes de aguardar entrada. O encerramento por fim de entrada continua funcionando.
- Parâmetros incompletos, como `--ordem` sem número, recebem uma mensagem compreensível.
- O menu só informa sucesso na atualização se o método confirmar que o registro foi encontrado.
- A importação reconhece o cabeçalho pelos campos exatos. Um nome contendo “caracteristicas” não faz o primeiro carro desaparecer.
- CSV com BOM UTF-8, linhas vazias, separadores dentro de aspas e aspas escapadas é aceito. Aspas malformadas são rejeitadas antes de substituir a base.
- O temporário de importação tem nome exclusivo: um CSV chamado `carga.tmp` não é mais truncado ou apagado. Temporários de cargas inválidas são removidos.
- Banco zerado com configuração ou índices existentes gera erro de truncamento e preserva os índices, em vez de ser convertido silenciosamente em uma base nova.
- Os scripts de teste não pulam silenciosamente uma classe de teste ausente.

## Conferência do enunciado

| Item | Verificação |
|---|---|
| Árvore B+ e ordem parametrizada | Páginas persistentes, splits, empréstimos, fusões, raiz e folhas encadeadas; ordem padrão 16 |
| Chave ID e posição no banco | `int` e `long` nos índices; posição aponta à lápide |
| Hash estendido | Função `id mod 2^p`, diretório, profundidades locais/globais e splits |
| Capacidade exigida | N inicial=100.000 e 2% resultam em X=2.000; N inicial persistido |
| Duas listas | `anos.lista` e `caracteristicas.lista`; inclusão, alteração e exclusão de postings |
| Pesquisa com ambas | Interseção por ID/endereço; ano 2014 E gas confere com referência sequencial |
| CRUD indexado | Seleção B+, Hash ou Lista; todos os índices acompanham as escritas |
| Atualização de tamanho diferente | Mantém ID, reloca registro e atualiza os endereços |
| Persistência e robustez | Reabertura, auditoria, diário de undo e recuperação de carga |
| Apresentação | Vídeo TP2 separado, 6min37,91s, explicações e transcrições reais de execução |

## Testes executados após as correções

- Árvore B+: 57.762 verificações aprovadas.
- Hash estendido: 8.804 verificações aprovadas na suíte padrão.
- Listas: testes funcionais, 16.000 operações aleatórias, reabertura, compactação e corrupção aprovados.
- Integração TP2: 686 verificações aprovadas.
- Recuperação: 28 verificações aprovadas.
- Regressões desta revisão: 35 verificações aprovadas, incluindo uma execução real do menu em subprocesso.
- Base completa: 408 verificações aprovadas, incluindo auditoria de todos os registros ativos. Carga e índices: 24,452 s; suíte da base: 46,179 s nesta execução.
- Os testes existentes do TP1 também passaram após a melhoria no leitor CSV compartilhado.

O teste da base completa e a conferência do pacote constam nos arquivos de resultado anexos. As evidências novas são `RESULTADO_TESTES_REVISAO.txt`, `RESULTADO_BASE_100K_REVISAO.txt`, `RESULTADO_REVISAO_TP2.txt` e `RESULTADO_PACOTE.txt`.

## Vídeos e limites da conferência

O vídeo original `video/video.mp4` do TP1 permanece byte a byte igual à versão do repositório (objeto Git `c65c9ee6e3c8cb9cff574efb67bf68f047a4cd90`). O vídeo do TP2 está separado em `video/tp2/video_tp2.mp4`. Ele usa narração sintetizada e apresenta transcrições reais, conforme informado no próprio vídeo. Os logs originais foram mantidos como evidência da gravação; a revisão tem logs separados.

A validação de execução foi feita no macOS com JDK 17, compilando para Java 11. Os scripts Windows foram revisados, mas não executados em Windows nesta máquina. Os testes verificam os cenários descritos; não constituem garantia de ausência de todo erro possível. Não foi realizado envio ao ambiente da disciplina.
