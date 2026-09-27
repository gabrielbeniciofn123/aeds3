# Avaliação da entrega revisada — TP2 Carros

## Parecer

A implementação atende aos requisitos funcionais verificáveis da **etapa 2: manipulação de arquivo indexado**, nos cenários executados. Foram conferidos os algoritmos, a persistência, o CRUD pelos três métodos, a manutenção dos quatro índices e a entrega audiovisual. Os testes passaram, inclusive com a base completa de 100.000 carros.

O enunciado recebido detalha a etapa 2, embora um título diga “Descrição do TP1”. Esta entrega foi organizada pelo conteúdo dos requisitos: B+, hashing estendido, duas listas invertidas, CRUD indexado e vídeo. Compactação, casamento de padrões e criptografia são etapas futuras e não estão implementados neste TP2.

Nenhum conjunto finito de testes garante ausência de todo erro possível. A pontuação depende da avaliação do professor; este parecer apresenta evidências, sem prometer nota ou pontos extras.

## Conferência requisito por requisito

| Exigência | Implementação e evidência | Resultado |
|---|---|---|
| Escolher e justificar B, B+ ou B* | B+ com pares `(id, posição)` nas folhas, páginas internas de navegação e folhas encadeadas. Justificativa em `DECISOES_TP2.md` e no vídeo. | Atendido |
| Ordem parametrizada | `--ordem`, padrão 16; ordem significa máximo de filhos. Testes com ordens pares e ímpares, splits, empréstimos, fusões e reabertura. | Atendido |
| Índice de árvore em arquivo | `arvore.bmais`, páginas de tamanho fixo e endereços `long`. Busca desce pelas páginas do índice em disco. | Atendido |
| Árvore acompanha alterações | Inclusão, atualização de endereço e remoção incremental; auditoria compara todas as entradas com o banco. | Atendido |
| Hashing estendido por ID | Diretório, profundidades global/local, duplicação e divisão de buckets. O ID único e estável permite comparar o mesmo carro pelos dois índices. | Atendido |
| `h(k) = k mod 2^p` | Máscara dos bits menos significativos, equivalente ao módulo para os IDs positivos aceitos. | Atendido |
| Bucket com X = 2% da base inicial | Padrão 2%; 100.000 carros produzem X = 2.000. N inicial e capacidade são persistidos; o CRUD não recalcula X. | Atendido |
| Pares ID e posição no hash | `hash.diretorio` e `hash.buckets`; acesso ao banco pelo endereço encontrado. | Atendido |
| Dois arquivos de lista invertida | `anos.lista` e `caracteristicas.lista`; termos levam a IDs e posições. Campos escolhidos permitem filtros úteis e combináveis. | Atendido |
| Inserir, alterar e excluir nas listas | Mudanças de ano, característica, endereço e exclusão são refletidas nos dois arquivos. | Atendido |
| Pesquisa usando ambas as listas | Interseção por ID/endereço: ano **E** característica. Também permite cada filtro individual. | Atendido |
| CRUD com indicação do índice | Menu pede B+, Hash ou Lista em cada C/R/U/D. O índice selecionado localiza ou confirma a operação; todos são mantidos nas escritas. | Atendido |
| Implementação, decisões, demonstração e testes | Fontes Java, scripts, documentos, logs reproduzíveis e vídeo com as 12 gravações de voz fornecidas pelo integrante. | Atendido |
| Vídeo com duração máxima de 10 minutos | Duração final e conferência do limite registradas em `../video/tp2/VERIFICACAO.txt`; explicação das escolhas, resultados e sessão real do menu em 12 cenas. | Atendido |

Para bases pequenas, `max(1, ceil(N_inicial × 2 / 100))` explicita a escolha de arredondamento e permite uma base inicialmente vazia. Na base oficial a conta é exata: 2.000. Outros percentuais continuam disponíveis para experimentos, mas **a entrega deve ser executada com 2%**.

## Correções e melhorias desta avaliação

1. **Proteção contra arquivos coincidentes do hash:** caminhos distintos podiam apontar ao mesmo arquivo físico e misturar os formatos de diretório e buckets. O construtor agora usa `Files.isSameFile` para recusar esses aliases antes de gravar. Regressões verificam a recusa e a preservação dos bytes.
2. **Recuperação de todas as escritas:** testes provocam erro real na gravação dos metadados depois de create, update no lugar, update com realocação e delete, pelos três métodos. Conferem rollback byte a byte, índices, contador de IDs e reabertura.
3. **Comprovação de acesso indexado:** em base descartável, os testes tornam temporariamente ilegível um registro anterior ao alvo. As consultas continuam chegando ao alvo pelos três índices, sem varredura sequencial disfarçada. O arquivo é restaurado antes da auditoria.
4. **Documentação alinhada ao texto recebido:** foram retiradas alegações de prazos e de uma suposta outra versão obrigatória com 5%, que não constam do enunciado atual.
5. **Vídeo e demonstração:** a criação mostra explicitamente os três métodos. A apresentação inclui sessão real do menu capturada por PTY, entradas e saídas, além das justificativas e testes. A versão final incorpora as 12 gravações de voz fornecidas pelo integrante, com as cenas e as etapas do menu ajustadas às falas. Os áudios originais e os dados de sincronização acompanham o pacote.

Não foi necessário reescrever a B+, o coordenador do CRUD ou a lista invertida: a revisão e os testes adicionais não comprovaram defeitos nesses componentes.

## Testes efetivamente executados

Ambiente: macOS, Azul OpenJDK 17.0.18, fontes compilados para Java 11 com UTF-8. O aplicativo não usa dependências Java externas.

| Conjunto | Resultado da versão revisada |
|---|---|
| Árvore B+ | 57.762 verificações aprovadas |
| Hash estendido | 8.810 verificações aprovadas, incluindo arquivos coincidentes |
| Listas invertidas | Testes funcionais, 16.000 operações aleatórias, persistência, compactação e detecção de corrupção aprovados |
| Integração TP2 | 686 verificações aprovadas; inclui CRUD pelos três métodos e 600 operações contra modelo de referência |
| Recuperação | 178 verificações aprovadas |
| CSV, preservação, acesso indexado e menu | 44 verificações aprovadas |
| Regressão do TP1 | Todos os testes obrigatórios existentes aprovados |
| Base completa | 408 verificações aprovadas; auditoria dos 100.000 registros, consultas, interseção, CRUD e reabertura |

Na base completa: árvore de ordem 16 e altura 6; hash com profundidade global 6, 64 buckets e X = 2.000; 102 termos de ano e 47 termos de característica. A pesquisa `2014 E gas` retornou **5.693 carros**, em concordância com uma referência sequencial independente.

A carga com construção dos quatro índices levou **68,016 s**, e a suíte da base completa levou **115,039 s** nesta execução, com outras verificações também em andamento no computador. Esses tempos descrevem o ambiente da medição, não uma garantia de desempenho. O teste cria o ID 100001 e exclui o ID 50000; por isso termina novamente com 100.000 registros ativos.

Evidências atuais:

- `RESULTADO_VALIDACAO_ATUAL.txt`: suíte completa da versão revisada.
- `RESULTADO_BASE_100K_ATUAL.txt`: carga real e conferência de todos os índices.
- `DEMONSTRACAO_ATUAL.txt`: criação, consultas, mudanças de endereço, exclusão e reabertura.
- `DEMONSTRACAO_MENU_ATUAL.txt`: interação real com o menu, usada no vídeo.
- `../video/tp2/VERIFICACAO.txt`: duração, formatos e validação audiovisual.
- `../video/tp2/SINCRONIZACAO.md`: capítulos e correspondência entre as 12 falas e as cenas.

Os demais logs foram preservados como histórico e podem conter contagens e tempos anteriores.

## Critérios de avaliação e limites

**Correção e robustez:** há testes determinísticos e aleatórios, conferência com referência independente, validação estrutural, proteção de arquivos, diário de recuperação e auditoria integral. **Conformidade:** os requisitos observáveis estão mapeados acima. **Clareza:** dados, índices, coordenação, menu e testes estão separados em classes e pastas. **Critérios de escolha:** B+, ID, campos das listas, ordem e capacidade estão explicados em `DECISOES_TP2.md` e no vídeo.

A aplicação permite um processo por base. Diretório do hash e postings das listas ficam em memória. O hash limita profundidade a 20, reutiliza espaços internos de buckets excluídos e não funde buckets. As listas mantêm histórico até compactação/reconstrução. Esses limites não impedem os casos exigidos e foram documentados. O mecanismo de recuperação não oferece garantia contra todo tipo de falha de hardware.

Os scripts Windows foram revisados, mas não executados em Windows neste ambiente. O vídeo usa as gravações de voz fornecidas pelo integrante e um terminal renderizado a partir de uma captura real do programa, com o tempo editado para acompanhar a fala. Essa sequência foi reconstruída usando os eventos preservados em `menu_eventos.json`; os resultados de execução e os logs existentes foram mantidos. Os nomes existentes do grupo foram preservados. O envio ao ambiente da disciplina não foi realizado.

## Como conferir a entrega

Extraia `TP2-Carros-Entrega-Final.zip`. Com JDK 11 ou superior, execute `./testar.sh`, `./testar.sh --base-100k` e `./demonstrar.sh` (ou os equivalentes `.bat`). Para utilizar o sistema, execute `./executar.sh`, importe `data/base.csv` pela opção 1 e confirme com `IMPORTAR`. A configuração padrão já segue o enunciado.

Assista a `video/video_tp2.mp4` e use `ROTEIRO_VIDEO.md` e `GUIA_ESTUDO.md` para preparar a defesa. A implementação, as evidências e o vídeo estão no mesmo pacote.
