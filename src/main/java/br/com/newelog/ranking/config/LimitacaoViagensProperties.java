package br.com.newelog.ranking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ranking.limitacao")
public record LimitacaoViagensProperties(int maxViagensPorMes) {

    public LimitacaoViagensProperties {
        if (maxViagensPorMes < 1)
            throw new IllegalStateException("ranking.limitacao.max-viagens-por-mes deve ser pelo menos 1.");
    }
}