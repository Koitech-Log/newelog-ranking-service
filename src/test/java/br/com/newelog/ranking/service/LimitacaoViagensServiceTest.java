package br.com.newelog.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import br.com.newelog.ranking.config.LimitacaoViagensProperties;

class LimitacaoViagensServiceTest {

    private static LimitacaoViagensService servico(int limite) {
        return new LimitacaoViagensService(new LimitacaoViagensProperties(limite));
    }
    private static BigDecimal v(String valor) { return new BigDecimal(valor); }

    @Test
    void abaixoDoLimiteMantemTodasAsViagens() {
        var r = servico(5).aplicar(List.of(v("10.00"), v("30.00"), v("20.00")));
        assertThat(r).containsExactly(v("30.00"), v("20.00"), v("10.00"));
    }

    @Test
    void acimaDoLimiteMantemAsMaioresValores() {
        var r = servico(3).aplicar(List.of(v("10.00"), v("500.00"), v("20.00"),
                v("300.00"), v("5.00"), v("400.00")));
        assertThat(r).containsExactly(v("500.00"), v("400.00"), v("300.00"));
    }

    @Test
    void muitasViagensPequenasNaoSuperamPoucasGrandes() {
        var servico = servico(5);
        var pequenas = Collections.nCopies(40, v("15.00"));   // 40 x 15 = 600 sem limite
        var grandes = Collections.nCopies(5, v("100.00"));    // 5 x 100 = 500
        BigDecimal somaPequenas = soma(servico.aplicar(pequenas));  // 5 x 15 = 75
        BigDecimal somaGrandes = soma(servico.aplicar(grandes));    // 500
        assertThat(somaPequenas).isLessThan(somaGrandes);
    }

    @Test
    void listaVaziaRetornaVazia() {
        assertThat(servico(5).aplicar(List.of())).isEmpty();
    }

    @Test
    void mesmosDadosEmOrdemDiferenteDaoOMesmoResultado() {
        var a = List.of(v("1.00"), v("9.00"), v("5.00"), v("7.00"));
        var b = List.of(v("7.00"), v("5.00"), v("9.00"), v("1.00"));
        assertThat(servico(2).aplicar(a)).isEqualTo(servico(2).aplicar(b));
    }

    @Test
    void limiteInvalidoFalhaNaConfiguracao() {
        assertThatThrownBy(() -> new LimitacaoViagensProperties(0))
                .isInstanceOf(IllegalStateException.class);
    }

    private static BigDecimal soma(List<BigDecimal> valores) {
        return valores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}