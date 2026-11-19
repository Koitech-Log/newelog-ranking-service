# NEWELOG — Ranking Service

Serviço criado do template da Koitech-Log para as tasks US17-KOI146, US17-KOI147 e US17-KOI151.
Inclui a pontuação do ranking: valorização SP e limite de viagens (US18, KOI-157/158) e
cálculo e ordenação pela pontuação (US19, KOI-152/153).

## Execução

Java 17+ (Docker e CI usam 21). Spring Boot 3.5.16, alinhado ao serviço de motoristas.
Configure as variáveis no terminal; o Spring não carrega .env automaticamente.

PowerShell:

```powershell
$env:JWT_SECRET = '<mesmo segredo do auth-service, mínimo 32 bytes>'
$env:MOTORISTAS_SERVICE_URL = 'http://localhost:8080'
.\mvnw.cmd spring-boot:run
```

| Variável | Padrão | Finalidade |
|---|---|---|
| JWT_SECRET | — (obrigatória) | Mesmo segredo do auth-service, mínimo 32 bytes |
| PORT | 8083 | Porta HTTP |
| MOTORISTAS_SERVICE_URL | http://localhost:8080 | URL base do motoristas-service |
| FRONTEND_URL | http://localhost:5173 | Origem permitida pelo CORS |
| MOTORISTAS_CONNECT_TIMEOUT_MS / MOTORISTAS_READ_TIMEOUT_MS | 5000 / 30000 | Timeouts por chamada à fonte |
| RANKING_MAX_VIAGENS | 20 | Quantas viagens por mês contam na pontuação (mínimo 1) |
| RANKING_FATOR_SP | 1.20 | Fator da viagem com destino São Paulo (mínimo 1; valor provisório até o cliente definir) |

Valores fora da faixa impedem a aplicação de iniciar.

Porta padrão: 8083. Health público: GET /actuator/health.
Não tem banco próprio nem acessa diretamente o banco de outro serviço.

## API

GET /ranking?ano=2026&mes=8&tipo=motoristas&busca=nome
Alias: /api/ranking. Authorization: Bearer JWT do auth-service, somente GESTOR.

- ano: 1–9999; mes: 1–12; obrigatórios.
- tipo: motoristas (padrão) ou veiculos.
- busca opcional: nome ou CPF com/sem formatação; também aceita placa.
- Resposta: ano, mes, tipo, criterioOrdenacao, totalParticipantes e dados.
- totalParticipantes: quantidade de participantes do mês/tipo ANTES da busca (0 em mês sem viagens).
- criterioOrdenacao: PONTUACAO.
- Cada item: posicao, motoristaId, motorista, veiculo, quantidadeViagens,
  freteTotal, custoTotal, resultado, distanciaKm, destinos e pontuacao.
- Na visão de veículos, motoristaId e motorista são nulos; veiculo identifica a placa.
- resultado = soma dos fretes menos soma dos pedágios (não muda com a pontuação).
- pontuacao: ver "Pontuação" abaixo.
- Ordem por pontuação decrescente, viagens decrescentes, nome/placa (sem diferenciar
  acento nem maiúscula) e ID. Empate não divide posição: as posições são únicas, 1..N.
- A posição é definida ANTES da busca e não muda quando se filtra.
- Mês sem viagens e busca sem resultado retornam dados: [] (HTTP 200).
- Participantes sem viagens no mês não entram; viagens sem placa não geram veículo fictício.
- 400: parâmetros inválidos. 401/403: autenticação/permissão.
- Erros 400, 401, 403, 502 e 503 têm corpo JSON { "timestamp", "mensagem" } em português.
- 502: erro ou resposta inválida do serviço de motoristas. 503: falha de conexão/timeout.
- Uma falha em qualquer detalhe interrompe a resposta inteira; não é devolvido ranking parcial.

## Origem e limites dos dados

1. Consulta GET /api/dashboard/rentabilidade-media?ano=...&mes=... no motoristas-service.
2. Busca GET /api/motoristas/{id} somente para os participantes encontrados.
3. Filtra as viagens do mês, agrega os valores e aplica a busca depois de definir as posições.

São 1 + N chamadas para N participantes, sem cache; os timeouts são por chamada.
Mudanças na fonte durante as chamadas não têm garantia de snapshot transacional.
O JWT do solicitante é repassado para a fonte, sem credenciais próprias.

A base atual não possui quilometragem: distanciaKm é null, nunca um zero inventado.
Os destinos são os valores registrados na fonte, que pode usar "Não informado".
A placa vem do cadastro ATUAL do motorista; a API não garante histórico por veículo.
Não altera dados nem regras de importação/duplicidade de nenhum serviço.

## Pontuação

Calculada por participante (motorista ou placa) com as viagens do mês:

1. Valor de cada viagem = frete − pedágio.
2. Valorização SP (KOI-158): se o destino da viagem for exatamente "São Paulo" (sem
   diferenciar acento, maiúscula nem espaços nas pontas), o valor é multiplicado por
   RANKING_FATOR_SP e arredondado a 2 casas. Listas de cidades ("São Paulo, Cotia"),
   destinos resumidos e "Não informado" não recebem o fator.
3. Limite de viagens (KOI-157): contam só as RANKING_MAX_VIAGENS viagens de maior valor
   já valorizado; as demais são ignoradas na pontuação.
4. pontuacao = soma dos valores que contam (0.00 sem viagens).

A pontuação não altera freteTotal, custoTotal, resultado nem quantidadeViagens, que continuam
com todas as viagens do mês. O destino vem de cada viagem gravada pelo motoristas-service a
partir da coluna Destino do CSV do manifesto.

## Verificação e fluxo

```powershell
.\mvnw.cmd clean verify
```

Testes de integração carregam a aplicação Spring com MockMvc e MockRestServiceServer,
com JWT assinado e validação real. Exercitam controller, segurança, cliente REST,
desserialização e agregação, sem abrir portas nem depender de banco ou ambiente externo.
Não substituem uma verificação com os dois serviços em execução.

Branches: main → develop → feature-ranking. PR de feature-ranking para develop.
Commits devem seguir o Jira, por exemplo:
US17-KOI146 feat: consultar dados mensais da frota
US17-KOI147 feat: disponibilizar endpoint de ranking
US17-KOI151 test: validar integração do ranking
