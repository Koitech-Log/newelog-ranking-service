package br.com.newelog.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import br.com.newelog.ranking.config.ValorizacaoProperties;

class ValorizacaoViagemServiceTest {

    private static ValorizacaoViagemService servico(String fator) {
        return new ValorizacaoViagemService(new ValorizacaoProperties(new BigDecimal(fator)));
    }
    private static BigDecimal v(String valor) { return new BigDecimal(valor); }

    @Test
    void destinoSaoPauloAplicaOFator() {
        assertThat(servico("1.20").aplicar(v("100.00"), "São Paulo"))
                .isEqualByComparingTo("120.00");
    }

    @Test
    void outroDestinoMantemOValorBase() {
        assertThat(servico("1.20").aplicar(v("100.00"), "Aracaju"))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void mudarOFatorNaConfiguracaoMudaOResultado() {
        assertThat(servico("1.50").aplicar(v("100.00"), "São Paulo"))
                .isEqualByComparingTo("150.00");
    }

    @Test
    void reconheceSaoPauloSemDependerDeAcentoOuMaiuscula() {
        assertThat(servico("1.20").aplicar(v("100.00"), "  SAO PAULO "))
                .isEqualByComparingTo("120.00");
    }

    @Test
    void destinoNuloOuNaoInformadoMantemOValorBase() {
        assertThat(servico("1.20").aplicar(v("100.00"), null)).isEqualByComparingTo("100.00");
        assertThat(servico("1.20").aplicar(v("100.00"), "Não informado")).isEqualByComparingTo("100.00");
    }

    @Test
    void arredondaParaDuasCasas() {
        assertThat(servico("1.15").aplicar(v("10.01"), "São Paulo"))
                .isEqualByComparingTo("11.51"); // 10.01 x 1.15 = 11.5115
    }

    @Test
    void fatorMenorQueUmFalhaNaConfiguracao() {
        assertThatThrownBy(() -> new ValorizacaoProperties(new BigDecimal("0.90")))
                .isInstanceOf(IllegalStateException.class);
    }

    // P3, opção B: lista de cidades separadas por vírgula

    @ParameterizedTest
    @ValueSource(strings = {
            "São Paulo, Cotia",
            "Guarulhos, São Paulo, Diadema",
            "Poá, São Paulo",
            "Osasco,São Paulo",
            "Barueri, SÃO  PAULO , Guarulhos",
            "São Paulo, São José dos Campos",
            "Santana de Parnaíba, Diadema, São Paulo, Santos",
            "São Paulo, Itapecerica da Serra…"})
    void listaComSaoPauloAplicaOFatorUmaVez(String destino) {
        assertThat(servico("1.20").aplicar(v("100.00"), destino)).isEqualByComparingTo("120.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Paulo Afonso",
            "São Paulo do Potengi",
            "São Paulo das Missões, Três Passos",
            "São Nicolau, São Paulo das Missões, Três Passos, Dezesseis de Novembro",
            "Santo André, São Bernardo do Campo",
            "São José dos Campos",
            "São Pauloo, Cotia",
            ", ,",
            ""})
    void listaSemSaoPauloExatoMantemOValorBase(String destino) {
        assertThat(servico("1.20").aplicar(v("100.00"), destino)).isEqualByComparingTo("100.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Campinas, São Paulo…", "Campinas, São Paulo...", "São Paulo…"})
    void cidadeCortadaPeloResumoNaoConta(String destino) {
        assertThat(servico("1.20").aplicar(v("100.00"), destino)).isEqualByComparingTo("100.00");
    }
}
