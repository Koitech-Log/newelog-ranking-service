package br.com.newelog.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import br.com.newelog.RankingServiceApplication;

@SpringBootTest(classes = RankingServiceApplication.class, properties = {
    "app.jwt.secret=segredo-de-teste-ranking-com-mais-de-32-bytes"
})
@AutoConfigureMockMvc
@Import(RankingIntegrationTest.FonteSimulada.class)
class RankingIntegrationTest {
    static final String SECRET = "segredo-de-teste-ranking-com-mais-de-32-bytes";
    static final Map<String, String> bodies = new ConcurrentHashMap<>();
    static final List<String> chamadas = new CopyOnWriteArrayList<>();
    static final List<String> tokens = new CopyOnWriteArrayList<>();
    static volatile int falha;
    static volatile String falharEm;
    static volatile long atraso;

    record Fonte(RestClient client, MockRestServiceServer server) {}
    @TestConfiguration
    static class FonteSimulada {
        @Bean Fonte fonte() {
            var builder = RestClient.builder().baseUrl("http://motoristas.test");
            var server = MockRestServiceServer.bindTo(builder).build();
            return new Fonte(builder.build(), server);
        }
        @Bean @Primary RestClient fonteClient(Fonte fonte) { return fonte.client(); }
    }

    @Autowired Fonte fonte;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void preparar() {
        chamadas.clear(); tokens.clear(); bodies.clear(); falha = 0; falharEm = ""; atraso = 0;
        fonte.server().reset();
        fonte.server().expect(ExpectedCount.manyTimes(), anything())
            .andExpect(method(org.springframework.http.HttpMethod.GET)).andRespond(request -> {
            chamadas.add(request.getURI().toString());
            tokens.add(request.getHeaders().getFirst("Authorization"));
            if (atraso > 0) throw new SocketTimeoutException("timeout simulado");
            String path = request.getURI().getPath();
            int status = falha > 0 && path.equals(falharEm) ? falha : 200;
            byte[] bytes = (status == 200 ? bodies.getOrDefault(path, "{}") : "{}")
                .getBytes(StandardCharsets.UTF_8);
            var response = new MockClientHttpResponse(bytes, HttpStatus.valueOf(status));
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        });
        bodies.put("/api/dashboard/rentabilidade-media",
            "{\"ano\":2026,\"mes\":8,\"porMotorista\":[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4}],\"geral\":{}}");
        bodies.put("/api/motoristas/1", motorista(1, "Ana Sá", "111.111.111-11", "ABC-1D23",
            viagem("2026-08-01", "São Paulo", "1000.00", "100.00") + "," +
            viagem("2026-07-31", "Outro mês", "999999.00", "0.00") + "," +
            viagem("2026-09-01", "Outro mês", "999999.00", "0.00")));
        bodies.put("/api/motoristas/2", motorista(2, "Bruno", "222.222.222-22", "DEF2E34",
            viagem("2026-08-10", "Campinas", "300.00", "100.00") + "," +
            viagem("2026-08-31", "Campinas", "200.00", "0.00")));
        bodies.put("/api/motoristas/3", motorista(3, "Clara", "333.333.333-33", null,
            viagem("2026-08-20", "Não informado", "0.00", "10.00")));
        bodies.put("/api/motoristas/4", motorista(4, "Davi", "444.444.444-44", "DEF-2E34",
            viagem("2026-08-15", "Santos", "250.00", "50.00") + "," +
            viagem("2026-08-16", "Santos", "200.00", "0.00")));
    }


    @Test
    void consultaMesCalculaValoresExatosERepassaToken() throws Exception {
        String jwt = token("GESTOR", SECRET, "newelog-auth-service", 300);
        var response = get("/ranking?ano=2026&mes=8", jwt);
        assertThat(response.statusCode()).isEqualTo(200);
        var json = mapper.readTree(response.body());
        var items = json.path("dados");
        assertThat(items.size()).isEqualTo(4);
        assertThat(json.path("criterioOrdenacao").asText()).isEqualTo("RESULTADO_FINANCEIRO");
        assertThat(items.get(0).path("motorista").asText()).isEqualTo("Ana Sá");
        assertThat(items.get(0).path("resultado").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(items.get(0).path("quantidadeViagens").asInt()).isEqualTo(1);
        assertThat(items.get(0).path("veiculo").asText()).isEqualTo("ABC1D23");
        assertThat(items.get(0).path("distanciaKm").isNull()).isTrue();
        assertThat(items.get(0).path("destinos").toString()).isEqualTo("[\"São Paulo\"]");
        assertThat(items.get(3).path("resultado").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(chamadas).hasSize(5);
        assertThat(chamadas.get(0)).contains("ano=2026", "mes=8");
        assertThat(tokens).containsOnly("Bearer " + jwt);
    }

    @Test
    void empateTemDesempateEstavel() throws Exception {
        var items = dados("/ranking?ano=2026&mes=8");
        assertThat(items.get(1).path("motorista").asText()).isEqualTo("Bruno");
        assertThat(items.get(2).path("motorista").asText()).isEqualTo("Davi");
        assertThat(items.get(1).path("posicao").asInt()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bruno", "22222222222", "222.222.222-22", "br%C3%BAno"})
    void buscaPreservaPosicaoReal(String busca) throws Exception {
        var items = dados("/ranking?ano=2026&mes=8&busca=" + busca);
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("posicao").asInt()).isEqualTo(2);
        assertThat(items.get(0).path("motorista").asText()).isEqualTo("Bruno");
        assertThat(chamadas).allMatch(c -> !c.contains("busca"));
    }

    @Test
    void buscaNomeIgnoraAcentos() throws Exception {
        assertThat(dados("/ranking?ano=2026&mes=8&busca=ana%20sa").size()).isEqualTo(1);
    }

    @Test
    void agrupaVeiculoCompartilhadoESemPlacaNaoViraVeiculo() throws Exception {
        var items = dados("/api/ranking?ano=2026&mes=8&tipo=veiculos");
        assertThat(items.size()).isEqualTo(2);
        var veiculo = items.get(1);
        assertThat(veiculo.path("veiculo").asText()).isEqualTo("DEF2E34");
        assertThat(veiculo.path("motorista").isNull()).isTrue();
        assertThat(veiculo.path("motoristaId").isNull()).isTrue();
        assertThat(veiculo.path("resultado").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(veiculo.path("quantidadeViagens").asInt()).isEqualTo(4);
        assertThat(veiculo.path("destinos").size()).isEqualTo(2);
    }

    @Test
    void buscaVeiculoPorMotoristaPreservaTotalDoVeiculo() throws Exception {
        var items = dados("/ranking?ano=2026&mes=8&tipo=veiculos&busca=Bruno");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("posicao").asInt()).isEqualTo(2);
        assertThat(items.get(0).path("resultado").decimalValue()).isEqualByComparingTo("800.00");
    }

    @Test
    void mesSemDadosRetornaListaVaziaSemBuscarDetalhes() throws Exception {
        bodies.put("/api/dashboard/rentabilidade-media", "{\"porMotorista\":[]}");
        assertThat(dados("/ranking?ano=2026&mes=1").size()).isZero();
        assertThat(chamadas).hasSize(1);
    }

    @Test
    void nenhumResultadoDaBuscaRetornaListaVazia() throws Exception {
        assertThat(dados("/ranking?ano=2026&mes=8&busca=inexistente").size()).isZero();
    }

    @Test
    void detalheSemViagensDoMesNaoEntraNoRanking() throws Exception {
        bodies.put("/api/motoristas/1", motorista(1, "Ana", "111", "ABC1D23",
            viagem("2026-07-01", "Santos", "9000", "0")));
        var items = dados("/ranking?ano=2026&mes=8");
        assertThat(items.size()).isEqualTo(3);
        assertThat(items.get(0).path("motorista").asText()).isEqualTo("Bruno");
    }

    @Test
    void idsRepetidosNaFonteNaoDuplicamValores() throws Exception {
        bodies.put("/api/dashboard/rentabilidade-media", "{\"porMotorista\":[{\"id\":1},{\"id\":1}]}");
        var items = dados("/ranking?ano=2026&mes=8");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("resultado").decimalValue()).isEqualByComparingTo("900");
        assertThat(chamadas).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ano=0&mes=8", "ano=10000&mes=8", "ano=2026&mes=0",
        "ano=2026&mes=13", "ano=x&mes=8", "ano=2026", "mes=8",
        "ano=2026&mes=8&tipo=invalido"})
    void parametrosInvalidosNaoConsultamFonte(String query) throws Exception {
        var response = get("/ranking?" + query, gestor());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("mensagem").asText()).isNotBlank();
        assertThat(chamadas).isEmpty();
    }

    @Test
    void buscaLongaRejeitada() throws Exception {
        assertThat(get("/ranking?ano=2026&mes=8&busca=" + "a".repeat(151), gestor()).statusCode()).isEqualTo(400);
        assertThat(chamadas).isEmpty();
    }

    @Test
    void semTokenRetorna401() throws Exception {
        assertThat(get("/ranking?ano=2026&mes=8", null).statusCode()).isEqualTo(401);
        assertThat(chamadas).isEmpty();
    }

    @Test
    void operadorRetorna403() throws Exception {
        assertThat(get("/ranking?ano=2026&mes=8", token("OPERADOR", SECRET, "newelog-auth-service", 300)).statusCode()).isEqualTo(403);
        assertThat(chamadas).isEmpty();
    }

    @Test
    void tokenExpiradoOuAssinadoErradoOuOutroEmissorRetorna401() throws Exception {
        for (String jwt : List.of(token("GESTOR", SECRET, "newelog-auth-service", -3600),
            token("GESTOR", SECRET, "outro", 300),
            token("GESTOR", "outro-segredo-de-mais-de-trinta-e-dois-bytes", "newelog-auth-service", 300))) {
            assertThat(get("/ranking?ano=2026&mes=8", jwt).statusCode()).isEqualTo(401);
        }
        assertThat(chamadas).isEmpty();
    }

    @Test
    void healthPublico() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(chamadas).isEmpty();
    }

    @Test
    void falhaDaFonteNaoDevolveRankingParcial() throws Exception {
        falha = 500; falharEm = "/api/motoristas/2";
        var response = get("/ranking?ano=2026&mes=8", gestor());
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(mapper.readTree(response.body()).has("dados")).isFalse();
        assertThat(chamadas).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void preservaNegativaDeAcessoDaFonte(int status) throws Exception {
        falha = status; falharEm = "/api/dashboard/rentabilidade-media";
        assertThat(get("/ranking?ano=2026&mes=8", gestor()).statusCode()).isEqualTo(status);
    }

    @Test
    void timeoutRetorna503() throws Exception {
        atraso = 800;
        assertThat(get("/ranking?ano=2026&mes=8", gestor()).statusCode()).isEqualTo(503);

    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"porMotorista\":null}", "{\"porMotorista\":[{\"id\":null}]}", "nao-json"})
    void respostaMensalInvalidaNaoViraMesVazio(String body) throws Exception {
        bodies.put("/api/dashboard/rentabilidade-media", body);
        assertThat(get("/ranking?ano=2026&mes=8", gestor()).statusCode()).isEqualTo(502);
    }

    @Test
    void detalheInvalidoNaoViraRankingParcial() throws Exception {
        bodies.put("/api/motoristas/2", "{\"id\":2}");
        assertThat(get("/ranking?ano=2026&mes=8", gestor()).statusCode()).isEqualTo(502);
    }

    @Test
    void valorMonetarioAusenteNaoViraZero() throws Exception {
        bodies.put("/api/motoristas/2", motorista(2, "Bruno", "222", "DEF2E34",
            "{\"data\":\"2026-08-01\",\"valorFrete\":100}"));
        assertThat(get("/ranking?ano=2026&mes=8", gestor()).statusCode()).isEqualTo(502);
    }

    @Test
    void placaComHifenEncontraVeiculo() throws Exception {
        var items = dados("/ranking?ano=2026&mes=8&tipo=veiculos&busca=DEF-2E34");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("veiculo").asText()).isEqualTo("DEF2E34");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 12})
    void respeitaLimitesDoMesNaViradaDoAno(int mes) throws Exception {
        bodies.put("/api/dashboard/rentabilidade-media", "{\"porMotorista\":[{\"id\":1}]}");
        String inicio = mes == 1 ? "2026-01-01" : "2026-12-01";
        String fim = mes == 1 ? "2026-01-31" : "2026-12-31";
        String antes = mes == 1 ? "2025-12-31" : "2026-11-30";
        String depois = mes == 1 ? "2026-02-01" : "2027-01-01";
        bodies.put("/api/motoristas/1", motorista(1, "Ana", "111", "ABC1D23",
            viagem(inicio, "Santos", "100", "1") + "," +
            viagem(fim, "Santos", "200", "2") + "," +
            viagem(antes, "Santos", "99999", "0") + "," +
            viagem(depois, "Santos", "99999", "0")));
        var items = dados("/ranking?ano=2026&mes=" + mes);
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("resultado").decimalValue()).isEqualByComparingTo("297");
        assertThat(items.get(0).path("quantidadeViagens").asInt()).isEqualTo(2);
    }

    @Test
    void preflightDoFrontPermitidoSemToken() throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .options("/ranking").header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "authorization")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(chamadas).isEmpty();
    }


    @Test
    void fevereiroBissextoIncluiDia29SemPuxarMarco() throws Exception {
        participantes(1);
        bodies.put("/api/motoristas/1", motorista(1, "Ana", "111", "ABC1D23",
            viagem("2024-02-01", "Santos", "100", "1") + "," +
            viagem("2024-02-29", "Santos", "200", "2") + "," +
            viagem("2024-03-01", "Santos", "9999", "0")));
        var items = dados("/ranking?ano=2024&mes=2");
        assertThat(items.get(0).path("resultado").decimalValue()).isEqualByComparingTo("297");
        assertThat(items.get(0).path("quantidadeViagens").asInt()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 9999})
    void extremosDeAnoAceitosSemEstouro(int ano) throws Exception {
        participantes(1);
        String data = String.format(java.util.Locale.ROOT, "%04d-12-31", ano);
        bodies.put("/api/motoristas/1", motorista(1, "Ana", "111", "ABC1D23",
            viagem(data, "Santos", "1", "0")));
        assertThat(dados("/ranking?ano=" + ano + "&mes=12").size()).isEqualTo(1);
    }

    @Test
    void centavosSomadosSemErroDePontoFlutuante() throws Exception {
        participantes(1);
        bodies.put("/api/motoristas/1", motorista(1, "Ana", "111", "ABC1D23",
            viagem("2026-08-01", "Santos", "0.10", "0.01") + "," +
            viagem("2026-08-02", "Santos", "0.20", "0.00")));
        assertThat(dados("/ranking?ano=2026&mes=8").get(0).path("resultado")
            .decimalValue()).isEqualByComparingTo("0.29");
    }

    @Test
    void maiorNumeroDeViagensDesempataAntesDoNome() throws Exception {
        participantes(1, 2);
        bodies.put("/api/motoristas/1", motorista(1, "Ana", "111", "ABC1D23",
            viagem("2026-08-01", "Santos", "400", "0")));
        var items = dados("/ranking?ano=2026&mes=8");
        assertThat(items.get(0).path("motorista").asText()).isEqualTo("Bruno");
        assertThat(items.get(1).path("motorista").asText()).isEqualTo("Ana");
    }

    @Test
    void idDesempataNomesIguaisMesmoComFonteEmOutraOrdem() throws Exception {
        participantes(2, 1);
        for (int id : new int[]{1, 2}) bodies.put("/api/motoristas/" + id,
            motorista(id, "Mesmo Nome", "111", "ABC1D23", viagem("2026-08-01", "Santos", "100", "0")));
        var items = dados("/ranking?ano=2026&mes=8");
        assertThat(items.get(0).path("motoristaId").asLong()).isEqualTo(1);
        assertThat(items.get(1).path("motoristaId").asLong()).isEqualTo(2);
    }

    @Test
    void resultadoNegativoContinuaOrdenadoDoMaiorParaMenor() throws Exception {
        participantes(1, 2);
        bodies.put("/api/motoristas/1", motorista(1, "Ana", "111", "ABC1D23",
            viagem("2026-08-01", "Santos", "0", "100")));
        bodies.put("/api/motoristas/2", motorista(2, "Bruno", "222", "DEF2E34",
            viagem("2026-08-01", "Santos", "0", "5")));
        assertThat(dados("/ranking?ano=2026&mes=8").get(0).path("resultado")
            .decimalValue()).isEqualByComparingTo("-5");
    }

    @Test
    void buscaVaziaOuComEspacosPreservaListaCompleta() throws Exception {
        assertThat(dados("/ranking?ano=2026&mes=8&busca=").size()).isEqualTo(4);
        assertThat(dados("/ranking?ano=2026&mes=8&busca=%20%20").size()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{\"porMotorista\":[null]}", "{\"porMotorista\":[{\"id\":0}]}",
        "{\"porMotorista\":[{\"id\":-1}]}", "{\"porMotorista\":[{\"id\":\"invalido\"}]}"})
    void participantesInvalidosSaoRecusados(String body) throws Exception {
        bodies.put("/api/dashboard/rentabilidade-media", body);
        assertThat(get("/ranking?ano=2026&mes=8", gestor()).statusCode()).isEqualTo(502);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"id\":999,\"nome\":\"Outro\",\"viagens\":[]}",
        "{\"id\":1,\"viagens\":[]}", "{\"id\":1,\"nome\":\"  \",\"viagens\":[]}"})
    void detalheComIdentidadeInvalidaRecusado(String body) throws Exception {
        participantes(1);
        bodies.put("/api/motoristas/1", body);
        assertThat(get("/ranking?ano=2026&mes=8", gestor()).statusCode()).isEqualTo(502);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"data\":\"nao-data\",\"valorFrete\":1,\"valorPedagio\":0}",
        "{\"data\":\"2026-08-01\",\"valorFrete\":-1,\"valorPedagio\":0}",
        "{\"data\":\"2026-08-01\",\"valorFrete\":1,\"valorPedagio\":-1}"})
    void viagemInvalidaNaoGeraResultadoFalso(String body) throws Exception {
        participantes(1);
        bodies.put("/api/motoristas/1", motorista(1, "Ana", "111", "ABC1D23", body));
        assertThat(get("/ranking?ano=2026&mes=8", gestor()).statusCode()).isEqualTo(502);
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 400, 404, 429, 503})
    void erroDaFonteNaoViraRespostaDeSucesso(int status) throws Exception {
        falha = status; falharEm = "/api/dashboard/rentabilidade-media";
        assertThat(get("/ranking?ano=2026&mes=8", gestor()).statusCode()).isEqualTo(502);
    }

    @Test
    void origemNaoAutorizadaBloqueada() throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .options("/ranking").header("Origin", "https://origem-invalida.example")
            .header("Access-Control-Request-Method", "GET")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chamadas).isEmpty();
    }

    @Test
    void postNaoCriaOuAlteraDados() throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/ranking").header("Authorization", "Bearer " + gestor()))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(chamadas).isEmpty();
    }

    @Test
    void perfilDesconhecidoBloqueado() throws Exception {
        assertThat(get("/ranking?ano=2026&mes=8",
            token("ADMIN", SECRET, "newelog-auth-service", 300)).statusCode()).isEqualTo(403);
        assertThat(chamadas).isEmpty();
    }

    @Test
    void tokenAindaNaoValidoBloqueado() throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
            .issuer("newelog-auth-service").claim("perfil", "GESTOR")
            .notBeforeTime(Date.from(Instant.now().plusSeconds(3600)))
            .expirationTime(Date.from(Instant.now().plusSeconds(7200))).build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        assertThat(get("/ranking?ano=2026&mes=8", jwt.serialize()).statusCode()).isEqualTo(401);
        assertThat(chamadas).isEmpty();
    }

    @Test
    void suporta500MotoristasSemCortarParticipantes() throws Exception {
        long[] ids = java.util.stream.LongStream.rangeClosed(1, 500).toArray();
        participantes(ids);
        for (long id : ids) bodies.put("/api/motoristas/" + id,
            motorista((int) id, "Motorista " + id, "111", "ABC1D23",
                viagem("2026-08-01", "Santos", "1.25", "0.25")));
        var items = dados("/ranking?ano=2026&mes=8");
        assertThat(items.size()).isEqualTo(500);
        assertThat(chamadas).hasSize(501);
        assertThat(java.util.stream.StreamSupport.stream(items.spliterator(), false)
            .map(n -> n.path("resultado").decimalValue()).reduce(java.math.BigDecimal.ZERO,
                java.math.BigDecimal::add)).isEqualByComparingTo("500.00");
        assertThat(items.get(499).path("posicao").asInt()).isEqualTo(500);
    }

    @Test
    void valoresConferemComReferenciaIndependenteEm30Cenarios() throws Exception {
        var random = new java.util.Random(170146147);
        participantes(1, 2, 3, 4);
        for (int cenario = 0; cenario < 30; cenario++) {
            var esperados = new java.util.HashMap<Long, Long>();
            long totalVeiculo = 0;
            for (int id = 1; id <= 4; id++) {
                long saldoCentavos = 0;
                var viagens = new java.util.ArrayList<String>();
                for (int viagem = 0; viagem < 12; viagem++) {
                    int frete = random.nextInt(100000), pedagio = random.nextInt(100000);
                    boolean noMes = random.nextBoolean();
                    viagens.add(viagem(noMes ? "2026-08-10" : "2026-07-10", "Santos",
                        java.math.BigDecimal.valueOf(frete, 2).toPlainString(),
                        java.math.BigDecimal.valueOf(pedagio, 2).toPlainString()));
                    if (noMes) saldoCentavos += frete - pedagio;
                }
                esperados.put((long) id, saldoCentavos);
                totalVeiculo += saldoCentavos;
                bodies.put("/api/motoristas/" + id, motorista(id, "Motorista " + id, "111", "ABC1D23",
                    String.join(",", viagens)));
            }
            var items = dados("/ranking?ano=2026&mes=8");
            for (var item : items) assertThat(item.path("resultado").decimalValue())
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(esperados.get(item.path("motoristaId").asLong()), 2));
            var veiculos = dados("/ranking?ano=2026&mes=8&tipo=veiculos");
            assertThat(veiculos.size()).isEqualTo(1);
            assertThat(veiculos.get(0).path("resultado").decimalValue())
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(totalVeiculo, 2));
        }
    }

    // --- KOI-148: ordenação e desempate (CA148-*) ---

    @Test
    void nomeOrdenaSemDiferenciarAcentoNemMaiuscula() throws Exception {
        participantes(1, 2, 3);
        String[] nomes = {"bruno", "Álvaro", "Carla"};
        for (int id = 1; id <= 3; id++) bodies.put("/api/motoristas/" + id,
            motorista(id, nomes[id - 1], "111", "ABC1D23", viagem("2026-08-01", "Santos", "100", "0")));
        var items = dados("/ranking?ano=2026&mes=8");
        assertThat(items.findValuesAsText("motorista")).containsExactly("Álvaro", "bruno", "Carla");
        assertThat(posicoes(items)).containsExactly(1, 2, 3);
    }

    @Test
    void visaoVeiculosDesempataPorViagensEDepoisPorPlaca() throws Exception {
        participantes(1, 2, 3);
        bodies.put("/api/motoristas/1", motorista(1, "Ana", "111", "ZZZ9Z99",
            viagem("2026-08-01", "Santos", "100", "0")));
        bodies.put("/api/motoristas/2", motorista(2, "Bruno", "222", "aaa-1a11",
            viagem("2026-08-01", "Santos", "100", "0")));
        bodies.put("/api/motoristas/3", motorista(3, "Clara", "333", "MMM1M11",
            viagem("2026-08-01", "Santos", "50", "0") + "," + viagem("2026-08-02", "Santos", "50", "0")));
        var json = raiz("/ranking?ano=2026&mes=8&tipo=veiculos");
        assertThat(json.path("criterioOrdenacao").asText()).isEqualTo("RESULTADO_FINANCEIRO");
        assertThat(json.path("tipo").asText()).isEqualTo("veiculos");
        var items = json.path("dados");
        assertThat(items.findValuesAsText("veiculo")).containsExactly("MMM1M11", "AAA1A11", "ZZZ9Z99");
        assertThat(posicoes(items)).containsExactly(1, 2, 3);
    }

    @Test
    void empateTotalTemPosicoesUnicasEOrdemRepetivel() throws Exception {
        participantes(5, 3, 1, 4, 2);
        for (int id = 1; id <= 5; id++) bodies.put("/api/motoristas/" + id,
            motorista(id, "Mesmo Nome", "111", "ABC1D23", viagem("2026-08-01", "Santos", "100", "0")));
        var primeira = dados("/ranking?ano=2026&mes=8");
        assertThat(posicoes(primeira)).containsExactly(1, 2, 3, 4, 5);
        assertThat(primeira.findValues("motoristaId").stream().map(JsonNode::asInt).toList())
            .containsExactly(1, 2, 3, 4, 5);
        assertThat(dados("/ranking?ano=2026&mes=8")).isEqualTo(primeira);
    }

    @Test
    void resultadoIgualComCasasDecimaisDiferentesContaComoEmpate() throws Exception {
        participantes(1, 2);
        bodies.put("/api/motoristas/1", motorista(1, "Ana", "111", "ABC1D23",
            viagem("2026-08-01", "Santos", "100.0", "0")));
        bodies.put("/api/motoristas/2", motorista(2, "Bruno", "222", "DEF2E34",
            viagem("2026-08-01", "Santos", "50.00", "0") + "," + viagem("2026-08-02", "Santos", "50.00", "0.00")));
        var items = dados("/ranking?ano=2026&mes=8");
        assertThat(items.findValuesAsText("motorista")).containsExactly("Bruno", "Ana");
    }

    // --- KOI-149: busca e posição real (CA149-*) ---

    @ParameterizedTest
    @ValueSource(strings = {"222.222", "2222", "222-22"})
    void buscaPorTrechoDeCpfEncontraMotorista(String busca) throws Exception {
        var items = dados("/ranking?ano=2026&mes=8&busca=" + busca);
        assertThat(items.findValuesAsText("motorista")).containsExactly("Bruno");
        assertThat(posicoes(items)).containsExactly(2);
    }

    @Test
    void buscaPorCpfNaVisaoVeiculosDevolveVeiculoDoMotorista() throws Exception {
        var items = dados("/ranking?ano=2026&mes=8&tipo=veiculos&busca=444.444.444-44");
        assertThat(items.findValuesAsText("veiculo")).containsExactly("DEF2E34");
        assertThat(posicoes(items)).containsExactly(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.345.678/0001-90", "12345678000190", "678/0001"})
    void buscaPorCnpjComOuSemMascara(String busca) throws Exception {
        bodies.put("/api/motoristas/3", motorista(3, "Clara", "12.345.678/0001-90", null,
            viagem("2026-08-20", "Santos", "0.00", "10.00")));
        var items = dados("/ranking?ano=2026&mes=8&busca=" + busca);
        assertThat(items.findValuesAsText("motorista")).containsExactly("Clara");
        assertThat(posicoes(items)).containsExactly(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc-1d23", "ABC1D23", "1d2"})
    void buscaPorPlacaNaVisaoMotoristas(String busca) throws Exception {
        var items = dados("/ranking?ano=2026&mes=8&busca=" + busca);
        assertThat(items.findValuesAsText("motorista")).containsExactly("Ana Sá");
    }

    @ParameterizedTest
    @ValueSource(strings = {"clara", "333.333.333-33"})
    void ultimoColocadoMantemPosicaoRealNaBusca(String busca) throws Exception {
        var items = dados("/ranking?ano=2026&mes=8&busca=" + busca);
        assertThat(items.findValuesAsText("motorista")).containsExactly("Clara");
        assertThat(posicoes(items)).containsExactly(4);
    }

    @Test
    void buscaSoComPontuacaoNaoEncontraNinguem() throws Exception {
        assertThat(dados("/ranking?ano=2026&mes=8&busca=.-.").size()).isZero();
    }

    @Test
    void mensagensDeParametroInvalidoSaoAsDoContrato() throws Exception {
        assertThat(mensagem(get("/ranking?ano=2026&mes=8&tipo=invalido", gestor())))
            .isEqualTo("tipo deve ser motoristas ou veiculos.");
        assertThat(mensagem(get("/ranking?ano=2026&mes=8&busca=" + "a".repeat(151), gestor())))
            .isEqualTo("A busca deve ter no máximo 150 caracteres.");
        assertThat(dados("/ranking?ano=2026&mes=8&busca=" + "a".repeat(150)).size()).isZero();
    }

    // --- Contrato v2: totalParticipantes (A1) e corpo de 401/403 (A2) ---

    @Test
    void totalParticipantesContaOMesInteiroAntesDaBusca() throws Exception {
        assertThat(raiz("/ranking?ano=2026&mes=8").path("totalParticipantes").asInt()).isEqualTo(4);
        var comBusca = raiz("/ranking?ano=2026&mes=8&busca=Bruno");
        assertThat(comBusca.path("totalParticipantes").asInt()).isEqualTo(4);
        assertThat(comBusca.path("dados").size()).isEqualTo(1);
        assertThat(raiz("/ranking?ano=2026&mes=8&busca=inexistente").path("totalParticipantes").asInt()).isEqualTo(4);
        assertThat(raiz("/ranking?ano=2026&mes=8&tipo=veiculos").path("totalParticipantes").asInt()).isEqualTo(2);
        bodies.put("/api/dashboard/rentabilidade-media", "{\"porMotorista\":[]}");
        var vazio = raiz("/ranking?ano=2026&mes=1");
        assertThat(vazio.path("totalParticipantes").isInt()).isTrue();
        assertThat(vazio.path("totalParticipantes").asInt()).isZero();
    }

    @Test
    void semTokenOuTokenInvalidoDevolveMensagemEmJson() throws Exception {
        for (String jwt : java.util.Arrays.asList(null, "token-invalido",
                token("GESTOR", SECRET, "newelog-auth-service", -3600))) {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/ranking?ano=2026&mes=8");
            if (jwt != null) request.header("Authorization", "Bearer " + jwt);
            var response = mvc.perform(request).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
            var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertThat(json.path("mensagem").asText()).isEqualTo("Sessão expirada ou token inválido.");
            assertThat(Instant.parse(json.path("timestamp").asText())).isNotNull();
        }
        assertThat(chamadas).isEmpty();
    }

    @Test
    void perfilSemPermissaoDevolveMensagemEmJson() throws Exception {
        var response = get("/ranking?ano=2026&mes=8", token("OPERADOR", SECRET, "newelog-auth-service", 300));
        assertThat(response.statusCode()).isEqualTo(403);
        var json = mapper.readTree(response.body());
        assertThat(json.path("mensagem").asText()).isEqualTo("Você não tem permissão para acessar o ranking.");
        assertThat(Instant.parse(json.path("timestamp").asText())).isNotNull();
        assertThat(chamadas).isEmpty();
    }

    JsonNode raiz(String path) throws Exception {
        var response = get(path, gestor());
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    String mensagem(Resposta response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        return mapper.readTree(response.body()).path("mensagem").asText();
    }

    static List<Integer> posicoes(JsonNode items) {
        return items.findValues("posicao").stream().map(JsonNode::asInt).toList();
    }

    void participantes(long... ids) throws Exception {
        bodies.put("/api/dashboard/rentabilidade-media", mapper.writeValueAsString(Map.of("porMotorista",
            java.util.Arrays.stream(ids).mapToObj(id -> Map.of("id", id)).toList())));
    }


    JsonNode dados(String path) throws Exception {
        var response = get(path, gestor());
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("dados");
    }

    record Resposta(int statusCode, String body) {}
    Resposta get(String path, String jwt) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URI.create(path));
        if (jwt != null) request.header("Authorization", "Bearer " + jwt);
        var response = mvc.perform(request).andReturn().getResponse();
        return new Resposta(response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
    }

    static String gestor() throws Exception { return token("GESTOR", SECRET, "newelog-auth-service", 300); }
    static String token(String perfil, String secret, String issuer, long segundos) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
            .subject("teste").issuer(issuer).claim("perfil", perfil)
            .expirationTime(Date.from(Instant.now().plusSeconds(segundos))).build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    static String viagem(String data, String destino, String frete, String pedagio) {
        return "{\"data\":\"" + data + "\",\"destino\":\"" + destino +
            "\",\"valorFrete\":" + frete + ",\"valorPedagio\":" + pedagio + "}";
    }
    static String motorista(int id, String nome, String cpf, String placa, String viagens) {
        return "{\"id\":" + id + ",\"nome\":\"" + nome + "\",\"cpfCnpj\":\"" + cpf +
            "\",\"placaVeiculo\":" + (placa == null ? "null" : "\"" + placa + "\"") +
            ",\"campoDesconhecido\":true,\"viagens\":[" + viagens + "]}";
    }

}
