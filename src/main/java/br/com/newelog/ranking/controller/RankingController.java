package br.com.newelog.ranking.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import br.com.newelog.ranking.dto.RankingResponse;
import br.com.newelog.ranking.service.RankingService;

@RestController
public class RankingController {
    private final RankingService service;
    public RankingController(RankingService service) { this.service = service; }

    @GetMapping({"/ranking", "/api/ranking"})
    public RankingResponse consultar(@RequestParam("ano") int ano, @RequestParam("mes") int mes,
            @RequestParam(name = "tipo", defaultValue = "motoristas") String tipo,
            @RequestParam(name = "busca", required = false) String busca,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return service.consultar(ano, mes, tipo, busca, authorization);
    }
}
