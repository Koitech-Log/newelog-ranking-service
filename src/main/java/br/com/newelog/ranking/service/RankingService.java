package br.com.newelog.ranking.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import br.com.newelog.ranking.client.MotoristasClient;
import br.com.newelog.ranking.dto.RankingResponse;
import br.com.newelog.ranking.dto.RankingResponse.Item;
import br.com.newelog.ranking.client.MotoristasClient.Viagem;

@Service
public class RankingService {
    private final MotoristasClient client;
    private final PontuacaoRankingService pontuacaoService;

    public RankingService(
            MotoristasClient client,
            PontuacaoRankingService pontuacaoService
    ) {
    this.client = client;
    this.pontuacaoService = pontuacaoService;
    }

    public RankingResponse consultar(int ano, int mes, String tipo, String busca, String authorization) {
        if (ano < 1 || ano > 9999 || mes < 1 || mes > 12)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe ano entre 1 e 9999 e mês entre 1 e 12.");
        if (!List.of("motoristas", "veiculos").contains(tipo))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tipo deve ser motoristas ou veiculos.");
        if (busca != null && busca.length() > 150)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A busca deve ter no máximo 150 caracteres.");

        var periodo = YearMonth.of(ano, mes);
        var grupos = new LinkedHashMap<String, Grupo>();
        for (var m : client.consultarMes(ano, mes, authorization)) {
            for (var v : m.viagens()) {
                if (v == null || v.data() == null || v.valorFrete() == null || v.valorPedagio() == null
                        || v.valorFrete().signum() < 0 || v.valorPedagio().signum() < 0)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Viagem inválida no serviço de motoristas.");
                if (!YearMonth.from(v.data()).equals(periodo)) continue;
                String placa = m.placaVeiculo() == null ? null : m.placaVeiculo().replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
                if (placa != null && placa.isBlank()) placa = null;
                if (tipo.equals("veiculos") && placa == null) continue; // não cria um veículo fictício
                String chave = tipo.equals("motoristas") ? "m:" + m.id() : "v:" + placa;
                final String veiculo = placa;
                var g = grupos.computeIfAbsent(chave, k -> new Grupo(
                    tipo.equals("motoristas") ? m.id() : null,
                    tipo.equals("motoristas") ? m.nome() : null, veiculo));
                g.nomes.add(normalizar(m.nome()));
                g.documentos.add(digitos(m.cpfCnpj()));
                g.viagens++;
                g.frete = g.frete.add(v.valorFrete());
                g.custo = g.custo.add(v.valorPedagio());
                g.viagensDoMes.add(v);
                if (v.destino() != null && !v.destino().isBlank()) g.destinos.add(v.destino());
            }
        }

        // Calcula a pontuação após reunir todas as viagens do mês de cada grupo.
        for (var g : grupos.values()) {
            g.pontuacao = pontuacaoService.calcular(g.viagensDoMes);
        }
        // KOI-153: ordena pela pontuação calculada.
        // Desempate estável evita posições aleatórias entre chamadas.
        var ordenados = new ArrayList<>(grupos.values());
        ordenados.sort(Comparator.comparing((Grupo g) -> g.pontuacao).reversed()
            .thenComparing(Comparator.comparingLong((Grupo g) -> g.viagens).reversed())
            .thenComparing(g -> normalizar(g.nome != null ? g.nome : g.veiculo))
            .thenComparing(g -> g.id == null ? 0L : g.id));
        String termo = normalizar(busca);
        String termoPlaca = termo.replaceAll("[^a-z0-9]", "");
        String documento = digitos(busca);
        var items = new ArrayList<Item>();
        for (int i = 0; i < ordenados.size(); i++) {
            var g = ordenados.get(i);
            boolean encontrou = termo.isEmpty() || g.nomes.stream().anyMatch(n -> n.contains(termo))
                || normalizar(g.veiculo).contains(termo)
                || (!termoPlaca.isEmpty() && normalizar(g.veiculo).contains(termoPlaca))
                || (!documento.isEmpty() && busca.trim().matches("[0-9. /-]+")
                    && g.documentos.stream().anyMatch(d -> d.contains(documento)));
            if (encontrou) items.add(new Item(i + 1, g.id, g.nome, g.veiculo, g.viagens,
                g.frete, g.custo, g.resultado(), null, List.copyOf(g.destinos), g.pontuacao));
        }
        // totalParticipantes conta o mês/tipo inteiro, antes da busca.
        return new RankingResponse(ano, mes, tipo, "PONTUACAO", ordenados.size(), List.copyOf(items));
    }

    private static String normalizar(String s) {
        return s == null ? "" : Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
    private static String digitos(String s) { return s == null ? "" : s.replaceAll("[^0-9]", ""); }

    private static class Grupo {
        final Long id;
        final String nome, veiculo;
        final TreeSet<String> nomes = new TreeSet<>(), documentos = new TreeSet<>(), destinos = new TreeSet<>();
        
        final List<Viagem> viagensDoMes = new ArrayList<>();
        
        long viagens;
        BigDecimal frete = BigDecimal.ZERO, custo = BigDecimal.ZERO;
        BigDecimal pontuacao = new BigDecimal("0.00");
        Grupo(Long id, String nome, String veiculo) { this.id = id; this.nome = nome; this.veiculo = veiculo; }
        BigDecimal resultado() { return frete.subtract(custo); }
    }
}
