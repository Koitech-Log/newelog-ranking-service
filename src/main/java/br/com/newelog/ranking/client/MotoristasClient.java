package br.com.newelog.ranking.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MotoristasClient {
    private final RestClient client;

    public MotoristasClient(RestClient client) {
        this.client = client;
    }

    // Usa a consulta mensal existente para buscar detalhes somente dos participantes do mês.
    // Nenhuma busca de usuário é enviada aqui: posições são definidas no conjunto completo.
    public List<Motorista> consultarMes(int ano, int mes, String authorization) {
        try {
            var resumo = client.get().uri("/api/dashboard/rentabilidade-media?ano={ano}&mes={mes}", ano, mes)
                .header("Authorization", authorization).retrieve().body(ResumoMensal.class);
            if (resumo == null || resumo.porMotorista() == null) throw respostaInvalida();
            var result = new ArrayList<Motorista>();
            var ids = new HashSet<Long>();
            for (var item : resumo.porMotorista()) {
                if (item == null || item.id() == null || item.id() <= 0) throw respostaInvalida();
                if (!ids.add(item.id())) continue;
                var motorista = client.get().uri("/api/motoristas/{id}", item.id())
                    .header("Authorization", authorization).retrieve().body(Motorista.class);
                if (motorista == null || !item.id().equals(motorista.id()) || motorista.viagens() == null
                        || motorista.nome() == null || motorista.nome().isBlank())
                    throw respostaInvalida();
                result.add(motorista);
            }
            return List.copyOf(result);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)
                throw new ResponseStatusException(e.getStatusCode(), "Acesso recusado pelo serviço de motoristas.");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Falha ao consultar o serviço de motoristas.");
        } catch (ResourceAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Serviço de motoristas indisponível.");
        } catch (RestClientException e) {
            throw respostaInvalida();
        }
    }

    private ResponseStatusException respostaInvalida() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Resposta inválida do serviço de motoristas.");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResumoMensal(List<Participante> porMotorista) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Participante(Long id) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Motorista(Long id, String nome, String cpfCnpj, String placaVeiculo, List<Viagem> viagens) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Viagem(LocalDate data, String destino, BigDecimal valorFrete, BigDecimal valorPedagio) {}
}
