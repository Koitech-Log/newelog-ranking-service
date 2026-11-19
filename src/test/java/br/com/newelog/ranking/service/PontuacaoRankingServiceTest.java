package br.com.newelog.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.newelog.ranking.client.MotoristasClient;
import br.com.newelog.ranking.client.MotoristasClient.Motorista;
import br.com.newelog.ranking.client.MotoristasClient.Viagem;
import br.com.newelog.ranking.config.LimitacaoViagensProperties;
import br.com.newelog.ranking.config.ValorizacaoProperties;

class PontuacaoRankingServiceTest {

    private static final String AUTHORIZATION = "Bearer teste";

    private PontuacaoRankingService criarPontuacao(int limite) {
        var valorizacao = new ValorizacaoViagemService(
            new ValorizacaoProperties(new BigDecimal("1.20"))
        );

        var limitacao = new LimitacaoViagensService(
            new LimitacaoViagensProperties(limite)
        );

        return new PontuacaoRankingService(valorizacao, limitacao);
    }

    private RankingService criarRanking(
            int limite,
            List<Motorista> motoristas
    ) {
        // Simula somente a consulta ao serviço externo de motoristas.
        var client = mock(MotoristasClient.class);

        when(client.consultarMes(2026, 8, AUTHORIZATION))
            .thenReturn(motoristas);

        return new RankingService(client, criarPontuacao(limite));
    }

    private Viagem viagem(
            String data,
            String destino,
            String frete,
            String pedagio
    ) {
        return new Viagem(
            LocalDate.parse(data),
            destino,
            new BigDecimal(frete),
            new BigDecimal(pedagio)
        );
    }

    private Motorista motorista(
            long id,
            String nome,
            String placa,
            Viagem... viagens
    ) {
        return new Motorista(
            id,
            nome,
            "11111111111",
            placa,
            List.of(viagens)
        );
    }

    @Test
    void somaValoresValorizadosSemDuplicarRentabilidade() {
        var servico = criarPontuacao(20);

        var sp = viagem(
            "2026-08-01", "São Paulo", "150.00", "50.00"
        );
        var campinas = viagem(
            "2026-08-02", "Campinas", "250.00", "50.00"
        );

        // São Paulo: (150 - 50) * 1.20 = 120.
        // Campinas: 250 - 50 = 200.
        assertThat(servico.calcular(List.of(sp, campinas)))
            .isEqualByComparingTo("320.00");

        // O cálculo não altera os valores financeiros da viagem.
        assertThat(sp.valorFrete()).isEqualByComparingTo("150.00");
        assertThat(sp.valorPedagio()).isEqualByComparingTo("50.00");
    }

    @Test
    void aplicaLimiteDepoisDaValorizacao() {
        var servico = criarPontuacao(1);

        var campinas = viagem(
            "2026-08-01", "Campinas", "110", "0"
        );
        var sp = viagem(
            "2026-08-02", "São Paulo", "100", "0"
        );

        // Após a valorização, São Paulo vale 120 e supera Campinas.
        assertThat(servico.calcular(List.of(campinas, sp)))
            .isEqualByComparingTo("120.00");
    }

    @Test
    void listaVaziaRetornaZero() {
        assertThat(criarPontuacao(20).calcular(List.of()))
            .isEqualByComparingTo("0.00");
    }

    @Test
    void ordemDasViagensNaoAlteraPontuacao() {
        var servico = criarPontuacao(2);

        var viagens = List.of(
            viagem("2026-08-01", "Campinas", "40", "0"),
            viagem("2026-08-02", "São Paulo", "100", "0"),
            viagem("2026-08-03", "Santos", "80", "0")
        );

        var invertidas = new ArrayList<>(viagens);
        Collections.reverse(invertidas);

        // Seleciona 120 e 80, independentemente da ordem recebida.
        assertThat(servico.calcular(viagens))
            .isEqualByComparingTo("200.00");

        assertThat(servico.calcular(invertidas))
            .isEqualByComparingTo("200.00");
    }

    @Test
    void rankingOrdenaPorPontuacaoPreservandoResultadoFinanceiro() {
        var ranking = criarRanking(
            20,
            List.of(
                motorista(
                    1, "Ana", "ABC1D23",
                    viagem("2026-08-01", "Campinas", "110", "0")
                ),
                motorista(
                    2, "Bruno", "DEF2E34",
                    viagem("2026-08-02", "São Paulo", "150", "50")
                )
            )
        );

        var resposta = ranking.consultar(
            2026, 8, "motoristas", null, AUTHORIZATION
        );

        assertThat(resposta.criterioOrdenacao()).isEqualTo("PONTUACAO");
        assertThat(resposta.totalParticipantes()).isEqualTo(2);
        assertThat(resposta.dados()).hasSize(2);

        var primeiro = resposta.dados().get(0);

        // Bruno tem resultado financeiro menor, mas pontuação maior.
        assertThat(primeiro.motorista()).isEqualTo("Bruno");
        assertThat(primeiro.posicao()).isEqualTo(1);
        assertThat(primeiro.resultado()).isEqualByComparingTo("100");
        assertThat(primeiro.pontuacao()).isEqualByComparingTo("120");

        assertThat(resposta.dados().get(1).motorista()).isEqualTo("Ana");
        assertThat(resposta.dados().get(1).pontuacao())
            .isEqualByComparingTo("110");
    }

    @Test
    void limitaPontuacaoSemLimitarTotaisFinanceirosOuContagem() {
        var ranking = criarRanking(
            1,
            List.of(
                motorista(
                    1, "Ana", "ABC1D23",
                    viagem("2026-08-01", "São Paulo", "150", "50"),
                    viagem("2026-08-02", "Campinas", "100", "20"),
                    viagem("2026-07-31", "São Paulo", "9999", "0")
                )
            )
        );

        var resposta = ranking.consultar(
            2026, 8, "motoristas", null, AUTHORIZATION
        );

        var item = resposta.dados().get(0);

        // Os totais incluem as duas viagens de agosto.
        assertThat(item.quantidadeViagens()).isEqualTo(2);
        assertThat(item.freteTotal()).isEqualByComparingTo("250");
        assertThat(item.custoTotal()).isEqualByComparingTo("70");
        assertThat(item.resultado()).isEqualByComparingTo("180");

        // Apenas a maior contribuição de agosto entra na pontuação.
        assertThat(item.pontuacao()).isEqualByComparingTo("120");
    }

    @Test
    void aplicaLimiteAoVeiculoInteiroMesmoComDoisMotoristas() {
        var ranking = criarRanking(
            1,
            List.of(
                motorista(
                    1, "Ana", "ABC-1D23",
                    viagem("2026-08-01", "São Paulo", "100", "0")
                ),
                motorista(
                    2, "Bruno", "ABC1D23",
                    viagem("2026-08-02", "Campinas", "110", "0")
                )
            )
        );

        var resposta = ranking.consultar(
            2026, 8, "veiculos", null, AUTHORIZATION
        );

        assertThat(resposta.totalParticipantes()).isEqualTo(1);
        assertThat(resposta.dados()).hasSize(1);

        var item = resposta.dados().get(0);

        assertThat(item.veiculo()).isEqualTo("ABC1D23");
        assertThat(item.motoristaId()).isNull();
        assertThat(item.quantidadeViagens()).isEqualTo(2);
        assertThat(item.resultado()).isEqualByComparingTo("210");

        // O limite é aplicado ao veículo, não separadamente por motorista.
        assertThat(item.pontuacao()).isEqualByComparingTo("120");
    }

    @Test
    void buscaPreservaPosicaoETotalDeParticipantes() {
        var ranking = criarRanking(
            20,
            List.of(
                motorista(
                    1, "Ana", "ABC1D23",
                    viagem("2026-08-01", "Campinas", "110", "0")
                ),
                motorista(
                    2, "Bruno", "DEF2E34",
                    viagem("2026-08-02", "São Paulo", "100", "0")
                )
            )
        );

        var resposta = ranking.consultar(
            2026, 8, "motoristas", "Ana", AUTHORIZATION
        );

        assertThat(resposta.totalParticipantes()).isEqualTo(2);
        assertThat(resposta.dados()).hasSize(1);
        assertThat(resposta.dados().get(0).posicao()).isEqualTo(2);
        assertThat(resposta.dados().get(0).motorista()).isEqualTo("Ana");
    }

    @Test
    void empateDePontuacaoMantemDesempatePorQuantidadeDeViagens() {
        var ranking = criarRanking(
            20,
            List.of(
                motorista(
                    1, "Ana", "ABC1D23",
                    viagem("2026-08-01", "São Paulo", "100", "0")
                ),
                motorista(
                    2, "Bruno", "DEF2E34",
                    viagem("2026-08-02", "Campinas", "60", "0"),
                    viagem("2026-08-03", "Campinas", "60", "0")
                )
            )
        );

        var resposta = ranking.consultar(
            2026, 8, "motoristas", null, AUTHORIZATION
        );

        // Ambos têm 120 pontos. Bruno fica primeiro por ter mais viagens.
        assertThat(resposta.dados().get(0).motorista()).isEqualTo("Bruno");
        assertThat(resposta.dados().get(0).pontuacao())
            .isEqualByComparingTo("120");
        assertThat(resposta.dados().get(1).pontuacao())
            .isEqualByComparingTo("120");
    }

    @Test
    void participanteSemViagensDoMesNaoEntraNoRanking() {
        var ranking = criarRanking(
            20,
            List.of(
                motorista(
                    1, "Ana", "ABC1D23",
                    viagem("2026-07-31", "São Paulo", "100", "0")
                )
            )
        );

        var resposta = ranking.consultar(
            2026, 8, "motoristas", null, AUTHORIZATION
        );

        assertThat(resposta.totalParticipantes()).isZero();
        assertThat(resposta.dados()).isEmpty();
    }
}