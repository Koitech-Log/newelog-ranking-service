# NEWELOG — Ranking Service

Serviço criado do template da Koitech-Log para as tasks US17-KOI146, US17-KOI147 e US17-KOI151.

## Execução

Java 17+ (Docker e CI usam 21). Spring Boot 3.5.16, alinhado ao serviço de motoristas.
Configure as variáveis no terminal; o Spring não carrega .env automaticamente.

PowerShell:

```powershell
$env:JWT_SECRET = '<mesmo segredo do auth-service, mínimo 32 bytes>'
$env:MOTORISTAS_SERVICE_URL = 'http://localhost:8080'
.\mvnw.cmd spring-boot:run
```

Porta padrão: 8083. Health público: GET /actuator/health.
Não tem banco próprio nem acessa diretamente o banco de outro serviço.

## API

GET /ranking?ano=2026&mes=8&tipo=motoristas&busca=nome
Alias: /api/ranking. Authorization: Bearer JWT do auth-service, somente GESTOR.

- ano: 1–9999; mes: 1–12; obrigatórios.
- tipo: motoristas (padrão) ou veiculos.
- busca opcional: nome ou CPF com/sem formatação; também aceita placa.
- Resposta: ano, mes, tipo, criterioOrdenacao e dados.
- Cada item: posicao, motoristaId, motorista, veiculo, quantidadeViagens,
  freteTotal, custoTotal, resultado, distanciaKm e destinos.
- Na visão de veículos, motoristaId e motorista são nulos; veiculo identifica a placa.
- resultado = soma dos fretes menos soma dos pedágios.
- Ordem inicial por resultado decrescente, viagens decrescentes, nome/placa e ID.
- A posição é definida ANTES da busca e não muda quando se filtra.
- Mês sem viagens e busca sem resultado retornam dados: [] (HTTP 200).
- Participantes sem viagens no mês não entram; viagens sem placa não geram veículo fictício.
- 400: parâmetros inválidos. 401/403: autenticação/permissão.
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

Não calcula pontuação ou valorização de rotas. criterioOrdenacao informa explicitamente
RESULTADO_FINANCEIRO; a integração da pontuação pertence à KOI-153.
O frontend e suas telas não fazem parte destas três tasks.

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
