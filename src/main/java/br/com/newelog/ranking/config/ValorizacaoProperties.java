package br.com.newelog.ranking.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ranking.valorizacao")
public record ValorizacaoProperties(BigDecimal fatorSaoPaulo) {

    public ValorizacaoProperties {
        // valorização nunca pode reduzir o valor: o fator mínimo é 1 (sem bônus)
        if (fatorSaoPaulo == null || fatorSaoPaulo.compareTo(BigDecimal.ONE) < 0)
            throw new IllegalStateException("ranking.valorizacao.fator-sao-paulo deve ser pelo menos 1.");
    }
}