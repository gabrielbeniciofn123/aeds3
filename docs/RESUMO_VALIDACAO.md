# Validação executada — 14/09/2026

Registro histórico. A avaliação da entrega revisada está em `AVALIACAO_ATUAL.md`, com evidências nos arquivos terminados em `ATUAL.txt`.

Ambiente: macOS x86_64, Azul OpenJDK 17.0.18. Fontes compilados com `--release 11` e UTF-8, sem dependências Java externas.

| Conjunto | Resultado |
|---|---|
| Árvore B+ | 57.762 verificações aprovadas, ordens 3 a 31, splits, fusões, reabertura e corrupção |
| Hash estendido | 8.804 verificações aprovadas na suíte padrão; colisões, divisão, exclusão e persistência |
| Listas invertidas | Cenários funcionais, 16.000 operações aleatórias, compactação, cópias e corrupção aprovados |
| Integração TP2 | 686 verificações aprovadas; 600 operações aleatórias e recuperação de carga interrompida |
| Recuperação adicional | 28 verificações aprovadas; falha no commit, retomada na mesma sessão e configuração inválida |
| Regressão TP1 | Todos os testes obrigatórios existentes passaram |
| Base real | 408 verificações aprovadas e auditorias de 100.000 registros ativos |

A carga real e a construção dos quatro índices levaram 40,920 s nesta execução. A suíte completa da base real levou 65,225 s. Não são garantias de desempenho em outro computador.

Na base real, a árvore de ordem 16 ficou com altura 6. O hash ficou com profundidade global 6, 64 buckets e capacidade 2.000. A lista de anos teve 102 termos e 100.000 postings; a lista de características teve 47 termos e 535.292 postings. A pesquisa `2014 E gas` encontrou 5.693 carros e coincidiu com uma referência sequencial independente.

Após criar o ID 100001, atualizar seu tamanho e excluir o ID 50000, permaneceram 100.000 registros ativos. Por isso as auditorias finais continuam mostrando 100.000, sem esconder as alterações do teste.

Evidências completas: `RESULTADO_TESTES_TP2.txt`, `RESULTADO_INTEGRACAO.txt`, `RESULTADO_BASE_100K.txt` e `DEMONSTRACAO.txt`. Os testes não usam `data/tp2`.
