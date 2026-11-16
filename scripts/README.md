# Teste integrado local

Execute no PowerShell normal, a partir da raiz do ranking:

    .\scripts\Test-Stack.ps1 -Postgres

O script usa as copias de develop em ../integration-sources, preparadas para esta
validacao. revisions.json registra os commits. Em outro computador, prepare essas
copias ou informe -SourcesRoot com o caminho para os quatro repos.

Requisitos: Git/Maven Wrapper ja presentes nos repos, Node/npm e Docker Desktop.
Java 21 e reutilizado de JAVA_HOME ou baixado como ZIP portatil do Eclipse
Adoptium, com SHA-256 conferido. Nao altera a instalacao global de Java.

O script executa:

- Build e testes de auth, motoristas, manifesto e ranking.
- Instalacao, build e lint do frontend.
- Dois PostgreSQL 16 temporarios, apenas em 127.0.0.1:18532 e 18533.
- As migrations originais, inclusive o seed do motoristas.
- Quatro aplicacoes Java reais, nas portas 18080, 18081, 18082 e 18083.
- Login real, cadastro de operador, JWT, 401/403 e validacao de parametros.
- Upload CSV -> manifesto -> motoristas -> PostgreSQL -> ranking.
- Valores esperados, filtro mensal, nome/CPF/placa e posicao preservada.
- Importacao de veiculos e reimportacao sem duplicar viagens.
- Queda do motoristas-service e resposta 503 do ranking.

Os testes HTTP usam o ano 2090 para nao misturar os fixtures sinteticos com o
seed original de 2026. Nao usam os bancos ou arquivos .env ja existentes.
Processos, containers e volumes criados pelo script sao encerrados/removidos
ao terminar, inclusive em caso de falha. Docker Desktop pode continuar aberto.

Sem -Postgres, a integracao usa H2 em memoria; esse modo nao valida PostgreSQL.
Com -SkipBuild, reutiliza os builds e classpaths preparados por uma execucao
anterior bem-sucedida. -JavaHome permite informar um JDK 21 ja instalado.

Resultado e logs: .local-e2e/<data-hora>/resultado.json.
Os tokens e senhas de teste nao sao incluidos no relatorio.

Falhas de assercao nas suites dos servicos de origem sao registradas em
sourceTestFailures. Nesse caso o script empacota o codigo original sem repetir
os testes e continua o fluxo HTTP, mas o resultado geral continua FAIL.
Erros de compilacao ou execucao interrompem o roteiro. Os testes do ranking
continuam obrigatorios. Uma falha existente no manifesto aceita 31/02/2026,
quando seu teste espera rejeitar a linha; o roteiro nao altera essa regra.

Limites: o frontend e validado por build/lint, sem automacao de navegador.
A tela do ranking ainda nao esta conectada ao endpoint neste trabalho.
Validacao realizada em 16/11/2026: as 23 verificacoes HTTP passaram com os
quatro servicos reais e PostgreSQL 16. Auth: 40 testes aprovados; motoristas:
82 aprovados; ranking: 74 aprovados. Build e lint do frontend aprovados.
O resultado geral permanece FAIL pela falha de data existente no manifesto
(57 testes aprovados, 1 falhou e 1 foi ignorado).
