package br.com.newelog.ranking.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import br.com.newelog.ranking.client.MotoristasClient.Viagem;

@Service
public class PontuacaoRankingService {

    private final ValorizacaoViagemService valorizacaoViagemService;
    private final LimitacaoViagensService limitacaoViagensService;

    public PontuacaoRankingService(
            ValorizacaoViagemService valorizacaoViagemService,
            LimitacaoViagensService limitacaoViagensService
    ) {
        this.valorizacaoViagemService = valorizacaoViagemService;
        this.limitacaoViagensService = limitacaoViagensService;
    }

    /**
     * Calcula o valor valorizado de uma viagem.
     * Não modifica os valores financeiros originais.
     */
    public BigDecimal calcularViagem(Viagem viagem) {
        Objects.requireNonNull(viagem, "A viagem é obrigatória.");
        Objects.requireNonNull(
                viagem.valorFrete(),
                "O valor do frete é obrigatório."
        );
        Objects.requireNonNull(
                viagem.valorPedagio(),
                "O valor do pedágio é obrigatório."
        );

        BigDecimal rentabilidade = viagem.valorFrete()
                .subtract(viagem.valorPedagio());

        return valorizacaoViagemService.aplicar(
                rentabilidade,
                viagem.destino()
        );
    }

    /**
     * Recebe as viagens de um único motorista ou veículo no mês.
     * Aplica a valorização, a limitação e soma os valores selecionados.
     * Uma lista vazia retorna 0.00.
     */
    public BigDecimal calcular(List<Viagem> viagens) {
        Objects.requireNonNull(
                viagens,
                "A lista de viagens é obrigatória."
        );

        List<BigDecimal> valoresValorizados = viagens.stream()
                .map(this::calcularViagem)
                .toList();

        return limitacaoViagensService.aplicar(valoresValorizados)
                .stream()
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
    }
}