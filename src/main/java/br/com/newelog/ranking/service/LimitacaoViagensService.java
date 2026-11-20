package br.com.newelog.ranking.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import br.com.newelog.ranking.config.LimitacaoViagensProperties;

@Service
public class LimitacaoViagensService {
    private final LimitacaoViagensProperties props;

    public LimitacaoViagensService(LimitacaoViagensProperties props) {
        this.props = props;
    }

    /** Recebe os valores já valorizados de um motorista/veículo no mês e
     *  devolve só os que contam: as N maiores viagens. */
    public List<BigDecimal> aplicar(List<BigDecimal> valores) {
        Objects.requireNonNull(valores, "valores é obrigatório");
        return valores.stream()
                .sorted(Comparator.reverseOrder())   // do maior para o menor
                .limit(props.maxViagensPorMes())     // fica só com as N primeiras
                .toList();                           // lista nova, a original não muda
    }
}