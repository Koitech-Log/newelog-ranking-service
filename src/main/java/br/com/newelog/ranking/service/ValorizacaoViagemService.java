package br.com.newelog.ranking.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import br.com.newelog.ranking.config.ValorizacaoProperties;

@Service
public class ValorizacaoViagemService {
    private static final String SAO_PAULO = "sao paulo";
    private static final String RETICENCIAS = "\u2026";

    private final ValorizacaoProperties props;

    public ValorizacaoViagemService(ValorizacaoProperties props) {
        this.props = props;
    }

    /**
     * Devolve o valor valorizado se São Paulo estiver entre as cidades do destino;
     * senão, o valor base.
     */
    public BigDecimal aplicar(BigDecimal valorBase, String destino) {
        Objects.requireNonNull(valorBase, "valorBase é obrigatório");
        if (incluiSaoPaulo(destino)) {
            return valorBase.multiply(props.fatorSaoPaulo()).setScale(2, RoundingMode.HALF_UP);
        }
        return valorBase; // fora de qualquer regra = valor base
    }

    /**
     * O destino pode ser uma lista de cidades separadas por vírgula
     * ("Guarulhos, São Paulo, Diadema"). Conta quando QUALQUER cidade for
     * exatamente São Paulo, sem diferenciar acento, maiúscula ou espaços
     * ("Paulo Afonso" e "São Paulo do Potengi" não contam). Uma cidade
     * terminada em reticências veio de um destino resumido pelo
     * manifesto-service e pode estar cortada, então não conta.
     */
    private boolean incluiSaoPaulo(String destino) {
        if (destino == null) return false;
        return Arrays.stream(destino.split(","))
                .map(String::strip)
                .filter(cidade -> !cidade.endsWith(RETICENCIAS) && !cidade.endsWith("..."))
                .map(ValorizacaoViagemService::normalizar)
                .anyMatch(SAO_PAULO::equals);
    }

    private static String normalizar(String cidade) {
        return Normalizer.normalize(cidade, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")   // tira os acentos: "São" vira "Sao"
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
