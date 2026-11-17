package br.com.newelog.ranking.dto;

import java.math.BigDecimal;
import java.util.List;

public record RankingResponse(int ano, int mes, String tipo, String criterioOrdenacao,
                              int totalParticipantes, List<Item> dados) {
    public record Item(int posicao, Long motoristaId, String motorista, String veiculo,
                       long quantidadeViagens, BigDecimal freteTotal, BigDecimal custoTotal,
                       BigDecimal resultado, BigDecimal distanciaKm, List<String> destinos) {}
}
